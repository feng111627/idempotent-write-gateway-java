package com.github.feng111627.idempotentwritegatewayjava.idempotency;

/**
 * 幂等记录底层存储不可用或读写失败时抛出。
 */
public class IdempotencyStorageException extends RuntimeException {

    public IdempotencyStorageException(String message) {
        super(message);
    }

    public IdempotencyStorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
