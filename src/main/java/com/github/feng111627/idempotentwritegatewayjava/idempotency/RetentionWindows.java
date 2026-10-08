package com.github.feng111627.idempotentwritegatewayjava.idempotency;

import java.time.Duration;

/**
 * 三种状态各自的保留窗口，由 {@link IdempotencyProperties} 在启动校验后解析产出。
 *
 * @param processingTtl       处理中记录保留时长
 * @param succeededTtl        成功记录保留时长
 * @param failedTtl           失败记录保留时长
 * @param rebaseOnTransition 状态切换（PROCESSING→SUCCEEDED/FAILED、FAILED→PROCESSING）时
 *                           是否按新状态重新起算窗口：
 *                           分状态配置为 {@code true}（每段窗口各算各的）；
 *                           只配老的 {@code idempotency.ttl} 时为 {@code false}
 *                           （沿用旧语义：从抢占时刻起算的固定截止时间）。
 */
public record RetentionWindows(Duration processingTtl,
                               Duration succeededTtl,
                               Duration failedTtl,
                               boolean rebaseOnTransition) {

    /** 取指定状态对应的保留时长。 */
    public Duration forStatus(IdempotencyStatus status) {
        return switch (status) {
            case PROCESSING -> processingTtl;
            case SUCCEEDED -> succeededTtl;
            case FAILED -> failedTtl;
        };
    }
}
