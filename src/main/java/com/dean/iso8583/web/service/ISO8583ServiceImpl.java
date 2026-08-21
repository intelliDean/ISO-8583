package com.dean.iso8583.web.service;

import com.dean.iso8583.core.IsoPacker;
import com.dean.iso8583.core.IsoSpec;
import com.dean.iso8583.core.IsoUnpacker;
import com.dean.iso8583.core.clearing.BatchClearingEngine;
import com.dean.iso8583.core.clearing.dto.ClearingBatch;
import com.dean.iso8583.core.clearing.dto.ClearingRecord;
import com.dean.iso8583.core.crypto.*;
import com.dean.iso8583.core.crypto.dto.DukptDtos;
import com.dean.iso8583.core.dto.*;
import com.dean.iso8583.core.echo.IsoEchoManager;
import com.dean.iso8583.core.echo.dto.ChannelStatusReport;
import com.dean.iso8583.core.echo.dto.EchoResult;
import com.dean.iso8583.core.emv.EmvTlvParser;
import com.dean.iso8583.core.emv.dto.EmvParseResult;
import com.dean.iso8583.core.event.OutboxEventRepository;
import com.dean.iso8583.core.event.OutboxPollerService;
import com.dean.iso8583.core.lock.DistributedLockService;
import com.dean.iso8583.core.metrics.IsoMetrics;
import com.dean.iso8583.core.reversal.TransactionRecord;
import com.dean.iso8583.core.reversal.TransactionStore;
import com.dean.iso8583.core.spec.IsoSpecRegistry;
import com.dean.iso8583.web.data.dto.*;
import com.dean.iso8583.web.data.utils.IsoMtiDescriptions;
import com.dean.iso8583.web.data.utils.IsoTcpClient;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Developer Note:
 * <p>Enterprise ISO 8583 Service Implementation.</p>
 * <p>Orchestrates message packing, unpacking, dynamic spec resolution, host simulation,
 * transaction state queries, keep-alive network heartbeat telemetry, cryptographic operations,
 * Dual-Message System (DMS) batch clearing &amp; settlement reconciliation, and distributed resiliency telemetry.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ISO8583ServiceImpl implements ISO8583Service {

    private final IsoTcpClient isoTcpClient;
    private final IsoSpecRegistry isoSpecRegistry;
    private final TransactionStore transactionStore;
    private final IsoEchoManager isoEchoManager;
    private final CryptoKeyRegistry cryptoKeyRegistry;
    private final BatchClearingEngine batchClearingEngine;
    private final OutboxEventRepository outboxRepository;
    private final OutboxPollerService outboxPollerService;
    private final DistributedLockService lockService;
    private final IsoMetrics isoMetrics;

    @PostConstruct
    public void initMetricsGauges() {
        isoMetrics.registerStateGauges(
                transactionStore::size,
                outboxRepository::countPending
        );
    }

    @Override
    public Map<Integer, IsoDTOs.IsoFieldDef> getCatalog() {
        return IsoSpec.getAllFieldDefs();
    }

    @Override
    public Map<String, IsoDTOs.IsoSpecDefinition> getSpecs() {
        return isoSpecRegistry.getAllSpecs();
    }

    @Override
    public WebDTOs.UnpackResult unpackMessage(WebDTOs.UnpackRequest request) {
        IsoDTOs.IsoSpecDefinition spec = isoSpecRegistry.getSpec(request.specId());

        IsoMessage message = IsoUnpacker.unpack(request.payload(), request.hasHeader(), spec);

        return buildUnpackResult(message, spec);
    }

    @Override
    public WebDTOs.PackResult packMessage(WebDTOs.PackRequest request) {
        IsoDTOs.IsoSpecDefinition spec = isoSpecRegistry.getSpec(request.specId());

        IsoMessage message = buildIsoMessage(request);

        String rawPayload = IsoPacker.packToString(message, spec);

        return new WebDTOs.PackResult(
                rawPayload,
                message.getPrimaryBitmapHex(),
                message.getSecondaryBitmapHex(),
                rawPayload.length()
        );
    }

    @Override
    public WebDTOs.SimulateResult simulateTransaction(WebDTOs.SimulateRequest request) {
        return isoTcpClient.simulate(request.rawPayload());
    }

    @Override
    public WebDTOs.EmvParseResponse parseEmv(WebDTOs.EmvParseRequest request) {
        EmvParseResult result = EmvTlvParser.parse(request.de55Hex());

        String arqcValue  = result.getValue("9F26");
        String atcValue   = result.getValue("9F36");
        Integer atcDecimal = atcValue != null
                ? Integer.parseInt(atcValue, 16)
                : null;

        log.info("EMV DE55 parsed — tags={}, hasARQC={}, ATC={}",
                result.tags().size(),
                result.hasTag("9F26"),
                atcDecimal);

        return new WebDTOs.EmvParseResponse(
                result.rawHex(),
                result.tags().size(),
                result.tags(),
                result.hasTag("9F26"),
                result.hasTag("9F36"),
                arqcValue,
                atcValue,
                atcDecimal
        );
    }

    @Override
    public Collection<TransactionRecord> getTransactions() {
        return transactionStore.findAll();
    }

    @Override
    public EchoResult triggerEcho() {
        return isoEchoManager.triggerEcho();
    }

    @Override
    public ChannelStatusReport getEchoStatus() {
        return isoEchoManager.getChannelStatus();
    }

    @Override
    public WebDTOs.PinEncodeResponse encodePin(WebDTOs.PinEncodeRequest request) {
        byte[] key = resolveKey(request.keyHex(), request.keyId(), "DEFAULT_ZPK_ACQ");
        String clearBlock     = IsoPinBlockEngine.encodeClearPinBlock(request.pin(), request.pan(), request.format());
        String encryptedBlock = IsoPinBlockEngine.encryptPin(request.pin(), request.pan(), request.format(), key);

        isoMetrics.recordCryptoOperation("PIN_ENCODE", request.format().name(), true);

        return new WebDTOs.PinEncodeResponse(
                clearBlock,
                encryptedBlock,
                request.format(),
                resolveKeyName(request.keyId(), request.keyHex(), "DEFAULT_ZPK_ACQ")
        );
    }

    @Override
    public WebDTOs.PinTranslateResponse translatePin(WebDTOs.PinTranslateRequest request) {
        byte[] srcKey = resolveKey(request.srcKeyHex(), request.srcKeyId(), "DEFAULT_ZPK_ACQ");
        byte[] dstKey = resolveKey(request.dstKeyHex(), request.dstKeyId(), "DEFAULT_ZPK_ISS");
        String dstPan = request.dstPan() != null ? request.dstPan() : request.srcPan();

        String translated = IsoPinBlockEngine.translatePinBlock(
                request.encryptedBlockHex(),
                request.srcPan(),
                request.srcFormat(),
                srcKey,
                dstPan,
                request.dstFormat(),
                dstKey
        );

        isoMetrics.recordCryptoOperation("PIN_TRANSLATE", request.srcFormat() + "->" + request.dstFormat(), true);

        return new WebDTOs.PinTranslateResponse(translated, request.srcFormat(), request.dstFormat(), true);
    }

    @Override
    public WebDTOs.MacGenerateResponse generateMac(WebDTOs.MacGenerateRequest request) {
        byte[] key = resolveKey(request.keyHex(), request.keyId(), "DEFAULT_MAK");

        byte[] payloadBytes = request.rawPayload().getBytes(StandardCharsets.US_ASCII);
        String mac = MacEngine.calculateRetailMac(payloadBytes, key);

        isoMetrics.recordCryptoOperation("MAC_GENERATE", "ISO-9797-1-ALG3", true);

        return new WebDTOs.MacGenerateResponse(
                mac,
                "ISO 9797-1 Algorithm 3 (Retail MAC)",
                resolveKeyName(request.keyId(), request.keyHex(), "DEFAULT_MAK")
        );
    }

    @Override
    public WebDTOs.MacVerifyResponse verifyMac(WebDTOs.MacVerifyRequest request) {
        byte[] key = resolveKey(request.keyHex(), request.keyId(), "DEFAULT_MAK");

        byte[] payloadBytes  = request.rawPayload().getBytes(StandardCharsets.US_ASCII);
        String calculatedMac = MacEngine.calculateRetailMac(payloadBytes, key);

        boolean valid = calculatedMac.equalsIgnoreCase(request.expectedMac().trim());

        isoMetrics.recordCryptoOperation("MAC_VERIFY", "ISO-9797-1-ALG3", valid);

        return new WebDTOs.MacVerifyResponse(valid, calculatedMac, valid ? "MAC valid" : "MAC mismatch");
    }

    @Override
    public ClearingBatch generateClearingBatch(WebDTOs.ClearingBatchRequest request) {
        String networkId = (request != null && request.networkId() != null) ? request.networkId() : "MASTERCARD-IPM";
        ClearingBatch batch = batchClearingEngine.generateClearingBatch(networkId);
        isoMetrics.recordClearingBatch(networkId, batch.presentmentCount(), true);
        return batch;
    }

    @Override
    public ClearingRecord fileChargeback(WebDTOs.ChargebackRequest request) {
        ClearingRecord record = batchClearingEngine.fileChargeback(
                request.stan(),
                request.maskedPan(),
                request.amountIso(),
                request.disputeReasonCode()
        );
        isoMetrics.recordChargeback(request.disputeReasonCode(), true);
        return record;
    }

    @Override
    public ClearingBatch parseClearingBatch(WebDTOs.ClearingParseRequest request) {
        return batchClearingEngine.parseClearingFile(request.rawBatchFile());
    }

    @Override
    public Collection<ClearingBatch> getClearingBatches() {
        return batchClearingEngine.getBatches();
    }

    @Override
    public Collection<ClearingRecord> getChargebacks() {
        return batchClearingEngine.getChargebacks();
    }

    @Override
    public WebDTOs.ResiliencyStatusResponse getResiliencyStatus() {
        String persistence = "PostgreSQL (Schema V1.0) / In-Memory Active Fallback";
        String lockEngine  = "Redis 7 / In-Memory Reentrant Cluster Lock";
        String outbox      = "Active (Transactional Outbox Pattern & Kafka Streamer)";

        return new WebDTOs.ResiliencyStatusResponse(
                persistence,
                transactionStore.size(),
                batchClearingEngine.getBatches().size(),
                batchClearingEngine.getChargebacks().size(),
                lockEngine,
                outbox,
                outboxRepository.countPending(),
                outboxPollerService.getTotalDispatched(),
                outboxRepository.findPendingEvents(10)
        );
    }

    @Override
    public DukptDtos.DeriveIpekResponse deriveDukptIpek(DukptDtos.DeriveIpekRequest request) {
        byte[] bdk      = resolveKey(request.bdkHex(), null, "DEFAULT_BDK");
        byte[] ksn      = CryptoUtils.hexToBytes(request.ksnHex());
        byte[] ipek     = DukptEngine.deriveIpek(bdk, ksn);
        byte[] maskedKsn = DukptEngine.maskKsn(ksn);

        isoMetrics.recordCryptoOperation("DUKPT_IPEK", "2TDEA", true);

        return new DukptDtos.DeriveIpekResponse(
                CryptoUtils.bytesToHex(bdk),
                request.ksnHex().toUpperCase(),
                CryptoUtils.bytesToHex(maskedKsn),
                CryptoUtils.bytesToHex(ipek),
                DukptEngine.extractKeySetId(ksn),
                DukptEngine.extractDeviceId(ksn),
                DukptEngine.extractTransactionCounter(ksn)
        );
    }

    @Override
    public DukptDtos.DeriveKeyResponse deriveDukptKey(DukptDtos.DeriveKeyRequest request) {
        byte[] bdk     = resolveKey(request.bdkHex(), null, "DEFAULT_BDK");
        byte[] ksn     = CryptoUtils.hexToBytes(request.ksnHex());
        byte[] ipek    = DukptEngine.deriveIpek(bdk, ksn);
        byte[] txnKey  = DukptEngine.deriveTransactionKey(ipek, ksn);
        byte[] pinKey  = DukptEngine.derivePinKey(txnKey);
        byte[] macKey  = DukptEngine.deriveMacKey(txnKey);
        byte[] dataKey = DukptEngine.deriveDataKey(txnKey);

        isoMetrics.recordCryptoOperation("DUKPT_KEY_DERIVE", "2TDEA", true);

        return new DukptDtos.DeriveKeyResponse(
                CryptoUtils.bytesToHex(bdk),
                request.ksnHex().toUpperCase(),
                CryptoUtils.bytesToHex(ipek),
                CryptoUtils.bytesToHex(txnKey),
                CryptoUtils.bytesToHex(pinKey),
                CryptoUtils.bytesToHex(macKey),
                CryptoUtils.bytesToHex(dataKey),
                DukptEngine.extractKeySetId(ksn),
                DukptEngine.extractDeviceId(ksn),
                DukptEngine.extractTransactionCounter(ksn)
        );
    }

    @Override
    public DukptDtos.DecryptDukptPinResponse decryptDukptPin(DukptDtos.DecryptDukptPinRequest request) {
        byte[] bdk    = resolveKey(request.bdkHex(), null, "DEFAULT_BDK");
        byte[] ksn    = CryptoUtils.hexToBytes(request.ksnHex());
        PinBlockFormat format = (request.format() != null && !request.format().isBlank())
                ? PinBlockFormat.valueOf(request.format().toUpperCase())
                : PinBlockFormat.FORMAT_0;

        byte[] ipek       = DukptEngine.deriveIpek(bdk, ksn);
        byte[] txnKey     = DukptEngine.deriveTransactionKey(ipek, ksn);
        byte[] pinKey     = DukptEngine.derivePinKey(txnKey);

        byte[] cipherBytes = CryptoUtils.hexToBytes(request.encryptedPinBlockHex());
        byte[] clearBytes  = CryptoUtils.desDecryptEcb(cipherBytes, pinKey);
        String clearHex    = CryptoUtils.bytesToHex(clearBytes);
        String clearPin    = IsoPinBlockEngine.decodeClearPinBlock(clearHex, request.pan(), format);

        isoMetrics.recordCryptoOperation("DUKPT_PIN_DECRYPT", format.name(), true);

        return new DukptDtos.DecryptDukptPinResponse(
                clearPin,
                clearHex,
                request.encryptedPinBlockHex().toUpperCase(),
                CryptoUtils.bytesToHex(pinKey),
                request.ksnHex().toUpperCase(),
                DukptEngine.extractTransactionCounter(ksn),
                format.name(),
                true,
                "DUKPT PIN block successfully decrypted via PEK variant"
        );
    }

    private byte[] resolveKey(String keyHex, String keyId, String defaultKeyId) {
        if (keyHex != null && !keyHex.isBlank()) {
            return CryptoUtils.hexToBytes(keyHex);
        }
        String id = (keyId != null && !keyId.isBlank()) ? keyId : defaultKeyId;
        return cryptoKeyRegistry.getKey(id)
                .orElseThrow(() -> new IllegalArgumentException("Cryptographic key not found in registry: " + id));
    }

    private WebDTOs.UnpackResult buildUnpackResult(IsoMessage message, IsoDTOs.IsoSpecDefinition spec) {

        List<WebDTOs.FieldDetail> details = message.getFields()
                .entrySet()
                .stream()
                .map(entry -> toFieldDetail(entry, spec))
                .toList();

        List<Integer> activeFields = message.getFields()
                .keySet()
                .stream()
                .toList();

        return new WebDTOs.UnpackResult(
                message.getHeader(),
                message.getMti(),
                IsoMtiDescriptions.describe(message.getMti()),
                message.getPrimaryBitmapHex(),
                message.getSecondaryBitmapHex(),
                activeFields,
                details
        );
    }

    private WebDTOs.FieldDetail toFieldDetail(Map.Entry<Integer, String> entry, IsoDTOs.IsoSpecDefinition spec) {
        int fieldId = entry.getKey();

        IsoDTOs.IsoFieldDef definition = (spec != null && spec.getFieldDef(fieldId) != null)
                ? spec.getFieldDef(fieldId)
                : IsoSpec.getFieldDef(fieldId);

        return new WebDTOs.FieldDetail(
                fieldId,
                definition != null ? definition.name() : "Custom Field",
                definition != null ? definition.type().name() : IsoFieldType.LLVAR_ALPHA.name(),
                entry.getValue()
        );
    }

    /**
     * Resolves the display name of the active cryptographic key for API responses.
     *
     * <p>Priority: explicit keyId > inline keyHex (reported as "CUSTOM_KEY") > default ID.</p>
     */
    private String resolveKeyName(String keyId, String keyHex, String defaultId) {
        if (keyId != null && !keyId.isBlank()) return keyId;
        if (keyHex != null && !keyHex.isBlank()) return "CUSTOM_KEY";
        return defaultId;
    }

    private IsoMessage buildIsoMessage(WebDTOs.PackRequest request) {
        IsoMessage message = new IsoMessage(request.mti());
        message.setHeader(request.header());

        // PackRequest.fields is Map<Object,String>; Jackson deserializes JSON numeric keys
        // as Integer objects at runtime. We call toString() + parseInt() to safely widen
        // any key type (Integer, Long, or a String sent by a client) to a primitive int.
        request.fields().forEach((key, value) -> message.setField(Integer.parseInt(key.toString()), value));

        return message;
    }
}
