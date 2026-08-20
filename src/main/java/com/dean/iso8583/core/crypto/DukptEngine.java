package com.dean.iso8583.core.crypto;

import lombok.extern.slf4j.Slf4j;

import java.util.Arrays;

/**
 * Developer Note:
 * Industry-standard ANSI X9.24-1:2009 / 2017 DUKPT (Derived Unique Key Per Transaction) Key Management Engine.
 *
 * <h2>DUKPT Architecture &amp; Key Lifecycle</h2>
 * <ul>
 *   <li><b>BDK (Base Derivation Key)</b>: 16-byte Double-Length 3DES key securely injected in payment switches/HSM.</li>
 *   <li><b>KSN (Key Serial Number)</b>: 10-byte identifier (59-bit Key Set + Device ID, 21-bit Transaction Counter).</li>
 *   <li><b>IPEK (Initial PIN Encryption Key)</b>: Derived once per POS device using BDK and masked KSN.</li>
 *   <li><b>Transaction Working Keys</b>: Non-reversible forward-secure tree derivation for each transaction.</li>
 *   <li><b>Key Variants</b>:
 *     <ul>
 *       <li>{@code PEK (PIN Encryption)}: {@code TxnKey ^ 00000000000000FF00000000000000FF}</li>
 *       <li>{@code MAK (MAC Calculation)}: {@code TxnKey ^ 000000000000FF00000000000000FF00}</li>
 *       <li>{@code DEK (Data Encryption)}: {@code TxnKey ^ 0000000000FF00000000000000FF0000}</li>
 *     </ul>
 *   </li>
 * </ul>
 */
@Slf4j
public final class DukptEngine {

    public static final byte[] PIN_KEY_VARIANT_MASK  = CryptoUtils.hexToBytes("00000000000000FF00000000000000FF");
    public static final byte[] MAC_KEY_VARIANT_MASK  = CryptoUtils.hexToBytes("000000000000FF00000000000000FF00");
    public static final byte[] DATA_KEY_VARIANT_MASK = CryptoUtils.hexToBytes("0000000000FF00000000000000FF0000");

    private static final byte[] BDK_MASK     = CryptoUtils.hexToBytes("C0C0C0C000000000C0C0C0C000000000");
    private static final byte[] KEY_GEN_MASK = CryptoUtils.hexToBytes("C0C0C0C000000000C0C0C0C000000000");

    private DukptEngine() {
        // Utility class
    }

    /**
     * Derives Initial PIN Encryption Key (IPEK) from BDK and KSN according to ANSI X9.24 Section A.4.
     *
     * @param bdk 16-byte Base Derivation Key
     * @param ksn 10-byte Key Serial Number
     * @return 16-byte IPEK
     */
    public static byte[] deriveIpek(byte[] bdk, byte[] ksn) {
        validateBdk(bdk);
        validateKsn(ksn);

        byte[] ksnMasked = maskKsn(ksn);
        byte[] ksn8 = new byte[8];
        System.arraycopy(ksnMasked, 0, ksn8, 0, 8);

        // Left half: 3DES encrypt with BDK
        byte[] ipekLeft = CryptoUtils.desEncryptEcb(ksn8, bdk);

        // Right half: 3DES encrypt with (BDK XOR BDK_MASK)
        byte[] bdkXor = CryptoUtils.xor(bdk, BDK_MASK);
        byte[] ipekRight = CryptoUtils.desEncryptEcb(ksn8, bdkXor);

        byte[] ipek = new byte[16];
        System.arraycopy(ipekLeft, 0, ipek, 0, 8);
        System.arraycopy(ipekRight, 0, ipek, 8, 8);
        return ipek;
    }

    /**
     * Derives current Transaction Key from IPEK and KSN via non-reversible key generation tree.
     *
     * @param ipek 16-byte Initial PIN Encryption Key
     * @param ksn  10-byte Key Serial Number with active transaction counter
     * @return 16-byte Transaction Key
     */
    public static byte[] deriveTransactionKey(byte[] ipek, byte[] ksn) {
        validateIpek(ipek);
        validateKsn(ksn);

        byte[] currentKsn = maskKsn(ksn);
        byte[] currentKey = Arrays.copyOf(ipek, ipek.length);

        long counter = extractTransactionCounter(ksn);
        long bitMask = 0x100000L; // 21-bit counter MSB (bit 20)

        while (bitMask > 0) {
            if ((counter & bitMask) != 0) {
                // Set active bit in currentKsn
                currentKsn[7] |= (byte) ((bitMask >> 16) & 0x1F);
                currentKsn[8] |= (byte) ((bitMask >> 8) & 0xFF);
                currentKsn[9] |= (byte) (bitMask & 0xFF);

                currentKey = nonReversibleKeyGeneration(currentKey, currentKsn);
            }
            bitMask >>= 1;
        }

        return currentKey;
    }

    /**
     * ANSI X9.24 Non-Reversible Key Generation Function (Generate Next Intermediate Key).
     */
    public static byte[] nonReversibleKeyGeneration(byte[] key, byte[] ksn) {
        byte[] ksn8 = new byte[8];
        System.arraycopy(ksn, 2, ksn8, 0, 8); // Last 8 bytes of 10-byte KSN

        byte[] keyLeft = Arrays.copyOfRange(key, 0, 8);
        byte[] keyRight = Arrays.copyOfRange(key, 8, 16);

        // Step 1: msg = R_8 XOR K_R
        byte[] msg1 = CryptoUtils.xor(ksn8, keyRight);
        // Step 2: Single-DES encrypt msg with K_L
        byte[] temp1 = CryptoUtils.desEncryptEcb(msg1, keyLeft);
        // Step 3: new_right = temp1 XOR K_R
        byte[] newRight = CryptoUtils.xor(temp1, keyRight);

        // Step 4: Masked key
        byte[] keyMasked = CryptoUtils.xor(key, KEY_GEN_MASK);
        byte[] keyMaskedLeft = Arrays.copyOfRange(keyMasked, 0, 8);
        byte[] keyMaskedRight = Arrays.copyOfRange(keyMasked, 8, 16);

        // Step 5: msg2 = R_8 XOR K_masked_R
        byte[] msg2 = CryptoUtils.xor(ksn8, keyMaskedRight);
        // Step 6: Single-DES encrypt msg2 with K_masked_L
        byte[] temp2 = CryptoUtils.desEncryptEcb(msg2, keyMaskedLeft);
        // Step 7: new_left = temp2 XOR K_masked_R
        byte[] newLeft = CryptoUtils.xor(temp2, keyMaskedRight);

        byte[] nextKey = new byte[16];
        System.arraycopy(newLeft, 0, nextKey, 0, 8);
        System.arraycopy(newRight, 0, nextKey, 8, 8);
        return nextKey;
    }

    /**
     * Derives PIN Encryption Key (PEK) variant from transaction key.
     */
    public static byte[] derivePinKey(byte[] transactionKey) {
        return CryptoUtils.xor(transactionKey, PIN_KEY_VARIANT_MASK);
    }

    /**
     * Derives Message Authentication Key (MAK) variant from transaction key.
     */
    public static byte[] deriveMacKey(byte[] transactionKey) {
        return CryptoUtils.xor(transactionKey, MAC_KEY_VARIANT_MASK);
    }

    /**
     * Derives Data Encryption Key (DEK) variant from transaction key.
     */
    public static byte[] deriveDataKey(byte[] transactionKey) {
        return CryptoUtils.xor(transactionKey, DATA_KEY_VARIANT_MASK);
    }

    /**
     * Masks out the 21-bit transaction counter from a 10-byte KSN (zeroes low 21 bits).
     */
    public static byte[] maskKsn(byte[] ksn) {
        byte[] masked = Arrays.copyOf(ksn, 10);
        masked[7] &= (byte) 0xE0; // Zero out low 5 bits
        masked[8] = 0x00;
        masked[9] = 0x00;
        return masked;
    }

    /**
     * Extracts the 21-bit integer transaction counter from a 10-byte KSN.
     */
    public static long extractTransactionCounter(byte[] ksn) {
        validateKsn(ksn);
        return (((long) (ksn[7] & 0x1F)) << 16) | (((long) (ksn[8] & 0xFF)) << 8) | ((long) (ksn[9] & 0xFF));
    }

    /**
     * Extracts Key Set ID (KSI) hex from KSN.
     */
    public static String extractKeySetId(byte[] ksn) {
        validateKsn(ksn);
        return CryptoUtils.bytesToHex(Arrays.copyOfRange(ksn, 0, 3));
    }

    /**
     * Extracts Device ID hex from KSN.
     */
    public static String extractDeviceId(byte[] ksn) {
        validateKsn(ksn);
        return CryptoUtils.bytesToHex(Arrays.copyOfRange(ksn, 3, 7));
    }

    /**
     * End-to-end DUKPT PIN decryption: derives PEK from BDK + KSN and decrypts the PIN block.
     *
     * @param bdk                  16-byte Base Derivation Key
     * @param ksn                  10-byte Key Serial Number
     * @param encryptedPinBlockHex 16-hex character DE 52 PIN block
     * @param pan                  Primary Account Number
     * @param format               ISO 9564 PIN block format
     * @return clear numeric PIN
     */
    public static String decryptDukptPin(
            byte[] bdk,
            byte[] ksn,
            String encryptedPinBlockHex,
            String pan,
            PinBlockFormat format
    ) {
        byte[] ipek = deriveIpek(bdk, ksn);
        byte[] txnKey = deriveTransactionKey(ipek, ksn);
        byte[] pinKey = derivePinKey(txnKey);

        return IsoPinBlockEngine.decryptPin(encryptedPinBlockHex, pan, format, pinKey);
    }

    private static void validateBdk(byte[] bdk) {
        if (bdk == null || (bdk.length != 16 && bdk.length != 24)) {
            throw new IllegalArgumentException("BDK must be 16 or 24 bytes (double/triple length 3DES)");
        }
    }

    private static void validateIpek(byte[] ipek) {
        if (ipek == null || (ipek.length != 16 && ipek.length != 24)) {
            throw new IllegalArgumentException("IPEK must be 16 or 24 bytes");
        }
    }

    private static void validateKsn(byte[] ksn) {
        if (ksn == null || ksn.length != 10) {
            throw new IllegalArgumentException("KSN must be exactly 10 bytes (20 hex characters)");
        }
    }
}