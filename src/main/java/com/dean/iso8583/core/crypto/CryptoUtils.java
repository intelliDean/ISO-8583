package com.dean.iso8583.core.crypto;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;

/**
 * Developer Note:
 * Cryptographic utility functions for payment engine operations:
 *  - Byte array XOR operations
 *  - 2-Key & 3-Key Triple-DES (TDES / DESede) encryption and decryption
 *  - AES-128 / AES-192 / AES-256 encryption and decryption
 *  - Secure random hex padding generation
 *  - Hex string & byte array conversions
 */
public final class CryptoUtils {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final char[] HEX_ARRAY = "0123456789ABCDEF".toCharArray();
    private static final String AES = "AES";

    private CryptoUtils() {
        // Utility class
    }

    /**
     * Converts a hexadecimal string to a byte array.
     */
    public static byte[] hexToBytes(String hex) {
        if (hex == null || hex.isEmpty()) {
            return new byte[0];
        }

        String cleanHex = cleanHex(hex);
        validateEvenLength(cleanHex, hex);

        byte[] bytes = new byte[cleanHex.length() / 2];
        for (int i = 0; i < cleanHex.length(); i += 2) {
            bytes[i / 2] = hexPairToByte(cleanHex.charAt(i), cleanHex.charAt(i + 1), hex);
        }
        return bytes;
    }

    private static String cleanHex(String hex) {
        return hex.replaceAll("\\s+", "").toUpperCase();
    }

    private static void validateEvenLength(String cleanHex, String original) {
        if ((cleanHex.length() & 1) != 0) {
            throw new IllegalArgumentException("Hex string must have an even number of characters: %s"
                    .formatted(original));
        }
    }

    private static byte hexPairToByte(char highChar, char lowChar, String original) {
        int high = Character.digit(highChar, 16);
        int low = Character.digit(lowChar, 16);

        if (high == -1 || low == -1) {
            throw new IllegalArgumentException("Invalid hex character in: %s".formatted(original));
        }
        return (byte) ((high << 4) | low);
    }

    /**
     * Converts a byte array to an uppercase hexadecimal string.
     */
    public static String bytesToHex(byte[] bytes) {

        if (bytes == null || bytes.length == 0) return "";

        char[] hexChars = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            writeHexPair(hexChars, i * 2, bytes[i]);
        }
        return new String(hexChars);
    }

    private static void writeHexPair(char[] hexChars, int offset, byte b) {
        int v = b & 0xFF;
        hexChars[offset] = HEX_ARRAY[v >>> 4];
        hexChars[offset + 1] = HEX_ARRAY[v & 0x0F];
    }

    /**
     * Performs a bitwise XOR between two equal-length byte arrays.
     */
    public static byte[] xor(byte[] a, byte[] b) {
        validateEqualLength(a, b);

        byte[] result = new byte[a.length];
        for (int i = 0; i < a.length; i++) {
            result[i] = (byte) (a[i] ^ b[i]);
        }
        return result;
    }

    private static void validateEqualLength(byte[] a, byte[] b) {
        if (a.length != b.length) {
            throw new IllegalArgumentException("Arrays must have identical length for XOR: %d != %d"
                    .formatted(a.length, b.length));
        }
    }

    /**
     * Generates a string of pseudo-random hexadecimal characters of the specified length.
     */
    public static String generateRandomHex(int length) {
        StringBuilder sb = new StringBuilder(length);

        for (int i = 0; i < length; i++) {
            sb.append(HEX_ARRAY[SECURE_RANDOM.nextInt(16)]);
        }
        return sb.toString();
    }

    /**
     * Encrypts 8 bytes of plaintext using Single-DES or Triple-DES ECB mode (NoPadding).
     * Automatically adjusts 16-byte (2-key) 3DES to 24-byte (3-key) format for JCE compatibility.
     */
    public static byte[] desEncryptEcb(byte[] data, byte[] key) {
        return runDesCipher(data, key, Cipher.ENCRYPT_MODE, "DES/3DES encryption failed");
    }

    /**
     * Decrypts 8 bytes of ciphertext using Single-DES or Triple-DES ECB mode (NoPadding).
     */
    public static byte[] desDecryptEcb(byte[] data, byte[] key) {
        return runDesCipher(data, key, Cipher.DECRYPT_MODE, "DES/3DES decryption failed");
    }

    private static byte[] runDesCipher(byte[] data, byte[] key, int cipherMode, String failureContext) {
        byte[] expandedKey = expandDesKey(key);
        String algo = resolveDesAlgorithm(expandedKey);
        return runEcbCipher(algo, expandedKey, data, cipherMode, failureContext);
    }

    private static String resolveDesAlgorithm(byte[] expandedKey) {
        return expandedKey.length == 8 ? "DES" : "DESede";
    }

    /**
     * Encrypts data using AES ECB mode (NoPadding). Data length must be a multiple of 16 bytes.
     */
    public static byte[] aesEncryptEcb(byte[] data, byte[] key) {
        return runEcbCipher(AES, key, data, Cipher.ENCRYPT_MODE, "%s encryption failed".formatted(AES));
    }

    /**
     * Decrypts data using AES ECB mode (NoPadding). Data length must be a multiple of 16 bytes.
     */
    public static byte[] aesDecryptEcb(byte[] data, byte[] key) {
        return runEcbCipher(AES, key, data, Cipher.DECRYPT_MODE, "%s decryption failed".formatted(AES));
    }

    /**
     * Shared ECB/NoPadding cipher execution for both DES/3DES and AES, since encrypt and decrypt
     * only differ by Cipher mode and the two algorithm families only differ by algorithm name.
     */
    private static byte[] runEcbCipher(
            String algo,
            byte[] key,
            byte[] data,
            int cipherMode,
            String failureContext
    ) {
        try {
            SecretKeySpec keySpec = new SecretKeySpec(key, algo);
            Cipher cipher = Cipher.getInstance(algo + "/ECB/NoPadding");
            cipher.init(cipherMode, keySpec);
            return cipher.doFinal(data);

        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("%s:%s".formatted(failureContext, e.getMessage()), e);
        }
    }

    /**
     * Normalizes a 16-byte Double-Length 3DES key (K1 || K2) into a 24-byte Triple-Length key (K1 || K2 || K1).
     */
    public static byte[] expandDesKey(byte[] key) {
        if (key.length == 16) {
            byte[] expanded = new byte[24];
            System.arraycopy(key, 0, expanded, 0, 16);
            System.arraycopy(key, 0, expanded, 16, 8); // Duplicate K1
            return expanded;
        }
        return key;
    }
}
