package com.dean.iso8583.server;

import com.dean.iso8583.core.dto.IsoMessage;
import com.dean.iso8583.core.IsoPacker;
import com.dean.iso8583.core.IsoUnpacker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;


@Slf4j
@Component
public class IsoTcpServer implements SmartLifecycle {

    private final IsoTcpProperties properties;
    private final ExecutorService clientExecutor;
    private final IsoMessageProcessor messageProcessor;

    private volatile ServerSocket serverSocket;
    private volatile boolean running;

    public IsoTcpServer(IsoTcpProperties properties, IsoMessageProcessor messageProcessor) {
        this.properties = properties;
        this.messageProcessor = messageProcessor;
        this.clientExecutor = Executors.newVirtualThreadPerTaskExecutor();
    }

    @Override
    public void start() {
        if (running) return;

        try {
            serverSocket = new ServerSocket(properties.port());
            running = true;

            log.info("ISO 8583 TCP Host Simulator started on port {}", properties.port());

            Thread.startVirtualThread(this::acceptConnections);

        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Failed to start ISO 8583 TCP server on port: %d".formatted(properties.port()),
                    exception
            );
        }
    }

    private void acceptConnections() {
        while (running) {
            try {
                Socket socket = serverSocket.accept();

                log.info("Incoming ISO 8583 connection from {}", socket.getRemoteSocketAddress());

                clientExecutor.submit(() -> handleClient(socket));

            } catch (IOException exception) {
                if (running) {
                    log.error("Error accepting TCP connection", exception);
                }
            }
        }
    }

    private void handleClient(Socket socket) {
        try (
                socket;
                DataInputStream input = new DataInputStream(socket.getInputStream());
                DataOutputStream output = new DataOutputStream(socket.getOutputStream())
        ) {

            while (running && !socket.isClosed()) {
                byte[] payload = readFrame(input);

                if (payload == null) return;

                processMessage(payload, output);
            }

        } catch (IOException exception) {
            logClientDisconnect(socket, exception);
        } catch (RuntimeException exception) {
            log.error("Unexpected error processing client {}", socket.getRemoteSocketAddress(), exception);
        }
    }

    private byte[] readFrame(DataInputStream input) throws IOException {
        int length;

        try {
            length = input.readUnsignedShort();
        } catch (EOFException exception) {
            return null;
        }

        validateFrameLength(length);

        byte[] payload = new byte[length];

        input.readFully(payload);

        return payload;
    }

    private void processMessage(byte[] payload, DataOutputStream output) throws IOException {

        String rawMessage = decodePayload(payload);
        boolean hasHeader = hasTPDUHeader(rawMessage);

        log.info("Received ISO 8583 message ({} bytes): {}", payload.length, 
                com.dean.iso8583.core.utils.IsoMessageSanitizer.sanitizePayloadForLogging(rawMessage, hasHeader));

        IsoMessage request = IsoUnpacker.unpack(rawMessage, hasHeader);
        IsoMessage response = messageProcessor.process(request);

        sendResponse(response, output);
    }

    private String decodePayload(byte[] payload) {
        return new String(payload, StandardCharsets.US_ASCII);
    }

    private IsoMessage unpackMessage(String rawMessage) {
        boolean hasHeader = hasTPDUHeader(rawMessage);

        return IsoUnpacker.unpack(rawMessage, hasHeader);
    }

    private void sendResponse(IsoMessage response, DataOutputStream output) throws IOException {

        String rawResponse = IsoPacker.packToString(response);

        byte[] responseBytes = rawResponse.getBytes(StandardCharsets.US_ASCII);

        validateFrameLength(responseBytes.length);

        output.writeShort(responseBytes.length);
        output.write(responseBytes);
        output.flush();

        log.info("Sent ISO 8583 response: {}", 
                com.dean.iso8583.core.utils.IsoMessageSanitizer.sanitizeMessage(response));
    }

    private boolean hasTPDUHeader(String payload) {
        if (payload.length() < 14) return false;

        String possibleMtiWithoutHeader = payload.substring(0, 4);

        if (isValidMti(possibleMtiWithoutHeader)) return false;

        String possibleMtiWithHeader = payload.substring(10, 14);

        return isValidMti(possibleMtiWithHeader);
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
            throw new IllegalArgumentException("ISO 8583 frame length must be greater than zero");
        }

        if (length > properties.maxFrameLength()) {
            throw new IllegalArgumentException("ISO 8583 frame exceeds maximum allowed size: %d".formatted(length));
        }
    }

    private void logClientDisconnect(Socket socket, IOException exception) {
        log.debug("ISO 8583 client disconnected: {} ({})", socket.getRemoteSocketAddress(), exception.getMessage());
    }

    @Override
    public void stop() {
        running = false;

        closeServerSocket();
        shutdownExecutor();

        log.info("ISO 8583 TCP Host Simulator stopped");
    }

    private void closeServerSocket() {
        ServerSocket socket = serverSocket;

        if (socket == null || socket.isClosed()) return;

        try {
            socket.close();
        } catch (IOException exception) {
            log.warn("Failed to close ISO 8583 server socket", exception);
        }
    }

    private void shutdownExecutor() {
        clientExecutor.shutdown();

        try {
            if (!clientExecutor.awaitTermination(properties.shutdownTimeout(), TimeUnit.SECONDS)) {
                clientExecutor.shutdownNow();
            }
        } catch (InterruptedException exception) {
            clientExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE;
    }
}


//@Slf4j
//@Component
//public class IsoTcpServer {
//
//    private static final int PORT = 8583;
//    private ServerSocket serverSocket;
//    private final ExecutorService executor = Executors.newCachedThreadPool();
//    private volatile boolean running = false;
//
//    @PostConstruct
//    public void startServer() {
//        executor.submit(() -> {
//            try {
//                serverSocket = new ServerSocket(PORT);
//                running = true;
//                log.info("ISO 8583 TCP Host Simulator started on port {}", PORT);
//
//                while (running) {
//                    try {
//                        Socket clientSocket = serverSocket.accept();
//                        executor.submit(() -> handleClient(clientSocket));
//                    } catch (IOException e) {
//                        if (!running) break;
//                        log.error("Error accepting TCP connection", e);
//                    }
//                }
//            } catch (IOException e) {
//                log.error("Failed to start ISO 8583 TCP Server on port {}", PORT, e);
//            }
//        });
//    }
//
//    private void handleClient(Socket socket) {
//        log.info("Incoming ISO 8583 TCP connection from {}", socket.getRemoteSocketAddress());
//        try (DataInputStream in = new DataInputStream(socket.getInputStream());
//             DataOutputStream out = new DataOutputStream(socket.getOutputStream())) {
//
//            while (running && !socket.isClosed()) {
//                // 2-byte length header framing
//                int length = in.readUnsignedShort();
//                if (length <= 0) break;
//
//                byte[] payloadBytes = new byte[length];
//                in.readFully(payloadBytes);
//                String rawPayload = new String(payloadBytes, StandardCharsets.UTF_8);
//
//                log.info("Received ISO 8583 message ({} bytes): {}", length, rawPayload);
//
//                // Auto-detect TPDU header (10 characters before 4-digit MTI)
//                boolean hasHeader = false;
//                if (rawPayload.length() >= 14 && !rawPayload.substring(0, 4).matches("^(01|02|04|08)\\d\\d$")) {
//                    if (rawPayload.substring(10, 14).matches("^(01|02|04|08)\\d\\d$")) {
//                        hasHeader = true;
//                    }
//                }
//
//                // Process ISO message
//                IsoMessage request = IsoUnpacker.unpack(rawPayload, hasHeader);
//                IsoMessage response = generateResponse(request);
//
//                String rawResponse = IsoPacker.packToString(response);
//                byte[] responseBytes = rawResponse.getBytes(StandardCharsets.UTF_8);
//
//                // Write 2-byte header length + payload
//                out.writeShort(responseBytes.length);
//                out.write(responseBytes);
//                out.flush();
//                log.info("Sent ISO 8583 response: {}", rawResponse);
//            }
//        } catch (IOException e) {
//            log.debug("Client socket closed: {}", e.getMessage());
//        }
//    }
//
//    private IsoMessage generateResponse(IsoMessage req) {
//        String reqMti = req.getMti();
//        String respMti = "0210";
//        if ("0800".equals(reqMti)) {
//            respMti = "0810";
//        } else if ("0100".equals(reqMti)) {
//            respMti = "0110";
//        } else if ("0400".equals(reqMti)) {
//            respMti = "0410";
//        }
//
//        IsoMessage resp = new IsoMessage(respMti);
//        resp.setHeader(req.getHeader());
//
//        // Copy key tracing fields
//        if (req.hasField(2)) resp.setField(2, req.getField(2));
//        if (req.hasField(3)) resp.setField(3, req.getField(3));
//        if (req.hasField(4)) resp.setField(4, req.getField(4));
//        if (req.hasField(7)) resp.setField(7, req.getField(7));
//        if (req.hasField(11)) resp.setField(11, req.getField(11));
//        if (req.hasField(41)) resp.setField(41, req.getField(41));
//        if (req.hasField(42)) resp.setField(42, req.getField(42));
//        if (req.hasField(70)) resp.setField(70, req.getField(70));
//
//        // Generate response fields
//        resp.setField(39, "00"); // Response Code: 00 = Approved / Success
//        if (req.hasField(11)) {
//            resp.setField(38, "AUTH" + req.getField(11)); // Auth code
//        } else {
//            resp.setField(38, "AUTH01");
//        }
//        resp.setField(37, "123456789012"); // RRN
//
//        return resp;
//    }
//
//    @PreDestroy
//    public void stopServer() {
//        running = false;
//        try {
//            if (serverSocket != null && !serverSocket.isClosed()) {
//                serverSocket.close();
//            }
//        } catch (IOException ignored) {}
//        executor.shutdown();
//        log.info("ISO 8583 TCP Host Simulator stopped");
//    }
//}
