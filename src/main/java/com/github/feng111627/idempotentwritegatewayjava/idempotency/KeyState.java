package com.github.feng111627.idempotentwritegatewayjava.idempotency;

/**
 * 对外可查询的幂等键状态（比存储内部的三态记录更细，区分了“没有/已过期/被淘汰”）。
 */
public enum KeyState {

    /** 该键从未提交过，或记录已过期/已被容量淘汰：应当作一次全新提交。 */
    NOT_FOUND,

    /** 首次请求仍在处理中，可按 {@code retryAfterSeconds} 提示稍后再来。 */
    PROCESSING,

    /** 之前已成功过：窗口内用同一个键、同一份请求体提交会直接重放首次响应。 */
    SUCCEEDED,

    /** 之前失败过、仍在重试窗口内：可用同一个键、同一份请求体重试。 */
    FAILED_RETRYABLE,

    /**
     * 处理中记录的保留窗口已过，但首次请求在本实例内仍未结束（in-flight）。
     * 此时仍不允许同键并发执行，以避免业务被做两遍；客户端应稍后再来。
     */
    PROCESSING_TIMEOUT
}
