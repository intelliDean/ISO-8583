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

    private static final int BDK_LENGTH = 16;
    private static final int KSN_LENGTH = 10;
    private static final int HALF_KEY_LENGTH = 8;
    private static final long COUNTER_MSB_MASK = 0x100000L; // 21st bit (MSB of counter)

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
    public static byte[] deriveIPEK(byte[] bdk, byte[] ksn) {
        validateBdk(bdk);
        validateKsn(ksn);

        byte[] baseKsn = computeBaseKsn(ksn);

        // Left half of IPEK = 3DES(baseKsn) under BDK
        byte[] leftIpek = CryptoUtils.desEncryptEcb(baseKsn, bdk);

        // Right half of IPEK = 3DES(baseKsn) under (BDK XOR BDK_MASK)
        byte[] bdkXor = CryptoUtils.xor(bdk, BDK_MASK);
        byte[] rightIpek = CryptoUtils.desEncryptEcb(baseKsn, bdkXor);

        return combineHalves(leftIpek, rightIpek);
    }

    private static void validateBdk(byte[] bdk) {
        if (bdk.length != BDK_LENGTH) {
            throw new IllegalArgumentException("BDK must be 16 bytes (Double-Length Triple-DES key)");
        }
    }

    private static void validateKsn(byte[] ksn) {
        if (ksn.length != KSN_LENGTH) {
            throw new IllegalArgumentException("KSN must be exactly 10 bytes (20 hex characters)");
        }
    }

    /**
     * Masks out the bottom 21 bits (transaction counter) of the 10-byte KSN to get the Base KSN (8 bytes).
     */
    private static byte[] computeBaseKsn(byte[] ksn) {
        byte[] baseKsn = new byte[8];
        System.arraycopy(ksn, 0, baseKsn, 0, 8);
        baseKsn[7] &= (byte) 0xE0; // zero bottom 21 bits (last 5 bits of byte 7 + bytes 8, 9)
        return baseKsn;
    }

    /**
     * Derives the transaction-specific Future Key from an IPEK and transaction KSN.
     *
     * @param ipek 16-byte IPEK
     * @param ksn  10-byte KSN containing the device transaction counter
     * @return 16-byte transaction-specific future key
     */
    public static byte[] deriveTransactionKey(byte[] ipek, byte[] ksn) {
        long counter = extractTransactionCounter(ksn);
        byte[] currentKey = ipek.clone();
        byte[] r8 = initializeCounterRegister(ksn);

        // Propagate non-reversible key generation function for each set bit in counter
        for (long mask = COUNTER_MSB_MASK; mask > 0; mask >>= 1) {
            if ((counter & mask) != 0) {
                setRegisterBit(r8, mask);
                currentKey = nonReversibleKeyGeneration(currentKey, r8);
            }
        }

        return currentKey;
    }

    /**
     * Builds the initial 8-byte working counter register from the KSN's transaction-counter bytes.
     */
    private static byte[] initializeCounterRegister(byte[] ksn) {
        byte[] r8 = new byte[8];
        System.arraycopy(ksn, 2, r8, 0, 6);
        r8[5] &= (byte) 0xE0;
        return r8;
    }

    /**
     * Sets the bit corresponding to {@code mask} in the working counter register.
     */
    private static void setRegisterBit(byte[] r8, long mask) {
        r8[5] |= (byte) ((mask >> 16) & 0x1F);
        r8[6] = (byte) ((mask >> 8) & 0xFF);
        r8[7] = (byte) (mask & 0xFF);
    }

    /**
     * Derives the Session PIN Encryption Key (PEK) for a transaction.
     *
     * @param bdk 16-byte Base Derivation Key
     * @param ksn 10-byte KSN
     * @return 16-byte session PIN key
     */
    public static byte[] derivePinKey(byte[] bdk, byte[] ksn) {
        return deriveSessionKey(bdk, ksn, PIN_VARIANT_MASK);
    }

    /**
     * Derives the Session MAC Key (MAK) for a transaction.
     *
     * @param bdk 16-byte Base Derivation Key
     * @param ksn 10-byte KSN
     * @return 16-byte session MAC key
     */
    public static byte[] deriveMacKey(byte[] bdk, byte[] ksn) {
        return deriveSessionKey(bdk, ksn, MAC_VARIANT_MASK);
    }

    /**
     * Derives the Session Data Encryption Key (DEK) for a transaction.
     *
     * @param bdk 16-byte Base Derivation Key
     * @param ksn 10-byte KSN
     * @return 16-byte session Data key
     */
    public static byte[] deriveDataKey(byte[] bdk, byte[] ksn) {
        return deriveSessionKey(bdk, ksn, DATA_VARIANT_MASK);
    }

    /**
     * Shared derivation path for PIN/MAC/Data session keys: IPEK -> transaction key -> variant XOR.
     * The three public derive*Key methods differ only in which variant mask is applied.
     */
    private static byte[] deriveSessionKey(byte[] bdk, byte[] ksn, byte[] variantMask) {
        byte[] ipek = deriveIPEK(bdk, ksn);
        byte[] transKey = deriveTransactionKey(ipek, ksn);
        return CryptoUtils.xor(transKey, variantMask);
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
        byte[] leftKey = leftHalf(key);
        byte[] rightKey = rightHalf(key);

        // Message = rightKey XOR r8
        byte[] msg = CryptoUtils.xor(rightKey, r8);

        // DES encrypt msg under leftKey
        byte[] newRight = CryptoUtils.xor(CryptoUtils.desEncryptEcb(msg, leftKey), rightKey);

        // Left key XOR with variant mask
        byte[] leftKeyXor = leftHalf(CryptoUtils.xor(key, KEY_REGISTER_MASK));
        byte[] newLeft = CryptoUtils.xor(CryptoUtils.desEncryptEcb(msg, leftKeyXor), leftKey);

        return combineHalves(newLeft, newRight);
    }

    private static byte[] leftHalf(byte[] key) {
        byte[] left = new byte[HALF_KEY_LENGTH];
        System.arraycopy(key, 0, left, 0, HALF_KEY_LENGTH);
        return left;
    }

    private static byte[] rightHalf(byte[] key) {
        byte[] right = new byte[HALF_KEY_LENGTH];
        System.arraycopy(key, HALF_KEY_LENGTH, right, 0, HALF_KEY_LENGTH);
        return right;
    }

    private static byte[] combineHalves(byte[] left, byte[] right) {
        byte[] combined = new byte[HALF_KEY_LENGTH * 2];
        System.arraycopy(left, 0, combined, 0, HALF_KEY_LENGTH);
        System.arraycopy(right, 0, combined, HALF_KEY_LENGTH, HALF_KEY_LENGTH);
        return combined;
    }
}