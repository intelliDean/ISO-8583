package com.dean.iso8583.core.crypto;

import lombok.extern.slf4j.Slf4j;
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

    private final ConcurrentHashMap<String, byte[]> keys = new ConcurrentHashMap<>();

    public CryptoKeyRegistry() {
        // Initialize standard default test keys for simulated development environment
        registerKey("DEFAULT_ZPK_ACQ", CryptoUtils.hexToBytes("0123456789ABCDEFFEDCBA9876543210"));
        registerKey("DEFAULT_ZPK_ISS", CryptoUtils.hexToBytes("FEDCBA98765432100123456789ABCDEF"));
        registerKey("DEFAULT_MAK",     CryptoUtils.hexToBytes("0123456789ABCDEFFEDCBA9876543210"));
        registerKey("DEFAULT_BDK",     CryptoUtils.hexToBytes("0123456789ABCDEFFEDCBA9876543210"));
    }

    /**
     * Registers or updates a named cryptographic key.
     *
     * @param keyId   unique key identifier (e.g. "VISA_ZPK", "ACQUIRER_MAK")
     * @param keyBytes key material bytes
     */
    public void registerKey(String keyId, byte[] keyBytes) {
        if (keyId == null || keyId.isBlank() || keyBytes == null) {
            throw new IllegalArgumentException("Key ID and key material must not be null or empty");
        }
        keys.put(keyId.toUpperCase(), keyBytes);
        log.info("Registered cryptographic key '{}' ({} bytes)", keyId.toUpperCase(), keyBytes.length);
    }

    /**
     * Retrieves a registered key by ID.
     */
    public Optional<byte[]> getKey(String keyId) {
        if (keyId == null) return Optional.empty();
        return Optional.ofNullable(keys.get(keyId.toUpperCase()));
    }

    /**
     * Checks if a key exists in the registry.
     */
    public boolean hasKey(String keyId) {
        return keyId != null && keys.containsKey(keyId.toUpperCase());
    }

    /**
     * Returns the total count of active keys in the registry.
     */
    public int size() {
        return keys.size();
    }
}
