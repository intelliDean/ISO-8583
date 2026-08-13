package com.dean.iso8583.server;

import com.dean.iso8583.core.dto.IsoMessage;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class IsoMessageProcessor {

    private static final Map<String, String> RESPONSE_MTIS = Map.of(
            "0800", "0810",
            "0100", "0110",
            "0400", "0410"
    );

    private static final List<Integer> TRACE_FIELDS = List.of(2, 3, 4, 7, 11, 41, 42, 70);

    public IsoMessage process(IsoMessage request) {
        IsoMessage response = createResponse(request);

        copyTraceFields(request, response);
        populateResponseFields(request, response);

        return response;
    }

    private IsoMessage createResponse(IsoMessage request) {
        String responseMti = RESPONSE_MTIS.getOrDefault(request.getMti(), "0210");

        IsoMessage response = new IsoMessage(responseMti);
        response.setHeader(request.getHeader());

        return response;
    }

    private void copyTraceFields(IsoMessage request, IsoMessage response) {
        for (int fieldId : TRACE_FIELDS) {
            copyField(request, response, fieldId);
        }
    }

    private void copyField(IsoMessage request, IsoMessage response, int fieldId) {
        if (request.hasField(fieldId)) {
            response.setField(
                    fieldId,
                    request.getField(fieldId)
            );
        }
    }

    private void populateResponseFields(IsoMessage request, IsoMessage response) {
        response.setField(39, "00");
        response.setField(37, generateRrn(request));
        response.setField(38, generateAuthorizationCode(request));
    }

    private String generateAuthorizationCode(IsoMessage request) {
        if (!request.hasField(11)) {
            return "AUTH01";
        }

        String stan = request.getField(11);
        String code = "A" + stan;
        return code.length() > 6 ? code.substring(0, 6) : String.format("%-6s", code);
    }

    private String generateRrn(IsoMessage request) {
        return "123456789012";
    }
}