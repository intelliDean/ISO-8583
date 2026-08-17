package com.dean.iso8583.core.crypto;

import lombok.extern.slf4j.Slf4j;

/**
 * Enterprise ANSI X9.24-1 DUKPT (Derived Unique Key Per Transaction) Key Management Engine.
 *
 * <h2>What is DUKPT?</h2>
 * DUKPT is the gold standard point-of-sale key management protocol specified in ANSI X9.24-1.
 * It ensures that every single transaction processed by a physical PIN pad or POS terminal is
 * encrypted using a unique, one-time session key. Even if an attacker compromises a terminal's
 * current key register, they cannot derive past keys or decrypt previous transactions (forward secrecy).
 *
 * <h2>KSN (Key Serial Number) Structure (10 Bytes / 20 Hex Chars)</h2>
 * <pre>
 *   [Key Set ID / Base Key ID: 6 Bytes] [Device ID: 1.5 Bytes] [Transaction Counter: 21 Bits]
 * </pre>
 *
 * <h2>Key Types Derived</h2>
 * <ul>
 *   <li><b>PIN Encryption Key (PEK)</b>: Used to decrypt DE 52 in POS transactions.</li>
 *   <li><b>Data Encryption Key (DEK)</b>: Used for field-level cardholder track data encryption.</li>
 *   <li><b>MAC Key (MAK)</b>: Used for ISO 9797 message authentication code generation.</li>
 * </ul>
 */
@Slf4j
public final class DukptEngine {

    // Standard ANSI X9.24-1 Variant Masks
    private static final byte[] PIN_VARIANT_MASK = CryptoUtils.hexToBytes("00000000000000FF00000000000000FF");
    private static final byte[] MAC_VARIANT_MASK = CryptoUtils.hexToBytes("000000000000FF00000000000000FF00");
    private static final byte[] DATA_VARIANT_MASK = CryptoUtils.hexToBytes("0000000000FF00000000000000FF0000");

    private static final byte[] BDK_MASK = CryptoUtils.hexToBytes("C0C0C0C000000000C0C0C0C000000000");
    private static final byte[] KEY_REGISTER_MASK = CryptoUtils.hexToBytes("C0C0C0C000000000C0C0C0C000000000");

    private DukptEngine() {
        // Utility class
    }

    /**
     * Derives the Initial PIN Encryption Key (IPEK) from a Base Derivation Key (BDK) and KSN.
     *
     * @param bdk double-length 16-byte BDK (Base Derivation Key)
     * @param ksn 10-byte (20-hex) Key Serial Number
     * @return 16-byte IPEK
     */
    public static byte[] deriveIpek(byte[] bdk, byte[] ksn) {
        if (bdk.length != 16) {
            throw new IllegalArgumentException("BDK must be 16 bytes (Double-Length Triple-DES key)");
        }
        if (ksn.length != 10) {
            throw new IllegalArgumentException("KSN must be exactly 10 bytes (20 hex characters)");
        }

        // Mask out the bottom 21 bits (transaction counter) of the 10-byte KSN to get the Base KSN (8 bytes)
        byte[] baseKsn = new byte[8];
        System.arraycopy(ksn, 0, baseKsn, 0, 8);
        baseKsn[7] &= (byte) 0xE0; // zero bottom 21 bits (last 5 bits of byte 7 + bytes 8, 9)

        // Left half of IPEK = 3DES(baseKsn) under BDK
        byte[] leftIpek = CryptoUtils.desEncryptEcb(baseKsn, bdk);

        // Right half of IPEK = 3DES(baseKsn) under (BDK XOR BDK_MASK)
        byte[] bdkXor = CryptoUtils.xor(bdk, BDK_MASK);
        byte[] rightIpek = CryptoUtils.desEncryptEcb(baseKsn, bdkXor);

        byte[] ipek = new byte[16];
        System.arraycopy(leftIpek, 0, ipek, 0, 8);
        System.arraycopy(rightIpek, 0, ipek, 8, 8);

        return ipek;
    }

    /**
     * Derives the transaction-specific Future Key from an IPEK and transaction KSN.
     *
     * @param ipek 16-byte IPEK
     * @param ksn  10-byte KSN containing the device transaction counter
     * @return 16-byte transaction-specific future key
     */
    public static byte[] deriveTransactionKey(byte[] ipek, byte[] ksn) {
        // Extract 21-bit transaction counter from the last 3 bytes of KSN
        long counter = extractTransactionCounter(ksn);

        byte[] currentKey = new byte[16];
        System.arraycopy(ipek, 0, currentKey, 0, 16);

        // 8-byte working counter register
        byte[] r8 = new byte[8];
        System.arraycopy(ksn, 2, r8, 0, 6);
        r8[5] &= (byte) 0xE0;

        // Propagate non-reversible key generation function for each set bit in counter
        long mask = 0x100000L; // 21st bit (MSB of counter)
        while (mask > 0) {
            if ((counter & mask) != 0) {
                // Set the corresponding bit in r8
                r8[5] |= (byte) ((mask >> 16) & 0x1F);
                r8[6] = (byte) ((mask >> 8) & 0xFF);
                r8[7] = (byte) (mask & 0xFF);

                currentKey = nonReversibleKeyGeneration(currentKey, r8);
            }
            mask >>= 1;
        }

        return currentKey;
    }

    /**
     * Derives the Session PIN Encryption Key (PEK) for a transaction.
     *
     * @param bdk 16-byte Base Derivation Key
     * @param ksn 10-byte KSN
     * @return 16-byte session PIN key
     */
    public static byte[] derivePinKey(byte[] bdk, byte[] ksn) {
        byte[] ipek = deriveIpek(bdk, ksn);
        byte[] transKey = deriveTransactionKey(ipek, ksn);
        return CryptoUtils.xor(transKey, PIN_VARIANT_MASK);
    }

    /**
     * Derives the Session MAC Key (MAK) for a transaction.
     *
     * @param bdk 16-byte Base Derivation Key
     * @param ksn 10-byte KSN
     * @return 16-byte session MAC key
     */
    public static byte[] deriveMacKey(byte[] bdk, byte[] ksn) {
        byte[] ipek = deriveIpek(bdk, ksn);
        byte[] transKey = deriveTransactionKey(ipek, ksn);
        return CryptoUtils.xor(transKey, MAC_VARIANT_MASK);
    }

    /**
     * Derives the Session Data Encryption Key (DEK) for a transaction.
     *
     * @param bdk 16-byte Base Derivation Key
     * @param ksn 10-byte KSN
     * @return 16-byte session Data key
     */
    public static byte[] deriveDataKey(byte[] bdk, byte[] ksn) {
        byte[] ipek = deriveIpek(bdk, ksn);
        byte[] transKey = deriveTransactionKey(ipek, ksn);
        return CryptoUtils.xor(transKey, DATA_VARIANT_MASK);
    }

    /**
     * Extracts the 21-bit numeric transaction counter from a 10-byte KSN.
     */
    public static long extractTransactionCounter(byte[] ksn) {
        long b7 = ksn[7] & 0x1F;
        long b8 = ksn[8] & 0xFF;
        long b9 = ksn[9] & 0xFF;
        return (b7 << 16) | (b8 << 8) | b9;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Non-Reversible Key Generation Function (ANSI X9.24 § A.1.4)
    // ─────────────────────────────────────────────────────────────────────────

    private static byte[] nonReversibleKeyGeneration(byte[] key, byte[] r8) {
        byte[] leftKey = new byte[8];
        byte[] rightKey = new byte[8];
        System.arraycopy(key, 0, leftKey, 0, 8);
        System.arraycopy(key, 8, rightKey, 0, 8);

        // Message = rightKey XOR r8
        byte[] msg = CryptoUtils.xor(rightKey, r8);

        // DES encrypt msg under leftKey
        byte[] desLeft = CryptoUtils.desEncryptEcb(msg, leftKey);
        byte[] newRight = CryptoUtils.xor(desLeft, rightKey);

        // Left key XOR with variant mask
        byte[] keyXor = CryptoUtils.xor(key, KEY_REGISTER_MASK);
        byte[] leftKeyXor = new byte[8];
        System.arraycopy(keyXor, 0, leftKeyXor, 0, 8);

        byte[] desRight = CryptoUtils.desEncryptEcb(msg, leftKeyXor);
        byte[] newLeft = CryptoUtils.xor(desRight, leftKey);

        byte[] result = new byte[16];
        System.arraycopy(newLeft, 0, result, 0, 8);
        System.arraycopy(newRight, 0, result, 8, 8);

        return result;
    }
}
