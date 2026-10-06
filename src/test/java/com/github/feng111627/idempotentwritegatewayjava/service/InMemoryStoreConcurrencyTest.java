package com.github.feng111627.idempotentwritegatewayjava.service;

import com.github.feng111627.idempotentwritegatewayjava.store.AcquireResult;
import com.github.feng111627.idempotentwritegatewayjava.store.InMemoryIdempotencyStore;
import com.github.feng111627.idempotentwritegatewayjava.support.TestClock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 存储层并发互斥压测：不做时序假设，验证“同键抢占”在高并发下严格只有一次 Acquired。
 */
class InMemoryStoreConcurrencyTest {

    @Test
    @DisplayName("8 线程 × 500 次同键抢占（含随机错开）：Acquired 恰为 1，其余全部 InProgress")
    void onlyOneAcquireWinsUnderContention() throws Exception {
        TestClock clock = new TestClock();
        InMemoryIdempotencyStore store = new InMemoryIdempotencyStore(clock);
        int threads = 8;
        int perThread = 500;
        long processingTtl = 120_000L;

        AtomicInteger acquired = new AtomicInteger();
        AtomicInteger inProgress = new AtomicInteger();
        AtomicInteger other = new AtomicInteger();
        Set<String> acquiredByThreads = ConcurrentHashMap.newKeySet();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);

        try {
            for (int t = 0; t < threads; t++) {
                final String threadName = "t" + t;
                pool.submit(() -> {
                    try {
                        start.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    for (int i = 0; i < perThread; i++) {
                        AcquireResult r = store.acquire("hot-key", "fp-1", clock.millis(), processingTtl);
                        if (r instanceof AcquireResult.Acquired) {
                            acquired.incrementAndGet();
                            acquiredByThreads.add(threadName + ":" + i);
                        } else if (r instanceof AcquireResult.InProgress) {
                            inProgress.incrementAndGet();
                        } else {
                            other.incrementAndGet();
                        }
                        if ((i & 7) == 0) {
                            clock.advanceMillis(1); // 制造一些时间扰动，但不越过 TTL
                        }
                    }
                });
            }
            start.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS), "压测线程应在 30s 内完成");
        } finally {
            pool.shutdownNow();
        }

        assertEquals(1, acquired.get(), "同一幂等键在整个压测期间只能被抢占成功一次");
        assertEquals(threads * perThread - 1L, inProgress.get(), "其余全部应是处理中");
        assertEquals(0, other.get(), "不应出现冲突/重放等其它结果");
        assertEquals(1, acquiredByThreads.size(), "抢占成功者只能来自一个线程上下文");
    }

    @Test
    @DisplayName("不同幂等键互不阻塞，各自只被抢占一次")
    void differentKeysDoNotInterfere() throws Exception {
        TestClock clock = new TestClock();
        InMemoryIdempotencyStore store = new InMemoryIdempotencyStore(clock);
        int keys = 20;
        int threadsPerKey = 4;
        AtomicInteger acquired = new AtomicInteger();

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(keys * threadsPerKey);
        Set<String> done = ConcurrentHashMap.newKeySet();
        try {
            for (int k = 0; k < keys; k++) {
                final String key = "key-" + k + "-abcdef";
                for (int t = 0; t < threadsPerKey; t++) {
                    pool.submit(() -> {
                        try {
                            start.await();
                            if (store.acquire(key, "fp", clock.millis(), 120_000L)
                                    instanceof AcquireResult.Acquired) {
                                acquired.incrementAndGet();
                                done.add(key);
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    });
                }
            }
            start.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }

        assertEquals(keys, acquired.get(), "每个键恰好一次抢占成功");
        assertEquals(keys, done.size());
        assertEquals(keys, new HashSet<>(done).size());
    }
}
