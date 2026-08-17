package com.dean.iso8583.web.data.dto;

/**
 * Developer Note:
 * <p>Request body for POST /api/iso/emv/parse.
 *
 * <p>{@code de55Hex} is the hex-encoded value of ISO 8583 Data Element 55
 * (ICC System Related Data) as received in the authorization message.</p>
 * <p>This is a raw hex dump of the BER-TLV stream embedded in the ISO message.</p>
 *
 * Example:
 * <pre>{@code
 * {
 *   "de55Hex": "9F2608A1B2C3D4E5F6079F360200E29F10120110A000002A0000000000000000000000FF"
 * }
 * }</pre>
 *
 * @param de55Hex hex-encoded DE 55 BER-TLV payload
 */
public record EmvParseRequest(String de55Hex) {
}
