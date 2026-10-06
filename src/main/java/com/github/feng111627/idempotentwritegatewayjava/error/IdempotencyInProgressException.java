package com.github.feng111627.idempotentwritegatewayjava.error;

import java.time.Duration;

/**
 * 同键并发：首请仍在执行（409 IDEMPOTENCY_IN_PROGRESS）。
 */
public class IdempotencyInProgressException extends ApiException {

    private final Duration retryAfter;

    public IdempotencyInProgressException(String key, Duration retryAfter) {
        super(409, "IDEMPOTENCY_IN_PROGRESS", "该幂等键的请求正在处理中: " + key);
        this.retryAfter = retryAfter;
    }

    public Duration getRetryAfter() { return retryAfter; }
}
