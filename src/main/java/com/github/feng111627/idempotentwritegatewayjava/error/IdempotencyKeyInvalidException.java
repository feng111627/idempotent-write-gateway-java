package com.github.feng111627.idempotentwritegatewayjava.error;

/**
 * 幂等键格式非法（400 IDEMPOTENCY_KEY_INVALID）。
 */
public class IdempotencyKeyInvalidException extends ApiException {
    public IdempotencyKeyInvalidException(String key) {
        super(400, "IDEMPOTENCY_KEY_INVALID", "幂等键格式非法: " + key);
    }
}
