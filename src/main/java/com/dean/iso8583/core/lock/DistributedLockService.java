package com.dean.iso8583.core.lock;

import java.util.function.Supplier;

/**
 * Distributed Lock Service Interface for the ISO 8583 Engine.
 *
 * <p>Coordinates state mutations across multiple horizontally scaled payment switch nodes
 * to prevent double-authorisations, simultaneous chargeback conflicts, and race conditions
 * on cardholder accounts and terminal STAN counters.</p>
 */
public interface DistributedLockService {

    /**
     * Attempts to acquire a distributed lock on the specified key.
     *
     * @param lockKey       unique key string (e.g. "lock:stan:000123:pan:453201******1234")
     * @param waitTimeoutMs maximum time in ms to wait for the lock
     * @param leaseTimeMs   maximum time in ms before lock auto-expires to prevent deadlocks
     * @return lock token (non-null) if acquired, or null/empty if acquisition timed out
     */
    String tryAcquire(String lockKey, long waitTimeoutMs, long leaseTimeMs);

    /**
     * Releases a previously acquired lock if the token matches.
     *
     * @param lockKey   lock key string
     * @param lockToken token returned by {@link #tryAcquire}
     * @return true if successfully released, false otherwise
     */
    boolean release(String lockKey, String lockToken);

    /**
     * Executes a critical section under a distributed lock, guaranteeing automatic release.
     *
     * @param lockKey       lock key string
     * @param waitTimeoutMs wait timeout in ms
     * @param leaseTimeMs   lease timeout in ms
     * @param action        action to execute
     * @param <T>           return type
     * @return result of the action
     * @throws LockAcquisitionException if lock cannot be acquired within wait timeout
     */
    <T> T executeWithLock(String lockKey, long waitTimeoutMs, long leaseTimeMs, Supplier<T> action);
}
