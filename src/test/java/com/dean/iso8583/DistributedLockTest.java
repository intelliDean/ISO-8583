package com.dean.iso8583;

import com.dean.iso8583.core.lock.DistributedLockService;
import com.dean.iso8583.core.lock.InMemoryDistributedLockService;
import com.dean.iso8583.core.lock.LockAcquisitionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("DistributedLockService & Concurrency Tests")
class DistributedLockTest {

    private DistributedLockService lockService;

    @BeforeEach
    void setUp() {
        lockService = new InMemoryDistributedLockService();
    }

    @Test
    @DisplayName("Should acquire and release lock with valid token")
    void testAcquireAndRelease() {
        String lockKey = "lock:stan:000123";
        String token = lockService.tryAcquire(lockKey, 1000, 5000);

        assertNotNull(token);
        assertTrue(lockService.release(lockKey, token));
    }

    @Test
    @DisplayName("Should block concurrent acquisition on same key until released")
    void testContentionOnSameKey() throws InterruptedException {
        String lockKey = "lock:pan:453201******1234";
        String token1 = lockService.tryAcquire(lockKey, 500, 2000);
        assertNotNull(token1);

        // Second acquisition attempt should fail within short timeout
        String token2 = lockService.tryAcquire(lockKey, 100, 2000);
        assertNull(token2, "Second lock acquisition should fail while first is held");

        // Release first lock
        assertTrue(lockService.release(lockKey, token1));

        // Now second acquisition should succeed
        String token3 = lockService.tryAcquire(lockKey, 500, 2000);
        assertNotNull(token3, "Lock acquisition should succeed after release");
        assertTrue(lockService.release(lockKey, token3));
    }

    @Test
    @DisplayName("Should execute critical section atomically across multiple threads")
    void testExecuteWithLockConcurrency() throws InterruptedException {
        String lockKey = "lock:terminal:TERM0001";
        int threadCount = 10;
        int incrementsPerThread = 50;
        AtomicInteger counter = new AtomicInteger(0);

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    for (int j = 0; j < incrementsPerThread; j++) {
                        lockService.executeWithLock(lockKey, 2000, 1000, () -> {
                            int current = counter.get();
                            try { Thread.sleep(1); } catch (InterruptedException ignored) {}
                            counter.set(current + 1);
                            return null;
                        });
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        executor.shutdown();

        assertEquals(threadCount * incrementsPerThread, counter.get(),
                "Counter should exactly equal total increments without race conditions");
    }

    @Test
    @DisplayName("Should fail with LockAcquisitionException when wait timeout exceeded")
    void testLockAcquisitionException() {
        String lockKey = "lock:blocked";
        String token = lockService.tryAcquire(lockKey, 1000, 10000);
        assertNotNull(token);

        assertThrows(LockAcquisitionException.class, () ->
                lockService.executeWithLock(lockKey, 100, 1000, () -> "failed")
        );

        lockService.release(lockKey, token);
    }
}
