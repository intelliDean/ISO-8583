package com.dean.iso8583.core.clearing;

import com.dean.iso8583.core.IsoPacker;
import com.dean.iso8583.core.IsoUnpacker;
//import com.dean.iso8583.core.clearing.dto.*;
import com.dean.iso8583.core.clearing.dto.*;
import com.dean.iso8583.core.clearing.enums.ClearingRecordType;
import com.dean.iso8583.core.clearing.utils.InterchangeFeeCalculator;
import com.dean.iso8583.core.dto.IsoMessage;
import com.dean.iso8583.core.event.IsoEventPublisher;
import com.dean.iso8583.core.event.IsoEventType;
import com.dean.iso8583.core.lock.DistributedLockService;
import com.dean.iso8583.core.persistence.ClearingBatchRepository;
import com.dean.iso8583.core.reversal.TransactionRecord;
import com.dean.iso8583.core.reversal.TransactionState;
import com.dean.iso8583.core.reversal.TransactionStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static com.dean.iso8583.core.clearing.utils.ClearingUtils.*;

/**
 * Enterprise Dual-Message System (DMS) Batch Clearing &amp; Settlement Engine
 * with Distributed Locking, Repository Persistence, and Kafka Outbox Event Streaming.
 *
 * <h2>Core Functions</h2>
 * <ul>
 *   <li><b>Batch Presentment (1240)</b>: Aggregates settled authorizations into standard clearing files.</li>
 *   <li><b>Interchange Fee Calculation</b>: Automatically calculates scheme assessment &amp; interchange fees.</li>
 *   <li><b>Chargeback Management (1440)</b>: Processes issuer dispute filings and chargeback reversals.</li>
 *   <li><b>Batch Reconciliation (1644)</b>: Generates file header and reconciliation trailer with control totals.</li>
 *   <li><b>Outbox Streaming</b>: Emits events to Kafka topics for core banking ledger reconciliation.</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BatchClearingEngine {

    private final TransactionStore transactionStore;
    private final ClearingBatchRepository clearingRepository;
    private final DistributedLockService lockService;
    private final IsoEventPublisher eventPublisher;

    private final AtomicInteger batchSequence = new AtomicInteger(1);


    /**
     * Generates an end-of-day clearing batch from all eligible {@code AUTHORISED} transactions
     * under a cluster-wide distributed lock.
     *
     * @param networkId card network scheme (e.g. "MASTERCARD-IPM", "VISA-BASE2")
     * @return constructed {@link ClearingBatch} with control totals and raw batch file text
     */
    public ClearingBatch generateClearingBatch(String networkId) {

        final String lockKey = buildLockKey(resolveNetworkId(networkId));

        return lockService.executeWithLock(
                lockKey,
                LOCK_WAIT_MILLIS,
                LOCK_LEASE_MILLIS,
                () -> buildAndPersistBatch(networkId)
        );
    }

    private ClearingBatch buildAndPersistBatch(String networkId) {
        String batchId = nextBatchId();
        String settlementDate = LocalDate.now().format(DATE_FORMATTER);

        List<ClearingRecord> records = new ArrayList<>();
        StringBuilder fileBuilder = new StringBuilder();

        appendFileHeader(fileBuilder, batchId, settlementDate);

        ClearingDTOs.PresentmentResult presentments = appendPresentments(fileBuilder, records);
        int chargebackCount = appendChargebacks(fileBuilder, records);

        ControlTotals totals = ControlTotals.of(
                presentments.grossCents(),
                presentments.interchangeCents(),
                records.size()
        );

        appendFileTrailer(fileBuilder, totals);

        ClearingBatch batch = ClearingBatch.builder()
                .batchId(batchId)
                .settlementDate(settlementDate)
                .networkId(resolveNetworkId(networkId))
                .totalTransactions(records.size())
                .presentmentCount(records.size())
                .chargebackCount(chargebackCount)
                .totalGrossAmountIso(totals.grossIso())
                .totalInterchangeFeeIso(totals.feeIso())
                .netSettlementAmountIso(totals.netIso())
                .records(records)
                .rawBatchFile(fileBuilder.toString())
                .generatedAt(Instant.now())
                .build();

        persistAndPublish(batch, presentments, totals);
        return batch;
    }

    private String resolveNetworkId(String networkId) {
        return StringUtils.hasText(networkId)
                ? networkId.trim()
                : DEFAULT_NETWORK_ID;
    }

    private String buildLockKey(String networkId) {
        String lockNetworkId = StringUtils.hasText(networkId)
                ? networkId
                : DEFAULT_LOCK_NETWORK;

        return "lock:clearing:batch:%s".formatted(lockNetworkId);
    }

    private String nextBatchId() {
        return "BATCH-%s-%04d".formatted(
                LocalDate.now().format(DATE_FORMATTER),
                batchSequence.getAndIncrement()
        );
    }

    private void appendFileHeader(StringBuilder fileBuilder, String batchId, String settlementDate) {
        String headerMti = ClearingRecordType.FILE_HEADER.getMti();

        fileBuilder.append("HDR:")
                .append(headerMti).append(':')
                .append(batchId).append(':')
                .append(settlementDate).append('\n');
    }

    /**
     * Builds 1240 First Presentment records from all eligible AUTHORISED transactions,
     * appending each packed message to the file and accumulating control totals.
     */
    private ClearingDTOs.PresentmentResult appendPresentments(StringBuilder fileBuilder, List<ClearingRecord> records) {
        long grossCents = 0;
        long interchangeCents = 0;
        int count = 0;

        for (TransactionRecord txn : transactionStore.findAll()) {

            if (!isEligibleForPresentment(txn)) continue;

            ClearingDTOs.PresentmentEntry entry = buildPresentmentEntry(txn);
            records.add(entry.record());
            fileBuilder.append(entry.rawPacked())
                    .append("\n");

            grossCents += entry.amountCents();
            interchangeCents += entry.feeCents();
            count++;
        }

        return ClearingDTOs.PresentmentResult.builder()
                .grossCents(grossCents)
                .interchangeCents(interchangeCents)
                .count(count)
                .build();
    }

    private boolean isEligibleForPresentment(TransactionRecord txn) {
        return txn != null
                && txn.state() == TransactionState.AUTHORISED
                && txn.authorisedAmount() != null;
    }

    private ClearingDTOs.PresentmentEntry buildPresentmentEntry(TransactionRecord txn) {
        String amountIso = txn.authorisedAmount();
        String interchangeFeeIso = InterchangeFeeCalculator.calculateFee(amountIso);

        long amtCents = Long.parseLong(amountIso);
        long feeCents = Long.parseLong(interchangeFeeIso);
        long netSettlementCents = Math.max(0, amtCents - feeCents);
        String netSettlementIso = "%012d".formatted(netSettlementCents);

        IsoMessage msg1240 = build1240Message(txn, interchangeFeeIso);
        String rawPacked = IsoPacker.packToString(msg1240);

        ClearingRecord record = ClearingRecord.fromAuthorisation(
                "REC-%s".formatted(txn.stan()),
                txn.maskedPan(),
                amountIso,
                interchangeFeeIso,
                netSettlementIso,
                txn.currencyCode() != null ? txn.currencyCode() : CURRENCY_CODE_DEFAULT,
                txn.rrn(),
                txn.authCode(),
                txn.stan(),
                txn.terminalId(),
                txn.merchantId(),
                rawPacked
        );

        return new ClearingDTOs.PresentmentEntry(record, rawPacked, amtCents, feeCents);
    }

    /**
     * Appends any pending 1440 Chargebacks to the file and record list.
     *
     * @return number of chargebacks included
     */
    private int appendChargebacks(StringBuilder fileBuilder, List<ClearingRecord> records) {
        int chargebackCount = 0;
        for (ClearingRecord cb : clearingRepository.findAllChargebacks()) {
            records.add(cb);
            if (cb.rawPackedIso() != null) {
                fileBuilder.append(cb.rawPackedIso()).append("\n");
            }
            chargebackCount++;
        }
        return chargebackCount;
    }

    private void appendFileTrailer(StringBuilder fileBuilder, ControlTotals totals) {
        String trailerLine = "TRL:%s:COUNT=%d:GROSS=%s:FEE=%s:NET=%s".formatted(
                ClearingRecordType.FILE_TRAILER.getMti(),
                totals.recordCount(),
                totals.grossIso(),
                totals.feeIso(),
                totals.netIso()
        );

        fileBuilder.append(trailerLine);
    }

    private void persistAndPublish(ClearingBatch batch, ClearingDTOs.PresentmentResult presentments, ControlTotals totals) {
        clearingRepository.saveBatch(batch);
        log.info("Generated Clearing Batch {} — {} presentments, gross=${}, interchange=${}, net=${}",
                batch.batchId(), presentments.count(),
                formatCents(totals.grossCents()),
                formatCents(totals.interchangeCents()),
                formatCents(totals.netCents())
        );

        eventPublisher.publish(AGGREGATE_TYPE_CLEARING_BATCH, batch.batchId(), IsoEventType.CLEARING_BATCH_GENERATED, batch);
    }


    /**
     * Files a 1440 Chargeback dispute against a previously authorized/settled transaction
     * under distributed locking and repository persistence.
     *
     * @param stan              original transaction STAN
     * @param maskedPan         masked cardholder PAN
     * @param amountIso         disputed amount
     * @param disputeReasonCode scheme reason code (e.g. "4837" Fraud, "4853" Defective Merchandise)
     * @return created {@link ClearingRecord}
     */
    public ClearingRecord fileChargeback(
            String stan,
            String maskedPan,
            String amountIso,
            String disputeReasonCode
    ) {

        String lockKey = "lock:chargeback:stan:%s".formatted(stan);
        return lockService.executeWithLock(
                lockKey,
                WAIT_TIMEOUT,
                LOCK_WAIT_MILLIS,
                () -> buildAndPersistChargeback(stan, maskedPan, amountIso, disputeReasonCode)
        );
    }

    private ClearingRecord buildAndPersistChargeback(
            String stan,
            String maskedPan,
            String amountIso,
            String disputeReasonCode
    ) {
        String recordId = nextChargebackRecordId(stan);
        String reasonCode = resolveDisputeReasonCode(disputeReasonCode);

        String rawPacked = pack1440Message(stan, maskedPan, amountIso, reasonCode);
        ClearingRecord record = buildChargebackRecord(recordId, stan, maskedPan, amountIso, reasonCode, rawPacked);

        persistAndPublishChargeback(record, stan, maskedPan, amountIso, disputeReasonCode);
        return record;
    }

    private String nextChargebackRecordId(String stan) {
        return "CB-%s-%s".formatted(stan, System.currentTimeMillis());
    }

    private String resolveDisputeReasonCode(String disputeReasonCode) {
        return disputeReasonCode != null
                ? disputeReasonCode
                : DEFAULT_DISPUTE_CODE;
    }

    private String pack1440Message(String stan, String maskedPan, String amountIso, String reasonCode) {
        IsoMessage msg1440 = new IsoMessage(MTI_I44O);

        msg1440.setHeader(TPDU);
        msg1440.setField(2, maskedPan);
        msg1440.setField(3, DEFAULT_PROCESSING_CODE);
        msg1440.setField(4, amountIso);
        msg1440.setField(11, stan);
        msg1440.setField(25, reasonCode);
        return IsoPacker.packToString(msg1440);
    }

    private ClearingRecord buildChargebackRecord(
            String recordId,
            String stan,
            String maskedPan,
            String amountIso,
            String reasonCode,
            String rawPacked
    ) {
        return ClearingRecord.createChargeback(
                recordId,
                maskedPan,
                amountIso,
                CURRENCY_CODE_DEFAULT,
                RRN,
                AUTH_CODE,
                stan,
                reasonCode,
                rawPacked
        );
    }

    private void persistAndPublishChargeback(
            ClearingRecord record,
            String stan,
            String maskedPan,
            String amountIso,
            String disputeReasonCode
    ) {
        clearingRepository.saveChargeback(record);
        log.warn("Chargeback filed: ID={} STAN={} PAN={} Amount={} Reason={}",
                record.recordId(), stan, maskedPan, amountIso, disputeReasonCode);

        // Emit domain event for Kafka Outbox streaming
        eventPublisher.publish(AGGREGATE_TYPE_CHARGEBACK, record.recordId(), IsoEventType.CHARGEBACK_FILED, record);
    }


    /**
     * Parses an incoming raw batch clearing file string into structured records.
     */
    public ClearingBatch parseClearingFile(String rawBatchFile) {
        validateRawBatchFile(rawBatchFile);

        List<ClearingRecord> records = new ArrayList<>();
        LineParseTotals totals = new LineParseTotals();

        for (String rawLine : splitLines(rawBatchFile)) {
            String line = rawLine.trim();

            if (isSkippableLine(line)) continue;

            parseLine(line, records.size()).ifPresent(rec -> {
                records.add(rec);
                totals.accumulate(rec.recordType(), extractAmountCents(rec.amountIso()));
            });
        }

        return buildImportedBatch(rawBatchFile, records, totals);
    }

    private void validateRawBatchFile(String rawBatchFile) {
        if (rawBatchFile == null || rawBatchFile.isBlank()) {
            throw new IllegalArgumentException("Clearing batch file cannot be empty");
        }
    }

    private String[] splitLines(String rawBatchFile) {
        return rawBatchFile.split("\\r?\\n");
    }

    private boolean isSkippableLine(String line) {
        return line.isEmpty() || line.startsWith("HDR:") || line.startsWith("TRL:");
    }

    /**
     * Attempts to unpack a single ISO8583 line into a {@link ClearingRecord}.
     * Returns empty if the line could not be parsed, logging a warning.
     */
    private Optional<ClearingRecord> parseLine(String line, int currentRecordCount) {
        try {
            IsoMessage msg = unpackLine(line);
            return Optional.of(toClearingRecord(msg, line, currentRecordCount));
        } catch (Exception e) {
            log.warn("Could not unpack clearing line: {} - {}", line, e.getMessage());
            return Optional.empty();
        }
    }

    private IsoMessage unpackLine(String line) {
        boolean hasHeader = line.length() >= 14 && line.startsWith(TPDU);
        return IsoUnpacker.unpack(line, hasHeader);
    }

    private ClearingRecord toClearingRecord(IsoMessage msg, String rawLine, int currentRecordCount) {
        String amt = msg.getField(4);
        ClearingRecordType type = resolveRecordType(msg.getMti());

        return ClearingRecord.builder()
                .recordId("IMP-%d".formatted(currentRecordCount + 1))
                .recordType(type)
                .maskedPan(msg.getField(2))
                .processingCode(msg.getField(3))
                .amountIso(amt)
                .interchangeFeeIso("000000000000")
                .settlementAmountIso(amt)
                .currencyCode(msg.getField(49) != null ? msg.getField(49) : CURRENCY_CODE_DEFAULT)
                .rrn(msg.getField(37))
                .authCode(msg.getField(38))
                .stan(msg.getField(11))
                .terminalId(msg.getField(41))
                .merchantId(msg.getField(42))
                .disputeReasonCode(msg.getField(25))
                .rawPackedIso(rawLine)
                .timestamp(Instant.now())
                .build();
    }

    private ClearingRecordType resolveRecordType(String mti) {
        return mti.equals(MTI_I44O)
                ? ClearingRecordType.CHARGEBACK
                : ClearingRecordType.FIRST_PRESENTMENT;
    }

    private long extractAmountCents(String amountIso) {
        if (amountIso == null) return 0;

        try {
            return Long.parseLong(amountIso);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private ClearingBatch buildImportedBatch(
            String rawBatchFile,
            List<ClearingRecord> records,
            LineParseTotals totals
    ) {
        String batchId = "%s-%s".formatted(NETWORK_ID_IMPORTED, System.currentTimeMillis());
        String settlementDate = LocalDate.now().format(DATE_FORMATTER);

        String grossIso = "%012d".formatted(totals.getGrossCents());
        String feeIso = InterchangeFeeCalculator.calculateFee(grossIso);
        long netCents = Math.max(0, totals.getGrossCents() - Long.parseLong(feeIso));
        String netIso = "%012d".formatted(netCents);

        return ClearingBatch.builder()
                .batchId(batchId)
                .settlementDate(settlementDate)
                .networkId(NETWORK_ID_IMPORTED)
                .totalTransactions(records.size())
                .presentmentCount(totals.getPresentmentCount())
                .chargebackCount(totals.getChargebackCount())
                .totalGrossAmountIso(grossIso)
                .totalInterchangeFeeIso(feeIso)
                .netSettlementAmountIso(netIso)
                .records(records)
                .rawBatchFile(rawBatchFile)
                .generatedAt(Instant.now())
                .build();
    }


    /**
     * Retrieves all archived clearing batches.
     */
    public Collection<ClearingBatch> getBatches() {
        return clearingRepository.findAllBatches();
    }

    /**
     * Retrieves all active chargebacks.
     */
    public Collection<ClearingRecord> getChargebacks() {
        return clearingRepository.findAllChargebacks();
    }

    private IsoMessage build1240Message(TransactionRecord txn, String interchangeFeeIso) {

        IsoMessage msg = new IsoMessage(MTI_I240);
        msg.setHeader(TPDU);
        msg.setField(2, txn.maskedPan());
        msg.setField(3, txn.processingCode() != null ? txn.processingCode() : DEFAULT_PROCESSING_CODE);
        msg.setField(4, txn.authorisedAmount());

        if (txn.transmissionTime() != null) msg.setField(7, txn.transmissionTime());

        msg.setField(11, txn.stan());
        msg.setField(28, interchangeFeeIso);

        if (txn.rrn() != null) msg.setField(37, txn.rrn());
        if (txn.authCode() != null) msg.setField(38, txn.authCode());
        if (txn.terminalId() != null) msg.setField(41, txn.terminalId());
        if (txn.merchantId() != null) msg.setField(42, txn.merchantId());

        msg.setField(49, txn.currencyCode() != null ? txn.currencyCode() : CURRENCY_CODE_DEFAULT);
        return msg;
    }

    private String formatCents(long cents) {
        return "%.2f".formatted(cents / 100.0);
    }


}
