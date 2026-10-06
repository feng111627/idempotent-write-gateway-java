package com.github.feng111627.idempotentwritegatewayjava.error;

/**
 * 缺少幂等键（400 IDEMPOTENCY_KEY_MISSING）。
 */
public class IdempotencyKeyMissingException extends ApiException {
    public IdempotencyKeyMissingException(String headerName) {
        super(400, "IDEMPOTENCY_KEY_MISSING", "缺少幂等键请求头: " + headerName);
    }
}
