package com.dean.iso8583.core.clearing;

import com.dean.iso8583.core.IsoPacker;
import com.dean.iso8583.core.IsoUnpacker;
import com.dean.iso8583.core.dto.IsoMessage;
import com.dean.iso8583.core.reversal.TransactionRecord;
import com.dean.iso8583.core.reversal.TransactionState;
import com.dean.iso8583.core.reversal.TransactionStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Enterprise Dual-Message System (DMS) Batch Clearing &amp; Settlement Engine.
 *
 * <h2>Core Functions</h2>
 * <ul>
 *   <li><b>Batch Presentment (1240)</b>: Aggregates settled authorizations into standard clearing files.</li>
 *   <li><b>Interchange Fee Calculation</b>: Automatically calculates scheme assessment &amp; interchange fees.</li>
 *   <li><b>Chargeback Management (1440)</b>: Processes issuer dispute filings and chargeback reversals.</li>
 *   <li><b>Batch Reconciliation (1644)</b>: Generates file header and reconciliation trailer with control totals.</li>
 *   <li><b>File Export/Import</b>: Encodes and parses Mastercard IPM / Visa BASE II compatible batch files.</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BatchClearingEngine {

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd");
    private final TransactionStore transactionStore;
    private final AtomicInteger batchSequence = new AtomicInteger(1);

    // In-memory archive of processed clearing batches
    private final ConcurrentHashMap<String, ClearingBatch> batchArchive = new ConcurrentHashMap<>();
    // In-memory record of filed chargebacks
    private final ConcurrentHashMap<String, ClearingRecord> chargebackStore = new ConcurrentHashMap<>();

    /**
     * Generates an end-of-day clearing batch from all eligible {@code AUTHORISED} transactions.
     *
     * @param networkId card network scheme (e.g. "MASTERCARD-IPM", "VISA-BASE2")
     * @return constructed {@link ClearingBatch} with control totals and raw batch file text
     */
    public ClearingBatch generateClearingBatch(String networkId) {
        String batchId = "BATCH-" + LocalDate.now().format(DATE_FORMATTER) + "-" + String.format("%04d", batchSequence.getAndIncrement());
        String settlementDate = LocalDate.now().format(DATE_FORMATTER);

        Collection<TransactionRecord> storedTxns = transactionStore.findAll();
        List<ClearingRecord> records = new ArrayList<>();

        long totalGrossCents = 0;
        long totalInterchangeCents = 0;
        int presentmentCount = 0;

        StringBuilder fileBuilder = new StringBuilder();

        // 1. File Header (1644)
        String headerMti = ClearingRecordType.FILE_HEADER.getMti();
        fileBuilder.append(String.format("HDR:%s:%s:%s\n", headerMti, batchId, settlementDate));

        // 2. Generate 1240 First Presentments from Authorised records
        for (TransactionRecord txn : storedTxns) {
            if (txn.state() == TransactionState.AUTHORISED && txn.authorisedAmount() != null) {
                String amountIso = txn.authorisedAmount();
                String interchangeFeeIso = InterchangeFeeCalculator.calculateFee(amountIso);

                long amtCents = Long.parseLong(amountIso);
                long feeCents = Long.parseLong(interchangeFeeIso);
                long netSettlementCents = Math.max(0, amtCents - feeCents);
                String netSettlementIso = String.format("%012d", netSettlementCents);

                // Build 1240 IsoMessage
                IsoMessage msg1240 = build1240Message(txn, interchangeFeeIso, netSettlementIso);
                String rawPacked = IsoPacker.packToString(msg1240);

                ClearingRecord rec = ClearingRecord.fromAuthorisation(
                        "REC-" + txn.stan(),
                        txn.maskedPan(),
                        amountIso,
                        interchangeFeeIso,
                        netSettlementIso,
                        txn.currencyCode() != null ? txn.currencyCode() : "840",
                        txn.rrn(),
                        txn.authCode(),
                        txn.stan(),
                        txn.terminalId(),
                        txn.merchantId(),
                        rawPacked
                );

                records.add(rec);
                fileBuilder.append(rawPacked).append("\n");

                totalGrossCents += amtCents;
                totalInterchangeCents += feeCents;
                presentmentCount++;
            }
        }

        // 3. Include any pending 1440 Chargebacks
        int chargebackCount = 0;
        for (ClearingRecord cb : chargebackStore.values()) {
            records.add(cb);
            if (cb.rawPackedIso() != null) {
                fileBuilder.append(cb.rawPackedIso()).append("\n");
            }
            chargebackCount++;
        }

        // 4. File Trailer (1644 Reconciliation)
        long netSettlementTotalCents = Math.max(0, totalGrossCents - totalInterchangeCents);
        String grossIso = String.format("%012d", totalGrossCents);
        String feeIso = String.format("%012d", totalInterchangeCents);
        String netIso = String.format("%012d", netSettlementTotalCents);

        String trailerLine = String.format("TRL:%s:COUNT=%d:GROSS=%s:FEE=%s:NET=%s",
                ClearingRecordType.FILE_TRAILER.getMti(), records.size(), grossIso, feeIso, netIso);
        fileBuilder.append(trailerLine);

        ClearingBatch batch = new ClearingBatch(
                batchId,
                settlementDate,
                networkId != null ? networkId : "MASTERCARD-IPM",
                records.size(),
                presentmentCount,
                chargebackCount,
                grossIso,
                feeIso,
                netIso,
                records,
                fileBuilder.toString(),
                Instant.now()
        );

        batchArchive.put(batchId, batch);
        log.info("Generated Clearing Batch {} — {} presentments, gross=${}, interchange=${}, net=${}",
                batchId, presentmentCount, formatCents(totalGrossCents), formatCents(totalInterchangeCents), formatCents(netSettlementTotalCents));

        return batch;
    }

    /**
     * Files a 1440 Chargeback dispute against a previously authorized/settled transaction.
     *
     * @param stan               original transaction STAN
     * @param maskedPan          masked cardholder PAN
     * @param amountIso          disputed amount
     * @param disputeReasonCode  scheme reason code (e.g. "4837" Fraud, "4853" Defective Merchandise)
     * @return created {@link ClearingRecord}
     */
    public ClearingRecord fileChargeback(
            String stan,
            String maskedPan,
            String amountIso,
            String disputeReasonCode
    ) {
        String recordId = "CB-" + stan + "-" + System.currentTimeMillis();

        IsoMessage msg1440 = new IsoMessage("1440");
        msg1440.setHeader("6000000000");
        msg1440.setField(2, maskedPan);
        msg1440.setField(3, "000000");
        msg1440.setField(4, amountIso);
        msg1440.setField(11, stan);
        msg1440.setField(25, disputeReasonCode != null ? disputeReasonCode : "4837"); // Point of Service Condition Code / Reason

        String rawPacked = IsoPacker.packToString(msg1440);

        ClearingRecord record = ClearingRecord.createChargeback(
                recordId,
                maskedPan,
                amountIso,
                "840",
                "123456789012",
                "AUTH01",
                stan,
                disputeReasonCode != null ? disputeReasonCode : "4837",
                rawPacked
        );

        chargebackStore.put(recordId, record);
        log.warn("Chargeback filed: ID={} STAN={} PAN={} Amount={} Reason={}",
                recordId, stan, maskedPan, amountIso, disputeReasonCode);

        return record;
    }

    /**
     * Parses an incoming raw batch clearing file string into structured records.
     */
    public ClearingBatch parseClearingFile(String rawBatchFile) {
        if (rawBatchFile == null || rawBatchFile.isBlank()) {
            throw new IllegalArgumentException("Clearing batch file cannot be empty");
        }

        String[] lines = rawBatchFile.split("\\r?\\n");
        List<ClearingRecord> records = new ArrayList<>();
        String batchId = "IMPORTED-" + System.currentTimeMillis();
        String settlementDate = LocalDate.now().format(DATE_FORMATTER);

        long grossCents = 0;
        int presentmentCount = 0;
        int chargebackCount = 0;

        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("HDR:") || line.startsWith("TRL:")) {
                continue;
            }

            try {
                boolean hasHeader = line.length() >= 14 && line.startsWith("6000000000");
                IsoMessage msg = IsoUnpacker.unpack(line, hasHeader);

                String mti = msg.getMti();
                String pan = msg.getField(2);
                String amt = msg.getField(4);
                String stan = msg.getField(11);
                String rrn = msg.getField(37);
                String auth = msg.getField(38);

                ClearingRecordType type = "1440".equals(mti) ? ClearingRecordType.CHARGEBACK : ClearingRecordType.FIRST_PRESENTMENT;

                if (type == ClearingRecordType.CHARGEBACK) chargebackCount++;
                else presentmentCount++;

                if (amt != null) {
                    try { grossCents += Long.parseLong(amt); } catch (Exception ignored) {}
                }

                ClearingRecord rec = new ClearingRecord(
                        "IMP-" + (records.size() + 1),
                        type,
                        pan,
                        msg.getField(3),
                        amt,
                        "000000000000",
                        amt,
                        msg.getField(49) != null ? msg.getField(49) : "840",
                        rrn,
                        auth,
                        stan,
                        msg.getField(41),
                        msg.getField(42),
                        msg.getField(25),
                        line,
                        Instant.now()
                );
                records.add(rec);
            } catch (Exception e) {
                log.warn("Could not unpack clearing line: {} - {}", line, e.getMessage());
            }
        }

        String grossIso = String.format("%012d", grossCents);
        String feeIso = InterchangeFeeCalculator.calculateFee(grossIso);
        long netCents = Math.max(0, grossCents - Long.parseLong(feeIso));
        String netIso = String.format("%012d", netCents);

        return new ClearingBatch(
                batchId,
                settlementDate,
                "IMPORTED",
                records.size(),
                presentmentCount,
                chargebackCount,
                grossIso,
                feeIso,
                netIso,
                records,
                rawBatchFile,
                Instant.now()
        );
    }

    /**
     * Retrieves all archived clearing batches.
     */
    public Collection<ClearingBatch> getBatches() {
        return Collections.unmodifiableCollection(batchArchive.values());
    }

    /**
     * Retrieves all active chargebacks.
     */
    public Collection<ClearingRecord> getChargebacks() {
        return Collections.unmodifiableCollection(chargebackStore.values());
    }

    private IsoMessage build1240Message(TransactionRecord txn, String interchangeFeeIso, String netSettlementIso) {
        IsoMessage msg = new IsoMessage("1240");
        msg.setHeader("6000000000");
        msg.setField(2, txn.maskedPan());
        msg.setField(3, txn.processingCode() != null ? txn.processingCode() : "000000");
        msg.setField(4, txn.authorisedAmount());
        if (txn.transmissionTime() != null) msg.setField(7, txn.transmissionTime());
        msg.setField(11, txn.stan());
        msg.setField(28, interchangeFeeIso); // DE 28: Transaction Fee / Interchange Amount
        if (txn.rrn() != null) msg.setField(37, txn.rrn());
        if (txn.authCode() != null) msg.setField(38, txn.authCode());
        if (txn.terminalId() != null) msg.setField(41, txn.terminalId());
        if (txn.merchantId() != null) msg.setField(42, txn.merchantId());
        msg.setField(49, txn.currencyCode() != null ? txn.currencyCode() : "840");
        return msg;
    }

    private String formatCents(long cents) {
        return String.format("%.2f", cents / 100.0);
    }
}
