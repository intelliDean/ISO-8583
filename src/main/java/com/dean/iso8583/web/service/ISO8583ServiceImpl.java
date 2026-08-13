package com.dean.iso8583.web.service;

import com.dean.iso8583.core.IsoPacker;
import com.dean.iso8583.core.IsoSpec;
import com.dean.iso8583.core.IsoUnpacker;
import com.dean.iso8583.core.dto.IsoFieldDef;
import com.dean.iso8583.core.dto.IsoFieldType;
import com.dean.iso8583.core.dto.IsoMessage;
import com.dean.iso8583.core.dto.IsoSpecDefinition;
import com.dean.iso8583.core.emv.EmvParseResult;
import com.dean.iso8583.core.emv.EmvTlvParser;
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
 * Enterprise ISO 8583 Service Implementation.
 * Orchestrates message packing, unpacking, dynamic spec resolution, host simulation, and transaction state queries.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ISO8583ServiceImpl implements ISO8583Service {

    private final IsoTcpClient isoTcpClient;
    private final IsoSpecRegistry isoSpecRegistry;
    private final TransactionStore transactionStore;

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

        IsoMessage message = IsoUnpacker.unpack(
                request.payload(),
                request.hasHeader(),
                spec
        );

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

    /**
     * Developer Note:
     * Parses DE 55 (ICC System Related Data) using the BER-TLV parser.
     *
     * Key fraud-detection signals surfaced in the response:
     *  - {@code hasArqc}   — ARQC (9F26) presence; absent in magnetic-stripe fallback
     *  - {@code hasAtc}    — ATC (9F36) presence; must be validated for replay attacks
     *  - {@code atcDecimal} — integer ATC value for comparison with issuer stored value
     *
     * In production, this method should be extended to:
     *  1. Forward {@code arqcValue} + session data to an HSM for ARQC verification.
     *  2. Compare {@code atcDecimal} against the issuer's card-level ATC store.
     *  3. Log the IAD (9F10) for offline CVR auditing.
     */
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
