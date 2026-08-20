package com.dean.iso8583.server.tls;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import javax.net.ssl.*;
import java.io.InputStream;
import java.security.KeyStore;
import java.security.SecureRandom;

/**
 * Developer Note:
 * Factory for creating and configuring SSLContext and SSLServerSocket instances
 * for ISO 8583 binary TCP socket communication.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IsoTlsContextFactory {

    private final IsoTlsProperties isoTlsProperties;
    private final ResourceLoader resourceLoader;

    /**
     * Creates and initializes a new SSLContext configured per {@link IsoTlsProperties}.
     */
    public SSLContext createSslContext() {
        try {
            KeyStore keyStore = loadKeyStore(isoTlsProperties.getKeystorePath(), isoTlsProperties.getKeystorePassword());
            KeyManagerFactory keyMgrFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            keyMgrFactory.init(keyStore, isoTlsProperties.getKeystorePassword().toCharArray());

            TrustManager[] trustManagers = null;
            if (isoTlsProperties.getClientAuth() != IsoTlsProperties.ClientAuthMode.NONE
                    && isoTlsProperties.getTruststorePath() != null
                    && !isoTlsProperties.getTruststorePath().isBlank()) {
                KeyStore trustStore = loadKeyStore(isoTlsProperties.getTruststorePath(), isoTlsProperties.getTruststorePassword());
                TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
                tmf.init(trustStore);

                trustManagers = tmf.getTrustManagers();
            }

            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(keyMgrFactory.getKeyManagers(), trustManagers, new SecureRandom());
            return sslContext;

        } catch (Exception e) {
            throw new IllegalStateException("Failed to initialize SSLContext for ISO 8583 TCP server: " + e.getMessage(), e);
        }
    }

    /**
     * Configures client authentication and enabled protocols on an SSLServerSocket.
     */
    public void configureServerSocket(SSLServerSocket sslServerSocket) {
        sslServerSocket.setEnabledProtocols(new String[]{"TLSv1.3", "TLSv1.2"});

        switch (isoTlsProperties.getClientAuth()) {
            case NEED -> {
                sslServerSocket.setNeedClientAuth(true);
                log.info("ISO 8583 TCP Server: Strict Mutual TLS (mTLS) enforced — Client certificates required.");
            }
            case WANT -> {
                sslServerSocket.setWantClientAuth(true);
                log.info("ISO 8583 TCP Server: Client certificate requested (Optional mTLS).");
            }
            case NONE -> {
                sslServerSocket.setNeedClientAuth(false);
                sslServerSocket.setWantClientAuth(false);
                log.info("ISO 8583 TCP Server: Standard Server-side TLS active.");
            }
        }
    }

    private KeyStore loadKeyStore(String path, String password) throws Exception {
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        try (InputStream is = resourceLoader.getResource(path).getInputStream()) {
            keyStore.load(is, password.toCharArray());
        }
        return keyStore;
    }
}
