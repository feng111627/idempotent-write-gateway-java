package com.github.feng111627.idempotentwritegatewayjava.error;

/**
 * 同一幂等键携带了不同的请求内容（422 IDEMPOTENCY_KEY_CONFLICT）。
 */
public class IdempotencyConflictException extends ApiException {
    public IdempotencyConflictException(String key) {
        super(422, "IDEMPOTENCY_KEY_CONFLICT", "幂等键已用于不同的请求内容: " + key);
    }
}
