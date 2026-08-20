package com.dean.iso8583.client;

import com.dean.iso8583.core.IsoPacker;
import com.dean.iso8583.core.IsoUnpacker;
import com.dean.iso8583.core.dto.IsoMessage;
import lombok.extern.slf4j.Slf4j;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * Lightweight client for communicating directly with the ISO 8583 TCP Host over raw binary sockets.
 */
@Slf4j
public class IsoTerminalClient {

    private final String host;
    private final int port;
    private final String tpduHeader;

    public IsoTerminalClient(String host, int port, String tpduHeader) {
        this.host = host;
        this.port = port;
        this.tpduHeader = tpduHeader != null ? tpduHeader : "6000000000";
    }

    public IsoTerminalClient(String host, int port) {
        this(host, port, "6000000000");
    }

    public IsoMessage sendEcho(String stan) throws IOException {
        IsoMessage req = new IsoMessage("0800");
        req.setHeader(tpduHeader);
        req.setField(11, stan);
        req.setField(70, "301");
        return sendAndReceive(req);
    }

    public IsoMessage sendPurchase(String pan, String amount, String stan, String terminalId, String merchantId) throws IOException {
        IsoMessage req = new IsoMessage("0200");
        req.setHeader(tpduHeader);
        req.setField(2, pan);
        req.setField(3, "000000");
        req.setField(4, amount);
        req.setField(11, stan);
        req.setField(41, terminalId);
        req.setField(42, merchantId);
        req.setField(49, "840");
        return sendAndReceive(req);
    }

    public IsoMessage sendReversal(String pan, String amount, String stan, String terminalId, String merchantId) throws IOException {
        IsoMessage req = new IsoMessage("0400");
        req.setHeader(tpduHeader);
        req.setField(2, pan);
        req.setField(3, "000000");
        req.setField(4, amount);
        req.setField(11, stan);
        req.setField(41, terminalId);
        req.setField(42, merchantId);
        req.setField(49, "840");
        return sendAndReceive(req);
    }

    public IsoMessage sendAndReceive(IsoMessage message) throws IOException {
        String packed = IsoPacker.packToString(message);
        byte[] payloadBytes = packed.getBytes(StandardCharsets.US_ASCII);

        try (Socket socket = new Socket(host, port);
             DataOutputStream out = new DataOutputStream(socket.getOutputStream());
             DataInputStream in = new DataInputStream(socket.getInputStream())) {

            socket.setSoTimeout(5000);

            // Write 2-byte big endian length prefix
            out.writeShort(payloadBytes.length);
            out.write(payloadBytes);
            out.flush();

            // Read 2-byte big endian length prefix response
            int respLen = in.readUnsignedShort();
            byte[] respBytes = new byte[respLen];
            in.readFully(respBytes);

            String respString = new String(respBytes, StandardCharsets.US_ASCII);
            return IsoUnpacker.unpack(respString, message.getHeader() != null);
        }
    }
}
