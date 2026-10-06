package com.github.feng111627.idempotentwritegatewayjava.idempotency;

import java.time.Duration;

/**
 * 幂等记录存储抽象。claim 的实现必须是原子的：
 * 同一个幂等键的并发请求，最多只能有一个得到执行权。
 */
public interface IdempotencyStore {

    /**
     * 原子地抢占一个幂等键。
     *
     * @param key           幂等键
     * @param requestHash   请求内容指纹
     * @param ttl           记录存活时间（从抢占成功时刻起算）
     * @return 抢占结果
     * @throws IdempotencyStorageException 存储不可用时抛出
     */
    ClaimResult claim(String key, String requestHash, Duration ttl);

    /**
     * 业务执行成功后固化响应（状态置为 SUCCEEDED）。
     */
    void complete(String key, int statusCode, String contentType, String body);

    /**
     * 业务执行失败后标记失败（状态置为 FAILED，允许后续重试）。
     */
    void fail(String key);
}
