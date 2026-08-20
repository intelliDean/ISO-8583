package com.dean.iso8583.web.service;

import com.dean.iso8583.core.IsoPacker;
import com.dean.iso8583.core.IsoSpec;
import com.dean.iso8583.core.IsoUnpacker;
import com.dean.iso8583.core.clearing.BatchClearingEngine;
import com.dean.iso8583.core.clearing.dto.ClearingBatch;
import com.dean.iso8583.core.clearing.dto.ClearingRecord;
import com.dean.iso8583.core.crypto.CryptoKeyRegistry;
import com.dean.iso8583.core.crypto.CryptoUtils;
import com.dean.iso8583.core.crypto.IsoPinBlockEngine;
import com.dean.iso8583.core.crypto.MacEngine;
import com.dean.iso8583.core.dto.IsoFieldDef;
import com.dean.iso8583.core.dto.IsoFieldType;
import com.dean.iso8583.core.dto.IsoMessage;
import com.dean.iso8583.core.dto.IsoSpecDefinition;
import com.dean.iso8583.core.echo.dto.ChannelStatusReport;
import com.dean.iso8583.core.echo.dto.EchoResult;
import com.dean.iso8583.core.echo.IsoEchoManager;
import com.dean.iso8583.core.emv.dto.EmvParseResult;
import com.dean.iso8583.core.emv.EmvTlvParser;
import com.dean.iso8583.core.event.OutboxEventRepository;
import com.dean.iso8583.core.event.OutboxPollerService;
import com.dean.iso8583.core.lock.DistributedLockService;
import com.dean.iso8583.core.reversal.TransactionRecord;
import com.dean.iso8583.core.reversal.TransactionStore;
import com.dean.iso8583.core.spec.IsoSpecRegistry;
import com.dean.iso8583.web.data.dto.*;
import com.dean.iso8583.web.data.utils.IsoMtiDescriptions;
import com.dean.iso8583.web.data.utils.IsoTcpClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

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

    @Override
    public Map<Integer, IsoFieldDef> getCatalog() {
        return IsoSpec.getAllFieldDefs();
    }

    @Override
    public Map<String, IsoSpecDefinition> getSpecs() {
        return isoSpecRegistry.getAllSpecs();
    }

    @Override
    public UnpackResult unpackMessage(UnpackRequest request) {
        IsoSpecDefinition spec = isoSpecRegistry.getSpec(request.specId());

        IsoMessage message = IsoUnpacker.unpack(request.payload(), request.hasHeader(), spec);

        return buildUnpackResult(message, spec);
    }

    @Override
    public PackResult packMessage(PackRequest request) {
        IsoSpecDefinition spec = isoSpecRegistry.getSpec(request.specId());

        IsoMessage message = buildIsoMessage(request);

        String rawPayload = IsoPacker.packToString(message, spec);

        return new PackResult(
                rawPayload,
                message.getPrimaryBitmapHex(),
                message.getSecondaryBitmapHex(),
                rawPayload.length()
        );
    }

    @Override
    public SimulateResult simulateTransaction(SimulateRequest request) {
        return isoTcpClient.simulate(request.rawPayload());
    }

    @Override
    public EmvParseResponse parseEmv(EmvParseRequest request) {
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

        return new EmvParseResponse(
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
    public PinEncodeResponse encodePin(PinEncodeRequest request) {
        byte[] key = resolveKey(request.keyHex(), request.keyId(), "DEFAULT_ZPK_ACQ");
        String clearBlock = IsoPinBlockEngine.encodeClearPinBlock(request.pin(), request.pan(), request.format());
        String encryptedBlock = IsoPinBlockEngine.encryptPin(request.pin(), request.pan(), request.format(), key);

        String keyName = request.keyId() != null ? request.keyId() : (request.keyHex() != null ? "CUSTOM_KEY" : "DEFAULT_ZPK_ACQ");
        return new PinEncodeResponse(clearBlock, encryptedBlock, request.format(), keyName);
    }

    @Override
    public PinTranslateResponse translatePin(PinTranslateRequest request) {
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

        return new PinTranslateResponse(translated, request.srcFormat(), request.dstFormat(), true);
    }

    @Override
    public MacGenerateResponse generateMac(MacGenerateRequest request) {
        byte[] key = resolveKey(request.keyHex(), request.keyId(), "DEFAULT_MAK");

        byte[] payloadBytes = request.rawPayload().getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        String mac = MacEngine.calculateRetailMac(payloadBytes, key);

        String keyName = request.keyId() != null ? request.keyId() : (request.keyHex() != null ? "CUSTOM_KEY" : "DEFAULT_MAK");
        return new MacGenerateResponse(mac, "ISO 9797-1 Algorithm 3 (Retail MAC)", keyName);
    }

    @Override
    public MacVerifyResponse verifyMac(MacVerifyRequest request) {
        byte[] key = resolveKey(request.keyHex(), request.keyId(), "DEFAULT_MAK");

        byte[] payloadBytes = request.rawPayload().getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        String calculatedMac = MacEngine.calculateRetailMac(payloadBytes, key);

        boolean valid = calculatedMac.equalsIgnoreCase(request.expectedMac().trim());
        return new MacVerifyResponse(valid, calculatedMac, valid ? "MAC valid" : "MAC mismatch");
    }

    @Override
    public ClearingBatch generateClearingBatch(ClearingBatchRequest request) {
        String networkId = (request != null && request.networkId() != null) ? request.networkId() : "MASTERCARD-IPM";
        return batchClearingEngine.generateClearingBatch(networkId);
    }

    @Override
    public ClearingRecord fileChargeback(ChargebackRequest request) {
        return batchClearingEngine.fileChargeback(
                request.stan(),
                request.maskedPan(),
                request.amountIso(),
                request.disputeReasonCode()
        );
    }

    @Override
    public ClearingBatch parseClearingBatch(ClearingParseRequest request) {
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
    public ResiliencyStatusResponse getResiliencyStatus() {
        String persistence = "PostgreSQL (Schema V1.0) / In-Memory Active Fallback";
        String lockEngine  = "Redis 7 / In-Memory Reentrant Cluster Lock";
        String outbox      = "Active (Transactional Outbox Pattern & Kafka Streamer)";

        return new ResiliencyStatusResponse(
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

    private byte[] resolveKey(String keyHex, String keyId, String defaultKeyId) {
        if (keyHex != null && !keyHex.isBlank()) {
            return CryptoUtils.hexToBytes(keyHex);
        }
        String id = (keyId != null && !keyId.isBlank()) ? keyId : defaultKeyId;
        return cryptoKeyRegistry.getKey(id)
                .orElseThrow(() -> new IllegalArgumentException("Cryptographic key not found in registry: " + id));
    }

    private UnpackResult buildUnpackResult(IsoMessage message, IsoSpecDefinition spec) {

        List<FieldDetail> details = message.getFields()
                .entrySet()
                .stream()
                .map(entry -> toFieldDetail(entry, spec))
                .toList();

        List<Integer> activeFields = message.getFields()
                .keySet()
                .stream()
                .toList();

        return new UnpackResult(
                message.getHeader(),
                message.getMti(),
                IsoMtiDescriptions.describe(message.getMti()),
                message.getPrimaryBitmapHex(),
                message.getSecondaryBitmapHex(),
                activeFields,
                details
        );
    }

    private FieldDetail toFieldDetail(Map.Entry<Integer, String> entry, IsoSpecDefinition spec) {
        int fieldId = entry.getKey();

        IsoFieldDef definition = (spec != null && spec.getFieldDef(fieldId) != null)
                ? spec.getFieldDef(fieldId)
                : IsoSpec.getFieldDef(fieldId);

        return new FieldDetail(
                fieldId,
                definition != null ? definition.name() : "Custom Field",
                definition != null ? definition.type().name() : IsoFieldType.LLVAR_ALPHA.name(),
                entry.getValue()
        );
    }

    private IsoMessage buildIsoMessage(PackRequest request) {
        IsoMessage message = new IsoMessage(request.mti());
        message.setHeader(request.header());

        request.fields().forEach((key, value) ->
                message.setField(Integer.parseInt(key.toString()), value)
        );

        return message;
    }
}
