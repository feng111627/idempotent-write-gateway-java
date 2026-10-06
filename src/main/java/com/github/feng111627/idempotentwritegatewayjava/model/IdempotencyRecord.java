package com.github.feng111627.idempotentwritegatewayjava.model;

/**
 * 一条幂等记录。不可变对象，状态流转通过 completed(...) 生成新记录。
 */
public record IdempotencyRecord(
        String idempotencyKey,
        String requestFingerprint,
        IdempotencyState state,
        Integer httpStatus,
        String responseBody,
        String errorCode,
        String errorMessage,
        long createdAtMillis,
        long expiresAtMillis) {

    public static IdempotencyRecord processing(String idempotencyKey, String requestFingerprint,
                                               long nowMillis, long ttlMillis) {
        return new IdempotencyRecord(idempotencyKey, requestFingerprint, IdempotencyState.PROCESSING,
                null, null, null, null, nowMillis, nowMillis + ttlMillis);
    }

    /**
     * 生成终态记录（SUCCESS / FAILED）。沿用首请的创建时间，按终态 TTL 重新计算过期时间。
     */
    public IdempotencyRecord completed(IdempotencyState finalState, Integer httpStatus, String responseBody,
                                       String errorCode, String errorMessage,
                                       long nowMillis, long ttlMillis) {
        if (finalState == IdempotencyState.PROCESSING) {
            throw new IllegalArgumentException("终态不能是 PROCESSING");
        }
        return new IdempotencyRecord(idempotencyKey, requestFingerprint, finalState,
                httpStatus, responseBody, errorCode, errorMessage, createdAtMillis, nowMillis + ttlMillis);
    }

    public boolean expired(long nowMillis) {
        return nowMillis >= expiresAtMillis;
    }
}
