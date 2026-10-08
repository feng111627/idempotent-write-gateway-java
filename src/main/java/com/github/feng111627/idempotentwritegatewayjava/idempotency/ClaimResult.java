package com.github.feng111627.idempotentwritegatewayjava.idempotency;

/**
 * 抢占幂等键的判定结果，由存储层原子产出：
 * <ul>
 *     <li>{@link Granted} 当前请求抢到执行权，应当真正执行业务；</li>
 *     <li>{@link Replaying} 首次请求已成功，直接重放已固化的响应；</li>
 *     <li>{@link Processing} 首次请求仍在执行，调用方应稍后重试；</li>
 *     <li>{@link Retryable} 上次业务执行失败（记录仍在 TTL 内），本次重新抢占成功；</li>
 *     <li>{@link Rejected} 请求内容不一致等原因被拒绝。</li>
 * </ul>
 */
public sealed interface ClaimResult {

    /** 当前请求获得执行权（首次提交）。 */
    record Granted() implements ClaimResult {
    }

    /** 上次业务失败后重试，本次重新获得执行权。 */
    record Retryable() implements ClaimResult {
    }

    /** 命中已成功的记录，重放其响应。 */
    record Replaying(IdempotencyRecord record) implements ClaimResult {
    }

    /**
     * 同键请求仍在处理中。
     *
     * @param retryAfterSeconds 建议客户端多久后再来查询/重试（秒，恒 ≥ 1）
     */
    record Processing(int retryAfterSeconds) implements ClaimResult {
    }

    /** 因明确原因被拒绝。 */
    record Rejected(RejectionReason reason) implements ClaimResult {
    }
}
