package com.github.feng111627.idempotentwritegatewayjava.idempotency;

import java.time.Duration;

/**
 * 分状态保留策略：PROCESSING / SUCCEEDED / FAILED 三种记录各自的保留时长，
 * 以及内存存储的容量上限。
 *
 * <p>{@code legacyMode=true} 表示沿用老配置 {@code idempotency.ttl}：
 * 三段时长相同，且状态流转时过期时刻不重新锚定（含义与升级前完全一致）。
 */
public final class RetentionPolicy {

    private final Duration processingTtl;
    private final Duration succeededTtl;
    private final Duration failedTtl;
    private final int maxRecords;
    private final boolean legacyMode;

    public RetentionPolicy(Duration processingTtl,
                           Duration succeededTtl,
                           Duration failedTtl,
                           int maxRecords,
                           boolean legacyMode) {
        this.processingTtl = processingTtl;
        this.succeededTtl = succeededTtl;
        this.failedTtl = failedTtl;
        this.maxRecords = maxRecords;
        this.legacyMode = legacyMode;
    }

    public Duration getProcessingTtl() {
        return processingTtl;
    }

    public Duration getSucceededTtl() {
        return succeededTtl;
    }

    public Duration getFailedTtl() {
        return failedTtl;
    }

    public int getMaxRecords() {
        return maxRecords;
    }

    public boolean isLegacyMode() {
        return legacyMode;
    }
}
