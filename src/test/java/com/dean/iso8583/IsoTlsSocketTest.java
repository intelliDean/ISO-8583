package com.dean.iso8583;

import com.dean.iso8583.core.IsoPacker;
import com.dean.iso8583.core.IsoUnpacker;
import com.dean.iso8583.core.dto.IsoMessage;
import com.dean.iso8583.server.tls.IsoTlsContextFactory;
import com.dean.iso8583.server.tls.IsoTlsProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import javax.net.ssl.*;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("TLS & Mutual TLS (mTLS) Socket Security Tests")
class IsoTlsSocketTest {

    private static final String KEYSTORE_PASS = "changeit";

    @Test
    @DisplayName("Should successfully perform TLS 1.3 handshake and exchange encrypted ISO 8583 frame")
    void shouldExchangeIsoFrameOverTls() throws Exception {
        IsoTlsProperties tlsProps = new IsoTlsProperties();
        tlsProps.setEnabled(true);
        tlsProps.setClientAuth(IsoTlsProperties.ClientAuthMode.NONE);
        tlsProps.setKeystorePath("classpath:certs/server-keystore.p12");
        tlsProps.setKeystorePassword(KEYSTORE_PASS);

        IsoTlsContextFactory factory = new IsoTlsContextFactory(tlsProps, new DefaultResourceLoader());
        SSLContext serverSslContext = factory.createSslContext();
        SSLServerSocket serverSocket = (SSLServerSocket) serverSslContext.getServerSocketFactory().createServerSocket(0);
        factory.configureServerSocket(serverSocket);
        int port = serverSocket.getLocalPort();

        // Server thread: accept, read 0800 frame, return 0810 response
        CompletableFuture<String> serverFuture = CompletableFuture.supplyAsync(() -> {
            try (SSLSocket socket = (SSLSocket) serverSocket.accept();
                 DataInputStream in = new DataInputStream(socket.getInputStream());
                 DataOutputStream out = new DataOutputStream(socket.getOutputStream())) {

                int len = in.readUnsignedShort();
                byte[] payload = new byte[len];
                in.readFully(payload);
                String msgStr = new String(payload, StandardCharsets.US_ASCII);

                IsoMessage req = IsoUnpacker.unpack(msgStr, true);
                IsoMessage resp = new IsoMessage("0810");
                resp.setHeader(req.getHeader());
                resp.setField(7, req.getField(7));
                resp.setField(11, req.getField(11));
                resp.setField(39, "00");
                resp.setField(70, "301");

                byte[] respBytes = IsoPacker.packToString(resp).getBytes(StandardCharsets.US_ASCII);
                out.writeShort(respBytes.length);
                out.write(respBytes);
                out.flush();

                return socket.getSession().getCipherSuite();
            } catch (Exception e) {
                throw new RuntimeException(e);
            } finally {
                try { serverSocket.close(); } catch (Exception ignored) {}
            }
        });

        // Client: Trust all certs for dev test and connect
        TrustManager[] trustAll = new TrustManager[]{new X509TrustManager() {
            public java.security.cert.X509Certificate[] getAcceptedIssuers() { return null; }
            public void checkClientTrusted(java.security.cert.X509Certificate[] c, String a) {}
            public void checkServerTrusted(java.security.cert.X509Certificate[] c, String a) {}
        }};
        SSLContext clientCtx = SSLContext.getInstance("TLS");
        clientCtx.init(null, trustAll, new SecureRandom());

        try (SSLSocket clientSocket = (SSLSocket) clientCtx.getSocketFactory().createSocket()) {
            clientSocket.connect(new InetSocketAddress("localhost", port), 5000);
            DataOutputStream out = new DataOutputStream(clientSocket.getOutputStream());
            DataInputStream in = new DataInputStream(clientSocket.getInputStream());

            IsoMessage echoReq = new IsoMessage("0800");
            echoReq.setHeader("6000000000");
            echoReq.setField(7, "0820235900");
            echoReq.setField(11, "000001");
            echoReq.setField(70, "301");

            byte[] reqBytes = IsoPacker.packToString(echoReq).getBytes(StandardCharsets.US_ASCII);
            out.writeShort(reqBytes.length);
            out.write(reqBytes);
            out.flush();

            int respLen = in.readUnsignedShort();
            byte[] respBuf = new byte[respLen];
            in.readFully(respBuf);

            IsoMessage echoResp = IsoUnpacker.unpack(new String(respBuf, StandardCharsets.US_ASCII), true);
            assertThat(echoResp.getMti()).isEqualTo("0810");
            assertThat(echoResp.getField(39)).isEqualTo("00");

            String cipher = serverFuture.get(5, TimeUnit.SECONDS);
            assertThat(cipher).isNotBlank();
        }
    }

    @Test
    @DisplayName("Should enforce mTLS and reject connections without a client certificate when clientAuth is NEED")
    void shouldEnforceMutualTls() throws Exception {
        IsoTlsProperties tlsProps = new IsoTlsProperties();
        tlsProps.setEnabled(true);
        tlsProps.setClientAuth(IsoTlsProperties.ClientAuthMode.NEED);
        tlsProps.setKeystorePath("classpath:certs/server-keystore.p12");
        tlsProps.setKeystorePassword(KEYSTORE_PASS);
        tlsProps.setTruststorePath("classpath:certs/server-truststore.p12");
        tlsProps.setTruststorePassword(KEYSTORE_PASS);

        IsoTlsContextFactory factory = new IsoTlsContextFactory(tlsProps, new DefaultResourceLoader());
        SSLContext serverSslContext = factory.createSslContext();
        SSLServerSocket serverSocket = (SSLServerSocket) serverSslContext.getServerSocketFactory().createServerSocket(0);
        factory.configureServerSocket(serverSocket);
        int port = serverSocket.getLocalPort();

        CompletableFuture.runAsync(() -> {
            try (SSLSocket socket = (SSLSocket) serverSocket.accept()) {
                socket.startHandshake();
            } catch (Exception ignored) {
            } finally {
                try { serverSocket.close(); } catch (Exception ignored) {}
            }
        });

        // Client without certificate trying to connect to mTLS server
        TrustManager[] trustAll = new TrustManager[]{new X509TrustManager() {
            public java.security.cert.X509Certificate[] getAcceptedIssuers() { return null; }
            public void checkClientTrusted(java.security.cert.X509Certificate[] c, String a) {}
            public void checkServerTrusted(java.security.cert.X509Certificate[] c, String a) {}
        }};
        SSLContext clientNoCertCtx = SSLContext.getInstance("TLS");
        clientNoCertCtx.init(null, trustAll, new SecureRandom());

        assertThatThrownBy(() -> {
            try (SSLSocket clientSocket = (SSLSocket) clientNoCertCtx.getSocketFactory().createSocket()) {
                clientSocket.connect(new InetSocketAddress("localhost", port), 5000);
                clientSocket.startHandshake();
                DataOutputStream out = new DataOutputStream(clientSocket.getOutputStream());
                out.writeShort(5);
                out.write("TEST!".getBytes());
                out.flush();
                clientSocket.getInputStream().read();
            }
        }).isInstanceOf(Exception.class);
    }
}
