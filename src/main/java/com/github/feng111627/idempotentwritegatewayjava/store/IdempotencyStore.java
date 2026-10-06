package com.github.feng111627.idempotentwritegatewayjava.store;

import com.github.feng111627.idempotentwritegatewayjava.model.IdempotencyRecord;

/**
 * 幂等记录存储抽象。实现需保证 acquire 的原子性（并发互斥）。
 * 底层存储不可用时抛 RuntimeException，由上层统一转为 StorageUnavailableException。
 */
public interface IdempotencyStore {

    /**
     * 原子抢占：同键首请写入 PROCESSING；并发请求返回 InProgress；
     * 同键同指纹重放返回 Completed；同键不同指纹返回 Conflict；
     * 已过期记录视为不存在，可被新首请重新抢占。
     */
    AcquireResult acquire(String idempotencyKey, String requestFingerprint,
                          long nowMillis, long processingTtlMillis);

    /**
     * 首请执行结束后写入终态（SUCCESS / FAILED）。
     */
    void complete(IdempotencyRecord record, long nowMillis);
}
