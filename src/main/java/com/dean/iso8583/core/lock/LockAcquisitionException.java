package com.dean.iso8583.core.lock;

/**
 * Thrown when a distributed lock cannot be acquired within the designated timeout period.
 */
public class LockAcquisitionException extends RuntimeException {

    public LockAcquisitionException(String message) {
        super(message);
    }

    public LockAcquisitionException(String message, Throwable cause) {
        super(message, cause);
    }
}
