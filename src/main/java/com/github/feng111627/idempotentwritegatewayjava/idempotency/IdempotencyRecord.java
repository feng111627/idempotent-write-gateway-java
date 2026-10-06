package com.github.feng111627.idempotentwritegatewayjava.idempotency;

/**
 * 一条幂等记录：记录某个幂等键当前所处的状态、关联的请求指纹，
 * 成功时固化的响应，以及记录的创建与过期时间。
 *
 * <p>记录为不可变对象；状态流转通过 {@code toSucceeded} / {@code toFailed}
 * 产出新实例，避免并发下读到半成品状态。
 */
public final class IdempotencyRecord {

    private final String key;
    private final IdempotencyStatus status;
    private final String requestHash;
    private final int httpStatus;
    private final String contentType;
    private final String body;
    private final long createdAtMillis;
    private final long expiresAtMillis;

    private IdempotencyRecord(String key,
                              IdempotencyStatus status,
                              String requestHash,
                              int httpStatus,
                              String contentType,
                              String body,
                              long createdAtMillis,
                              long expiresAtMillis) {
        this.key = key;
        this.status = status;
        this.requestHash = requestHash;
        this.httpStatus = httpStatus;
        this.contentType = contentType;
        this.body = body;
        this.createdAtMillis = createdAtMillis;
        this.expiresAtMillis = expiresAtMillis;
    }

    /** 创建一条 PROCESSING 状态的新记录，过期时间 = now + ttlMillis。 */
    public static IdempotencyRecord processing(String key, String requestHash,
                                               long now, long ttlMillis) {
        return new IdempotencyRecord(key, IdempotencyStatus.PROCESSING, requestHash,
                0, null, null, now, safeAdd(now, ttlMillis));
    }

    /** 流转为 SUCCEEDED，并固化首次执行的响应；过期时间不变。 */
    public IdempotencyRecord toSucceeded(int httpStatus, String contentType, String body) {
        return new IdempotencyRecord(key, IdempotencyStatus.SUCCEEDED, requestHash,
                httpStatus, contentType, body, createdAtMillis, expiresAtMillis);
    }

    /** 流转为 FAILED，清空响应；过期时间不变（TTL 内允许同内容重试）。 */
    public IdempotencyRecord toFailed() {
        return new IdempotencyRecord(key, IdempotencyStatus.FAILED, requestHash,
                0, null, null, createdAtMillis, expiresAtMillis);
    }

    /** 记录是否已过期（过期后同键可被重新抢占）。 */
    public boolean isExpired(long now) {
        return now >= expiresAtMillis;
    }

    public String getKey() {
        return key;
    }

    public IdempotencyStatus getStatus() {
        return status;
    }

    public String getRequestHash() {
        return requestHash;
    }

    public int getHttpStatus() {
        return httpStatus;
    }

    public String getContentType() {
        return contentType;
    }

    public String getBody() {
        return body;
    }

    public long getCreatedAtMillis() {
        return createdAtMillis;
    }

    public long getExpiresAtMillis() {
        return expiresAtMillis;
    }

    private static long safeAdd(long base, long delta) {
        long sum = base + delta;
        return sum < base ? Long.MAX_VALUE : sum;
    }
}
