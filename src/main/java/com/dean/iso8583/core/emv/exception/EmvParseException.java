package com.dean.iso8583.core.emv.exception;

/**
 * Developer Note:
 * Unchecked exception thrown when the BER-TLV parser encounters a structurally
 * invalid or truncated DE 55 payload.
 *
 * Enterprise Relevance:
 * Callers (IsoMessageProcessor, ISO8583ServiceImpl) should catch this exception
 * and translate it into a hard decline (DE 39 = '30' — Format Error) rather
 * than propagating it up to the HTTP layer. Malformed DE 55 data is a strong
 * indicator of a skimmed or tampered card reader.
 */
public class EmvParseException extends RuntimeException {

    public EmvParseException(String message) {
        super(message);
    }

    public EmvParseException(String message, Throwable cause) {
        super(message, cause);
    }
}
