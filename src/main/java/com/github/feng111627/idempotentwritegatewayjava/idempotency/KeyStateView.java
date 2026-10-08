package com.github.feng111627.idempotentwritegatewayjava.idempotency;

/**
 * 幂等键状态查询的只读结果。
 *
 * @param key                 幂等键
 * @param state               对外状态
 * @param retryAfterSeconds   建议多久后再来查询/重试（秒），无建议时为 null
 * @param expiresAtEpochMillis 记录过期的 epoch 毫秒时间戳，无记录时为 null
 */
public record KeyStateView(String key,
                           KeyState state,
                           Integer retryAfterSeconds,
                           Long expiresAtEpochMillis) {
}
