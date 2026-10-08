package com.github.feng111627.idempotentwritegatewayjava.idempotency;

/**
 * 幂等键状态查询结果（只读快照）。
 *
 * @param key               幂等键
 * @param state             对外状态
 * @param retryAfterSeconds 仅 PROCESSING / PROCESSING_TIMEOUT 有意义：建议客户端多久后再来（秒）
 * @param expiresAtEpochMillis 当前记录的过期时间（epoch 毫秒）；NOT_FOUND 时为 {@code 0}
 */
public record KeyStatusView(String key,
                            KeyState state,
                            int retryAfterSeconds,
                            long expiresAtEpochMillis) {

    public static KeyStatusView notFound(String key) {
        return new KeyStatusView(key, KeyState.NOT_FOUND, 0, 0L);
    }
}
