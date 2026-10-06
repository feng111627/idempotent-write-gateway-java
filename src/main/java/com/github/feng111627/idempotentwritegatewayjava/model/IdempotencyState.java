package com.github.feng111627.idempotentwritegatewayjava.model;

/**
 * 幂等记录状态：处理中 / 成功 / 失败。
 */
public enum IdempotencyState {
    PROCESSING,
    SUCCESS,
    FAILED
}
