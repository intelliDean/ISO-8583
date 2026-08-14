package com.dean.iso8583.client;

import com.dean.iso8583.core.IsoPacker;
import com.dean.iso8583.core.IsoUnpacker;
import com.dean.iso8583.core.dto.IsoMessage;
import com.dean.iso8583.core.echo.NetworkManagementCode;
import lombok.extern.slf4j.Slf4j;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * Developer Note:
 * Standalone Java Client for connecting to the ISO 8583 TCP Server (port 8583).
 *
 * Demonstrates:
 *  - 2-byte Big-Endian framing
 *  - Packing 0800 Keep-Alive Echo requests
 *  - Packing 0200 Purchase authorization requests
 *  - Packing 0400 Transaction reversals
 *  - Unpacking 0810, 0210, 0410 responses
 */
@Slf4j
public class IsoTerminalClient {

    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("MMddHHmmss")
            .withZone(ZoneOffset.UTC);

    private final String host;
    private final int port;
    private final String tpduHeader;

    public IsoTerminalClient() {
        this("localhost", 8583, "6000000000");
    }

    public IsoTerminalClient(String host, int port, String tpduHeader) {
        this.host = host;
        this.port = port;
        this.tpduHeader = tpduHeader;
    }

    /**
     * Sends a raw ISO payload string over TCP with 2-byte Big-Endian length header
     * and returns the unpacked response IsoMessage.
     */
    public IsoMessage send(String rawPayload) throws IOException {
        try (Socket socket = new Socket(host, port);
             DataOutputStream out = new DataOutputStream(socket.getOutputStream());
             DataInputStream in = new DataInputStream(socket.getInputStream())) {

            byte[] requestBytes = rawPayload.getBytes(StandardCharsets.US_ASCII);

            // Write 2-byte Big-Endian length prefix
            out.writeShort(requestBytes.length);
            out.write(requestBytes);
            out.flush();

            // Read 2-byte response length
            int respLength = in.readUnsignedShort();
            byte[] respBytes = new byte[respLength];
            in.readFully(respBytes);

            String rawResponse = new String(respBytes, StandardCharsets.US_ASCII);
            boolean hasHeader = rawResponse.startsWith(tpduHeader);

            return IsoUnpacker.unpack(rawResponse, hasHeader);
        }
    }

    /**
     * Sends a structured 0800 Keep-Alive Echo request.
     */
    public IsoMessage sendEcho(String stan) throws IOException {
        IsoMessage echo = new IsoMessage("0800");
        echo.setHeader(tpduHeader);
        echo.setField(7, DATE_TIME_FORMATTER.format(Instant.now()));
        echo.setField(11, stan);
        echo.setField(70, NetworkManagementCode.ECHO_TEST.getCode());

        String packed = IsoPacker.packToString(echo);
        return send(packed);
    }

    /**
     * Sends a 0200 Financial Purchase authorization request.
     */
    public IsoMessage sendPurchase(String pan, String amountIso, String stan, String terminalId, String merchantId) throws IOException {
        IsoMessage purchase = new IsoMessage("0200");
        purchase.setHeader(tpduHeader);
        purchase.setField(2, pan);
        purchase.setField(3, "000000");
        purchase.setField(4, amountIso);
        purchase.setField(7, DATE_TIME_FORMATTER.format(Instant.now()));
        purchase.setField(11, stan);
        purchase.setField(41, terminalId);
        purchase.setField(42, merchantId);
        purchase.setField(49, "840");

        String packed = IsoPacker.packToString(purchase);
        return send(packed);
    }

    /**
     * Sends a 0400 Transaction Reversal request for a prior 0200 purchase.
     */
    public IsoMessage sendReversal(String pan, String amountIso, String stan, String terminalId, String merchantId) throws IOException {
        IsoMessage reversal = new IsoMessage("0400");
        reversal.setHeader(tpduHeader);
        reversal.setField(2, pan);
        reversal.setField(3, "000000");
        reversal.setField(4, amountIso);
        reversal.setField(7, DATE_TIME_FORMATTER.format(Instant.now()));
        reversal.setField(11, stan);
        reversal.setField(41, terminalId);
        reversal.setField(42, merchantId);
        reversal.setField(49, "840");

        String packed = IsoPacker.packToString(reversal);
        return send(packed);
    }
}
