package com.github.feng111627.idempotentwritegatewayjava.idempotency;

/**
 * 幂等记录存储抽象。claim 的实现必须是原子的：
 * 同一个幂等键的并发请求，最多只能有一个得到执行权。
 */
public interface IdempotencyStore {

    /**
     * 原子地抢占一个幂等键。
     *
     * @param key         幂等键
     * @param requestHash 请求内容指纹
     * @param windows     三种状态各自的保留窗口（处理中记录按 processingTtl 起算，
     *                    后续状态切换由存储内部按窗口策略续算）
     * @return 抢占结果
     * @throws IdempotencyStorageException 存储不可用、或容量已满且无法安全腾位时抛出
     */
    ClaimResult claim(String key, String requestHash, RetentionWindows windows);

    /**
     * 业务执行成功后固化响应（状态置为 SUCCEEDED）。
     */
    void complete(String key, int statusCode, String contentType, String body);

    /**
     * 业务执行失败后标记失败（状态置为 FAILED，允许后续重试）。
     */
    void fail(String key);

    /**
     * 只读查询某个幂等键当前对外可见的状态，不产生任何副作用、不改变记录生命周期。
     */
    KeyStatusView inspect(String key);

    /**
     * 业务执行彻底结束（成功固化或失败标记都走完）后，释放本实例的“执行中”护栏。
     * 默认空实现；有执行护栏的存储（如内存实现）需要覆盖。
     */
    default void releaseInFlight(String key) {
    }
}
