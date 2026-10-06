package com.github.feng111627.idempotentwritegatewayjava.idempotency;

/**
 * 幂等校验拒绝请求的原因，用于向调用方明确区分失败类型。
 */
public enum RejectionReason {

    /** 请求头中未携带幂等键。 */
    MISSING_KEY,

    /** 幂等键格式非法（为空串、长度越界或包含非法字符）。 */
    INVALID_KEY_FORMAT,

    /** 同一幂等键关联了与首次提交不一致的请求内容。 */
    REQUEST_MISMATCH,

    /** 幂等记录底层存储不可用或写入失败，按 fail-closed 策略拒绝。 */
    STORAGE_UNAVAILABLE
}
