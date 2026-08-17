package com.dean.iso8583.web.data.dto;

import com.dean.iso8583.core.crypto.PinBlockFormat;

/**
 * Request body for atomic cross-zone PIN block translation.
 *
 * @param encryptedBlockHex incoming DE 52 encrypted PIN block
 * @param srcPan            source PAN
 * @param srcFormat         source format
 * @param srcKeyHex         source key hex (or null to use keyId)
 * @param srcKeyId          source key identifier (e.g. "DEFAULT_ZPK_ACQ")
 * @param dstPan            destination PAN (if different from srcPan, else null to reuse srcPan)
 * @param dstFormat         destination format
 * @param dstKeyHex         destination key hex (or null to use keyId)
 * @param dstKeyId          destination key identifier (e.g. "DEFAULT_ZPK_ISS")
 */
public record PinTranslateRequest(
        String encryptedBlockHex,
        String srcPan,
        PinBlockFormat srcFormat,
        String srcKeyHex,
        String srcKeyId,
        String dstPan,
        PinBlockFormat dstFormat,
        String dstKeyHex,
        String dstKeyId
) {
}
