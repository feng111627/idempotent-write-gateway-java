package com.github.feng111627.idempotentwritegatewayjava.support;

import com.github.feng111627.idempotentwritegatewayjava.model.IdempotencyRecord;
import com.github.feng111627.idempotentwritegatewayjava.store.AcquireResult;
import com.github.feng111627.idempotentwritegatewayjava.store.IdempotencyStore;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 测试用故障存储：可按次数让 acquire / complete 抛异常，模拟存储不可用。
 * 内部委托真实的内存语义逻辑（用 InMemoryIdempotencyStore 包装）。
 */
public class FaultyIdempotencyStore implements IdempotencyStore {

    private final IdempotencyStore delegate;
    private final AtomicInteger acquireFailuresRemaining = new AtomicInteger();
    private final AtomicInteger completeFailuresRemaining = new AtomicInteger();

    public FaultyIdempotencyStore(IdempotencyStore delegate) {
        this.delegate = delegate;
    }


    /** 重置故障注入计数；若委托是内存存储则同时清空记录。 */
    public void reset() {
        acquireFailuresRemaining.set(0);
        completeFailuresRemaining.set(0);
        if (delegate instanceof com.github.feng111627.idempotentwritegatewayjava.store.InMemoryIdempotencyStore mem) {
            mem.clear();
        }
    }

    public void failNextAcquire(int times) {
        acquireFailuresRemaining.set(times);
    }

    public void failNextComplete(int times) {
        completeFailuresRemaining.set(times);
    }

    @Override
    public AcquireResult acquire(String idempotencyKey, String requestFingerprint,
                                 long nowMillis, long processingTtlMillis) {
        if (acquireFailuresRemaining.getAndDecrement() > 0) {
            throw new RuntimeException("模拟存储不可用: acquire");
        }
        return delegate.acquire(idempotencyKey, requestFingerprint, nowMillis, processingTtlMillis);
    }

    @Override
    public void complete(IdempotencyRecord record, long nowMillis) {
        if (completeFailuresRemaining.getAndDecrement() > 0) {
            throw new RuntimeException("模拟存储不可用: complete");
        }
        delegate.complete(record, nowMillis);
    }
}
