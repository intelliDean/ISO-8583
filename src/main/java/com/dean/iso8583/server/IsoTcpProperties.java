package com.dean.iso8583.server;


import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "iso.tcp")
public record IsoTcpProperties(
        String host,
        int port,
        int maxFrameLength,
        long shutdownTimeout
) {
    public IsoTcpProperties {
        if (port <= 0 || port > 65535) {
            throw new IllegalArgumentException("Invalid TCP port: " + port);
        }

        if (maxFrameLength <= 0 || maxFrameLength > 65535) {
            throw new IllegalArgumentException("Invalid maximum frame length: %d".formatted(maxFrameLength));
        }
    }
}
