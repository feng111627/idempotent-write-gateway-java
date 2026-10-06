package com.github.feng111627.idempotentwritegatewayjava.error;

/**
 * 幂等存储不可用（503 IDEMPOTENCY_STORAGE_UNAVAILABLE）。
 */
public class StorageUnavailableException extends ApiException {
    public StorageUnavailableException(String operation, Throwable cause) {
        super(503, "IDEMPOTENCY_STORAGE_UNAVAILABLE",
                "幂等存储不可用，操作=" + operation, cause);
    }
}
