package com.dean.iso8583.server.tls;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Developer Note:
 * Configuration properties for ISO 8583 TCP Server TLS/mTLS encryption.
 */
@Data
@Component
@ConfigurationProperties(prefix = "iso.tcp.tls")
public class IsoTlsProperties {

    /**
     * Whether TLS is enabled for the binary TCP socket server (port 8583).
     */
    private boolean enabled = false;

    /**
     * Resource path to the PKCS12 server keystore containing the server certificate & private key.
     */
    private String keystorePath = "classpath:certs/server-keystore.p12";

    /**
     * Keystore password.
     */
    private String keystorePassword = "changeit";

    /**
     * Resource path to the PKCS12 server truststore containing trusted client certificates (for mTLS).
     */
    private String truststorePath = "classpath:certs/server-truststore.p12";

    /**
     * Truststore password.
     */
    private String truststorePassword = "changeit";

    /**
     * Client authentication mode:
     * - NONE: Standard server-side TLS (default)
     * - WANT: Request client certificate but do not require it
     * - NEED: Strict mutual TLS (mTLS) - reject clients without a valid certificate
     */
    private ClientAuthMode clientAuth = ClientAuthMode.NONE;

    public enum ClientAuthMode {
        NONE,
        WANT,
        NEED
    }
}
