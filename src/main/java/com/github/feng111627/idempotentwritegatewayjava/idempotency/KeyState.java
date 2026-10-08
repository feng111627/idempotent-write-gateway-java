package com.github.feng111627.idempotentwritegatewayjava.idempotency;

/**
 * 对外可见的幂等键状态（查询入口用）。
 */
public enum KeyState {

    /** 该键从未提交过（或记录已被容量淘汰），可作为全新提交。 */
    NOT_FOUND,

    /** 首次请求仍在处理中，暂不能拿到最终结果。 */
    PROCESSING,

    /** 之前已成功过，在保留窗口内提交会重放首次结果。 */
    SUCCEEDED,

    /** 之前失败过，仍在失败保留窗口内，可用相同内容重试。 */
    FAILED_RETRYABLE,

    /** 记录已超过保留窗口，同键可当作新提交重新处理。 */
    EXPIRED
}
