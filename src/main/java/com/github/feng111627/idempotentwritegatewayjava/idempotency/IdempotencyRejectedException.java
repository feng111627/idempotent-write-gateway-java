package com.github.feng111627.idempotentwritegatewayjava.idempotency;

/**
 * 幂等校验拒绝请求时抛出，携带明确的拒绝原因。
 */
public class IdempotencyRejectedException extends RuntimeException {

    private final RejectionReason reason;

    public IdempotencyRejectedException(RejectionReason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public RejectionReason getReason() {
        return reason;
    }
}
