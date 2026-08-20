package com.dean.iso8583.web.data.utils;

import com.dean.iso8583.core.IsoPacker;
import com.dean.iso8583.core.IsoUnpacker;
import com.dean.iso8583.core.dto.IsoMessage;
import com.dean.iso8583.server.IsoTcpProperties;
import com.dean.iso8583.web.data.dto.WebDTOs;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;

@Component
@RequiredArgsConstructor
public class IsoTcpClient {

    private final IsoTcpProperties properties;

    public WebDTOs.SimulateResult simulate(String rawRequest) {
        Instant start = Instant.now();

        try {
            IsoMessage response = sendRequest(rawRequest);

            Duration elapsed = Duration.between(start, Instant.now());

            return buildSuccessResult(rawRequest, response, elapsed);

        } catch (Exception exception) {

            Duration elapsed = Duration.between(start, Instant.now());

            return buildFailureResult(rawRequest, exception, elapsed);
        }
    }

    private IsoMessage sendRequest(String rawRequest) throws IOException {

        try (
                Socket socket = new Socket(properties.host(), properties.port());

                DataOutputStream output = new DataOutputStream(socket.getOutputStream());

                DataInputStream input = new DataInputStream(socket.getInputStream())
        ) {
            writeMessage(output, rawRequest);

            String rawResponse = readMessage(input);

            return unpackResponse(rawResponse);
        }
    }

    private void writeMessage(DataOutputStream output, String payload) throws IOException {

        byte[] bytes = payload.getBytes(StandardCharsets.US_ASCII);

        validateFrameLength(bytes.length);

        output.writeShort(bytes.length);
        output.write(bytes);
        output.flush();
    }

    private String readMessage(DataInputStream input) throws IOException {

        int length = input.readUnsignedShort();

        validateFrameLength(length);

        byte[] bytes = new byte[length];

        input.readFully(bytes);

        return new String(bytes, StandardCharsets.US_ASCII);
    }

    private IsoMessage unpackResponse(String rawResponse) {
        boolean hasHeader = hasTPDUHeader(rawResponse);

        return IsoUnpacker.unpack(rawResponse, hasHeader);
    }

    private boolean hasTPDUHeader(String payload) {
        if (payload.length() < 14) return false;

        if (isValidMti(payload.substring(0, 4))) return false;

        return isValidMti(payload.substring(10, 14));
    }

    private boolean isValidMti(String mti) {
        if (mti.length() != 4) return false;

        return switch (mti.charAt(0)) {
            case '0', '1', '2', '4', '8' -> true;
            default -> false;
        };
    }

    private void validateFrameLength(int length) {
        if (length <= 0) {
            throw new IllegalArgumentException("Invalid ISO 8583 frame length: " + length);
        }

        if (length > properties.maxFrameLength()) {
            throw new IllegalArgumentException(
                    "ISO 8583 frame exceeds maximum size: %d".formatted(properties.maxFrameLength())
            );
        }
    }

    private WebDTOs.SimulateResult buildSuccessResult(String request, IsoMessage response, Duration elapsed) {
        String responseCode = response.getField(39);

        return WebDTOs.SimulateResult.builder()
                .requestPayload(request)
                .responsePayload(IsoPacker.packToString(response))
                .responseMti(response.getMti())
                .responseCode(responseCode)
                .responseCodeDescription(IsoResponseCodes.descriptionOf(responseCode))
                .roundtripMs(elapsed.toMillis())
                .success(true)
                .message("Success")
                .build();
    }

    private WebDTOs.SimulateResult buildFailureResult(String request, Exception exception, Duration elapsed) {
        return WebDTOs.SimulateResult.builder()
                .requestPayload(request)
                .responsePayload(null)
                .responseMti(null)
                .responseCode("ERR")
                .responseCodeDescription(exception.getMessage())
                .roundtripMs(elapsed.toMillis())
                .success(false)
                .message(exception.getMessage())
                .build();
    }
}