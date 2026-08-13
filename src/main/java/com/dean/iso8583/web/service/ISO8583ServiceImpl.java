package com.dean.iso8583.web.service;

import com.dean.iso8583.core.IsoPacker;
import com.dean.iso8583.core.IsoSpec;
import com.dean.iso8583.core.IsoUnpacker;
import com.dean.iso8583.core.dto.IsoFieldDef;
import com.dean.iso8583.core.dto.IsoFieldType;
import com.dean.iso8583.core.dto.IsoMessage;
import com.dean.iso8583.web.data.dto.*;
import com.dean.iso8583.web.data.utils.IsoMtiDescriptions;
import com.dean.iso8583.web.data.utils.IsoTcpClient;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;


@Service
@RequiredArgsConstructor
public class ISO8583ServiceImpl implements ISO8583Service {

    private final IsoTcpClient isoTcpClient;

    @Override
    public Map<Integer, IsoFieldDef> getCatalog() {
        return IsoSpec.getAllFieldDefs();
    }

    @Override
    public UnpackResult unpackMessage(UnpackRequest request) {
        IsoMessage message = IsoUnpacker.unpack(
                request.payload(),
                request.hasHeader()
        );

        return buildUnpackResult(message);
    }

    @Override
    public PackResult packMessage(PackRequest request) {
        IsoMessage message = buildIsoMessage(request);

        String rawPayload = IsoPacker.packToString(message);

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

    private UnpackResult buildUnpackResult(IsoMessage message) {

        List<FieldDetail> details = message.getFields()
                .entrySet()
                .stream()
                .map(this::toFieldDetail)
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

    private FieldDetail toFieldDetail(
            Map.Entry<Integer, String> entry
    ) {
        int fieldId = entry.getKey();

        IsoFieldDef definition = IsoSpec.getFieldDef(fieldId);

        return new FieldDetail(
                fieldId,
                definition != null
                        ? definition.name()
                        : "Custom Field",
                definition != null
                        ? definition.type().name()
                        : IsoFieldType.LLVAR_ALPHA.name(),
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


//@Service
//@RequiredArgsConstructor
//public class ISO8583ServiceImpl implements ISO8583Service {
//
//    @Override
//    public Map<Integer, IsoFieldDef> getCatalog() {
//        return IsoSpec.getAllFieldDefs();
//    }
//
//    @Override
//    public UnpackResult unpackMessage(UnpackRequest request) {
//        IsoMessage msg = IsoUnpacker.unpack(request.payload(), request.hasHeader());
//
//        List<FieldDetail> details = new ArrayList<>();
//        List<Integer> activeFields = new ArrayList<>();
//
//        for (Map.Entry<Integer, String> entry : msg.getFields().entrySet()) {
//            int fieldId = entry.getKey();
//            activeFields.add(fieldId);
//            IsoFieldDef def = IsoSpec.getFieldDef(fieldId);
//            String name = (def != null) ? def.name() : "Custom Field";
//            IsoFieldType type = (def != null) ? def.type() : IsoFieldType.LLVAR_ALPHA;
//
//            details.add(new FieldDetail(fieldId, name, type.name(), entry.getValue()));
//        }
//
//        return new UnpackResult(
//                msg.getHeader(),
//                msg.getMti(),
//                getMtiDescription(msg.getMti()),
//                msg.getPrimaryBitmapHex(),
//                msg.getSecondaryBitmapHex(),
//                activeFields,
//                details
//        );
//    }
//
//    @Override
//    public PackResult packMessage(PackRequest request) {
//        IsoMessage msg = new IsoMessage(request.mti());
//        msg.setHeader(request.header());
//
//        if (request.fields() != null) {
//            request.fields().forEach((key, val) -> {
//                int fieldId = Integer.parseInt(key.toString());
//                msg.setField(fieldId, val);
//            });
//        }
//
//        String rawPacked = IsoPacker.packToString(msg);
//        return new PackResult(
//                rawPacked,
//                msg.getPrimaryBitmapHex(),
//                msg.getSecondaryBitmapHex(),
//                rawPacked.length()
//        );
//    }
//
//    @Override
//    public SimulateResult simulateTransaction(SimulateRequest request) {
//        long startTime = System.currentTimeMillis();
//        String rawRequest = request.rawPayload();
//
//        try (Socket socket = new Socket("localhost", 8583);
//             DataOutputStream out = new DataOutputStream(socket.getOutputStream());
//             DataInputStream in = new DataInputStream(socket.getInputStream())) {
//
//            byte[] reqBytes = rawRequest.getBytes(StandardCharsets.UTF_8);
//            out.writeShort(reqBytes.length);
//            out.write(reqBytes);
//            out.flush();
//
//            int respLength = in.readUnsignedShort();
//            byte[] respBytes = new byte[respLength];
//            in.readFully(respBytes);
//            String rawResponse = new String(respBytes, StandardCharsets.UTF_8);
//
//            long elapsed = System.currentTimeMillis() - startTime;
//
//            boolean respHasHeader = false;
//            if (rawResponse.length() >= 14 && !rawResponse.substring(0, 4).matches("^(01|02|04|08)\\d\\d$")) {
//                if (rawResponse.substring(10, 14).matches("^(01|02|04|08)\\d\\d$")) {
//                    respHasHeader = true;
//                }
//            }
//
//            IsoMessage respMsg = IsoUnpacker.unpack(rawResponse, respHasHeader);
//
//            String respCode = respMsg.getField(39);
//            String respDesc = getResponseCodeDescription(respCode);
//
//            return SimulateResult.builder()
//                    .requestPayload(rawRequest)
//                    .responsePayload(rawResponse)
//                    .responseMti(respMsg.getMti())
//                    .responseCode(respCode)
//                    .responseCodeDescription(respDesc)
//                    .roundtripMs(elapsed)
//                    .success(true)
//                    .message("Success")
//                    .build();
//
//        } catch (Exception e) {
//
//            long elapsed = System.currentTimeMillis() - startTime;
//            return SimulateResult.builder()
//                    .requestPayload(rawRequest)
//                    .responsePayload(null)
//                    .responseMti(null)
//                    .responseCode("ERR")
//                    .responseCodeDescription(e.getMessage())
//                    .roundtripMs(elapsed)
//                    .success(false)
//                    .message(e.getMessage())
//                    .build();
//        }
//    }
//
//    private String getMtiDescription(String mti) {
//        if (mti == null || mti.length() != 4) return "Unknown MTI";
//        char versionChar = mti.charAt(0);
//        char classChar = mti.charAt(1);
//        char funcChar = mti.charAt(2);
//        char origChar = mti.charAt(3);
//
//        String ver = switch (versionChar) {
//            case '0' -> "ISO 8583:1987";
//            case '1' -> "ISO 8583:1993";
//            case '2' -> "ISO 8583:2003";
//            default -> "Custom Version";
//        };
//
//        String cls = switch (classChar) {
//            case '1' -> "Authorization";
//            case '2' -> "Financial";
//            case '3' -> "File Action";
//            case '4' -> "Reversal / Chargeback";
//            case '5' -> "Reconciliation";
//            case '8' -> "Network Management";
//            default -> "Other Message";
//        };
//
//        String func = switch (funcChar) {
//            case '0' -> "Request";
//            case '1' -> "Response";
//            case '2' -> "Advice";
//            case '3' -> "Advice Response";
//            default -> "Notification";
//        };
//
//        return "%s %s %s".formatted(ver, cls, func);
//    }
//
//    private String getResponseCodeDescription(String code) {
//        if (code == null) return "No Response Code";
//        return switch (code) {
//            case "00" -> "Approved / Successful";
//            case "01" -> "Refer to Card Issuer";
//            case "04" -> "Pick-Up Card (Hot Card)";
//            case "05" -> "Do Not Honor";
//            case "14" -> "Invalid Card Number (PAN)";
//            case "51" -> "Insufficient Funds";
//            case "54" -> "Expired Card";
//            case "55" -> "Incorrect PIN";
//            case "91" -> "Issuer Timeout / Down";
//            case "96" -> "System Error";
//            default -> "Response Code " + code;
//        };
//    }
//}
