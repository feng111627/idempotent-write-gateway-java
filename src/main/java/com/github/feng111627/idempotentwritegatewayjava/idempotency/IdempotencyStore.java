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
     * @param policy        分状态保留策略与容量上限
     * @return 抢占结果
     * @throws IdempotencyStorageException 存储不可用时抛出
     */
    ClaimResult claim(String key, String requestHash, RetentionPolicy policy);

    /**
     * 查询某幂等键当前的对外状态（不会触发淘汰，也不改变记录）。
     */
    KeyStateView query(String key);

    /**
     * 业务执行结束（成功固化 / 失败标记 / 兜底放弃）后，释放“处理中执行护栏”。
     *
     * <p>护栏的作用见 {@code InMemoryIdempotencyStore}：即使 PROCESSING 记录的
     * 保留窗口已到，只要首请的业务还在真实执行，同键的新请求也不能真正再执行业务。
     */
    void releaseInFlight(String key);

    /**
     * 业务执行成功后固化响应（状态置为 SUCCEEDED）。
     */
    void complete(String key, int statusCode, String contentType, String body);

    /**
     * 业务执行失败后标记失败（状态置为 FAILED，允许后续重试）。
     */
    void fail(String key);
}
