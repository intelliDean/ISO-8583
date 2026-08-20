package com.dean.iso8583.core.crypto;

import com.dean.iso8583.core.event.IsoEventPublisher;
import com.dean.iso8583.core.event.IsoEventType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Developer Note:
 * Cryptographic Key Registry for ISO 8583 Payment Engine.
 *
 * <h2>Key Hierarchy &amp; Management</h2>
 * <ul>
 *   <li><b>ZMK (Zone Master Key)</b>: Master key used to encrypt other working keys during key exchange (DE 70 = 101).</li>
 *   <li><b>ZPK (Zone PIN Key)</b>: Working key used for DE 52 PIN block encryption/decryption between nodes.</li>
 *   <li><b>ZAK / MAK (Zone/Message Authentication Key)</b>: Working key used for DE 64 / DE 128 MAC calculation.</li>
 *   <li><b>BDK (Base Derivation Key)</b>: Root key for DUKPT terminal key derivation.</li>
 * </ul>
 *
 * In a certified production PCI-PTS environment, this class acts as the interface
 * to a physical or cloud Hardware Security Module (HSM) such as Thales payShield,
 * Futurex, or AWS CloudHSM.
 */
@Slf4j
@Component
public class CryptoKeyRegistry {

    private static final Map<String, String> DEFAULT_TEST_KEYS_HEX = Map.of(
            "DEFAULT_ZPK_ACQ", "0123456789ABCDEFFEDCBA9876543210",
            "DEFAULT_ZPK_ISS", "FEDCBA98765432100123456789ABCDEF",
            "DEFAULT_MAK",     "0123456789ABCDEFFEDCBA9876543210",
            "DEFAULT_BDK",     "0123456789ABCDEFFEDCBA9876543210"
    );

    private final ConcurrentHashMap<String, byte[]> keys = new ConcurrentHashMap<>();

    @Autowired(required = false)
    private IsoEventPublisher eventPublisher;

    public CryptoKeyRegistry() {
        registerDefaultTestKeys();
    }

    // Initializes standard default test keys for simulated development environment
    private void registerDefaultTestKeys() {
        DEFAULT_TEST_KEYS_HEX.forEach((keyId, hex) ->
                storeKey(normalize(keyId), CryptoUtils.hexToBytes(hex)));
    }

    /**
     * Registers or updates a named cryptographic key.
     *
     * @param keyId    unique key identifier (e.g. "VISA_ZPK", "ACQUIRER_MAK")
     * @param keyBytes key material bytes
     */
    public void registerKey(String keyId, byte[] keyBytes) {
        validateKeyInput(keyId, keyBytes);

        String normalizedId = normalize(keyId);
        storeKey(normalizedId, keyBytes);
        publishKeyRotated(normalizedId, keyBytes.length);
    }

    /**
     * Retrieves a registered key by ID.
     */
    public Optional<byte[]> getKey(String keyId) {

        if (keyId == null) return Optional.empty();

        return Optional.ofNullable(keys.get(normalize(keyId)));
    }

    /**
     * Checks if a key exists in the registry.
     */
    public boolean hasKey(String keyId) {
        return keyId != null && keys.containsKey(normalize(keyId));
    }

    /**
     * Returns the total count of active keys in the registry.
     */
    public int size() {
        return keys.size();
    }

    private void validateKeyInput(String keyId, byte[] keyBytes) {
        if (keyId == null || keyId.isBlank() || keyBytes == null) {
            throw new IllegalArgumentException("Key ID and key material must not be null or empty");
        }
    }

    private String normalize(String keyId) {
        return keyId.toUpperCase();
    }

    private void storeKey(String normalizedId, byte[] keyBytes) {
        keys.put(normalizedId, keyBytes);
        log.info("Registered cryptographic key '{}' ({} bytes)", normalizedId, keyBytes.length);
    }

    private void publishKeyRotated(String normalizedId, int keyLengthBytes) {

        if (eventPublisher == null) return;

        eventPublisher.publish("CRYPTO_KEY", normalizedId, IsoEventType.CRYPTO_KEY_ROTATED,
                Map.of("keyId", normalizedId, "keyLengthBytes", keyLengthBytes)
        );
    }
}