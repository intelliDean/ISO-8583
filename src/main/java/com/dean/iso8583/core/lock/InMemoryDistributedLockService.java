package com.dean.iso8583.core.lock;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * High-performance thread-safe in-memory implementation of {@link DistributedLockService}.
 *
 * <p>Serves as the default locking engine for single-instance deployments, integration
 * tests, and fallback when Redis is unreachable.</p>
 */
@Slf4j
@Service
public class InMemoryDistributedLockService implements DistributedLockService {

    private record LockHolder(ReentrantLock lock, String token, long expiryTimeMs) {}

    private final ConcurrentHashMap<String, LockHolder> locks = new ConcurrentHashMap<>();

    @Override
    public String tryAcquire(String lockKey, long waitTimeoutMs, long leaseTimeMs) {
        String token = UUID.randomUUID().toString();
        long deadline = System.currentTimeMillis() + waitTimeoutMs;

        while (System.currentTimeMillis() <= deadline) {
            cleanExpiredLocks();

            if (locks.get(lockKey) == null) {
                AcquireAttempt attempt = attemptAcquire(lockKey, token, deadline, leaseTimeMs);

                if (attempt.status() == AttemptStatus.ACQUIRED) {
                    return attempt.token();
                } else if (attempt.status() == AttemptStatus.INTERRUPTED) {
                    return null;
                }
                // AttemptStatus.RETRY: another thread won the race, loop will sleep and retry
            }

            if (!sleepBriefly()) return null;

        }

        log.warn("Distributed Lock Acquisition Timed Out: Key='{}' WaitTimeout={}ms", lockKey, waitTimeoutMs);
        return null;
    }

    /**
     * Attempts a single lock-and-register cycle: blocks on the local {@link ReentrantLock}
     * up to the remaining time until deadline, then races to register the holder in the map.
     */
    private AcquireAttempt attemptAcquire(String lockKey, String token, long deadline, long leaseTimeMs) {
        ReentrantLock lock = new ReentrantLock();
        try {
            long remaining = Math.max(1, deadline - System.currentTimeMillis());
            if (!lock.tryLock(remaining, TimeUnit.MILLISECONDS)) {
                return AcquireAttempt.retry();
            }
            return registerLockHolder(lockKey, token, leaseTimeMs, lock);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return AcquireAttempt.interrupted();
        }
    }

    /**
     * Publishes the acquired local lock into the shared map. If another thread won the race
     * for {@code lockKey} in the meantime, releases the local lock and signals retry.
     */
    private AcquireAttempt registerLockHolder(
            String lockKey,
            String token,
            long leaseTimeMs,
            ReentrantLock lock
    ) {
        long expiry = System.currentTimeMillis() + leaseTimeMs;
        LockHolder holder = new LockHolder(lock, token, expiry);

        if (locks.putIfAbsent(lockKey, holder) != null) {
            lock.unlock();
            return AcquireAttempt.retry();
        }

        log.debug("Distributed Lock Acquired: Key='{}' Token='{}' Lease={}ms", lockKey, token, leaseTimeMs);
        return AcquireAttempt.acquired(token);
    }

    /**
     * Backs off briefly between acquisition attempts.
     *
     * @return false if interrupted while sleeping (caller should abort and return null)
     */
    private boolean sleepBriefly() {
        try {
            Thread.sleep(10);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    @Override
    public boolean release(String lockKey, String lockToken) {
        LockHolder holder = locks.get(lockKey);

        if (holder != null && holder.token().equals(lockToken)) {
            locks.remove(lockKey);
            try {
                if (holder.lock().isHeldByCurrentThread()) {
                    holder.lock().unlock();
                }
            } catch (IllegalMonitorStateException ignored) {}
            log.debug("Distributed Lock Released: Key='{}' Token='{}'", lockKey, lockToken);
            return true;
        }
        return false;
    }

    @Override
    public <T> T executeWithLock(String lockKey, long waitTimeoutMs, long leaseTimeMs, Supplier<T> action) {
        String token = tryAcquire(lockKey, waitTimeoutMs, leaseTimeMs);
        if (token == null) {
            throw new LockAcquisitionException("Failed to acquire lock for key: %s within %dms"
                    .formatted(lockKey, waitTimeoutMs));
        }
        try {
            return action.get();
        } finally {
            release(lockKey, token);
        }
    }

    private void cleanExpiredLocks() {
        long now = System.currentTimeMillis();
        locks.entrySet().removeIf(entry -> {
            if (entry.getValue().expiryTimeMs() < now) {
                log.warn("Distributed Lock Expired (TTL Exceeded): Key='{}'", entry.getKey());
                try {
                    if (entry.getValue().lock().isHeldByCurrentThread()) {
                        entry.getValue().lock().unlock();
                    }
                } catch (Exception ignored) {}
                return true;
            }
            return false;
        });
    }
}
