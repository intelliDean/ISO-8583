package com.dean.iso8583.core.lock;

record AcquireAttempt(
        AttemptStatus status,
        String token
) {

    public static AcquireAttempt acquired(String token) {
        return new AcquireAttempt(AttemptStatus.ACQUIRED, token);
    }

    public static AcquireAttempt retry() {
        return new AcquireAttempt(AttemptStatus.RETRY, null);
    }

    public static AcquireAttempt interrupted() {
        return new AcquireAttempt(AttemptStatus.INTERRUPTED, null);
    }
}
