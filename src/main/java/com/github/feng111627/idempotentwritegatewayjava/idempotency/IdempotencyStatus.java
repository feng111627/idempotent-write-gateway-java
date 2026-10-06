package com.github.feng111627.idempotentwritegatewayjava.idempotency;

/**
 * 幂等记录的生命周期状态。
 */
public enum IdempotencyStatus {

    /** 业务正在执行中（已抢占到幂等键，尚未产出结果）。 */
    PROCESSING,

    /** 业务执行成功，结果已固化，可在过期前直接重放。 */
    SUCCEEDED,

    /** 业务执行失败，允许同一幂等键重试（请求内容必须一致）。 */
    FAILED
}
