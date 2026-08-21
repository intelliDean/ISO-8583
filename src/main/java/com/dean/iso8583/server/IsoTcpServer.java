package com.dean.iso8583.server;

import com.dean.iso8583.core.IsoPacker;
import com.dean.iso8583.core.IsoUnpacker;
import com.dean.iso8583.core.dto.IsoMessage;
import com.dean.iso8583.core.metrics.IsoMetrics;
import com.dean.iso8583.core.utils.IsoMessageSanitizer;
import com.dean.iso8583.server.tls.IsoTlsContextFactory;
import com.dean.iso8583.server.tls.IsoTlsProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
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
import java.util.concurrent.atomic.AtomicBoolean;


@Slf4j
@Component
public class IsoTcpServer implements SmartLifecycle {

    private final IsoTcpProperties properties;
    private final IsoTlsProperties tlsProperties;
    private final IsoTlsContextFactory tlsContextFactory;
    private final IsoMetrics metrics;
    private final ExecutorService clientExecutor;
    private final IsoMessageProcessor messageProcessor;

    private volatile ServerSocket serverSocket;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public IsoTcpServer(
            IsoTcpProperties properties,
            IsoMessageProcessor messageProcessor,
            @Autowired(required = false) IsoTlsProperties tlsProperties,
            @Autowired(required = false) IsoTlsContextFactory tlsContextFactory,
            @Autowired(required = false) IsoMetrics metrics
    ) {
        this.properties = properties;
        this.messageProcessor = messageProcessor;
        this.tlsProperties = tlsProperties;
        this.tlsContextFactory = tlsContextFactory;
        this.metrics = metrics;
        this.clientExecutor = Executors.newVirtualThreadPerTaskExecutor();
    }

    @Override
    public void start() {
        if (running.get()) return;

        try {
            if (tlsProperties != null && tlsProperties.isEnabled() && tlsContextFactory != null) {
                SSLContext sslContext = tlsContextFactory.createSslContext();
                SSLServerSocket sslServerSocket = (SSLServerSocket) sslContext.getServerSocketFactory()
                        .createServerSocket(properties.port());

                tlsContextFactory.configureServerSocket(sslServerSocket);
                serverSocket = sslServerSocket;
                log.info("ISO 8583 TCP Host Simulator started on port {} with TLS/mTLS encryption (clientAuth={})",
                        properties.port(), tlsProperties.getClientAuth());
            } else {
                serverSocket = new ServerSocket(properties.port());
                log.info("ISO 8583 TCP Host Simulator started on port {} (Plain TCP)", properties.port());
            }

            running.set(true);
            Thread.startVirtualThread(this::acceptConnections);

        } catch (Exception exception) {
            throw new IllegalStateException(
                    "Failed to start ISO 8583 TCP server on port: %d".formatted(properties.port()),
                    exception
            );
        }
    }

    private void acceptConnections() {
        while (running.get()) {
            try {
                Socket socket = serverSocket.accept();

                log.info("Incoming ISO 8583 connection from {}", socket.getRemoteSocketAddress());

                clientExecutor.submit(() -> handleClient(socket));

            } catch (IOException exception) {
                if (running.get()) {
                    log.error("Error accepting TCP connection", exception);
                }
            }
        }
    }

    private void handleClient(Socket socket) {
        if (socket instanceof javax.net.ssl.SSLSocket sslSocket) {
            try {
                sslSocket.startHandshake();
                javax.net.ssl.SSLSession session = sslSocket.getSession();
                String peerInfo = "Anonymous";
                try {
                    if (session.getPeerCertificates() != null && session.getPeerCertificates().length > 0) {
                        peerInfo = session.getPeerPrincipal().getName();
                    }
                } catch (javax.net.ssl.SSLPeerUnverifiedException ignored) {
                }
                log.info("TLS Handshake established with {} — Protocol: {}, Cipher: {}, Peer: {}",
                        socket.getRemoteSocketAddress(), session.getProtocol(), session.getCipherSuite(), peerInfo);
            } catch (IOException e) {
                log.warn("TLS Handshake failed with {}: {}", socket.getRemoteSocketAddress(), e.getMessage());
                try {
                    socket.close();
                } catch (IOException ignored) {}
                return;
            }
        }

        try (
                socket;
                DataInputStream input = new DataInputStream(socket.getInputStream());
                DataOutputStream output = new DataOutputStream(socket.getOutputStream())
        ) {

            while (running.get() && !socket.isClosed()) {
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
        long startNanos = System.nanoTime();
        String rawMessage = new String(payload, StandardCharsets.US_ASCII);
        boolean hasHeader = hasTPDUHeader(rawMessage);

        log.info("Received ISO 8583 message ({} bytes): {}", payload.length,
                IsoMessageSanitizer.sanitizePayloadForLogging(rawMessage, hasHeader));

        IsoMessage request  = IsoUnpacker.unpack(rawMessage, hasHeader);
        IsoMessage response = messageProcessor.process(request);

        long durationNanos = System.nanoTime() - startNanos;
        if (metrics != null && response != null) {
            metrics.recordTransaction(
                    response.getMti(),
                    response.getField(39),
                    "DEFAULT",
                    durationNanos,
                    true
            );
        }

        sendResponse(response, output);
    }

    private void sendResponse(IsoMessage response, DataOutputStream output) throws IOException {

        String rawResponse = IsoPacker.packToString(response);

        byte[] responseBytes = rawResponse.getBytes(StandardCharsets.US_ASCII);

        validateFrameLength(responseBytes.length);

        output.writeShort(responseBytes.length);
        output.write(responseBytes);
        output.flush();

        log.info("Sent ISO 8583 response: {}", 
                IsoMessageSanitizer.sanitizeMessage(response));
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
        running.set(false);

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
        return running.get();
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE;
    }
}
