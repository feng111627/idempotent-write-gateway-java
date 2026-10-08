package com.github.feng111627.idempotentwritegatewayjava.idempotency;

import java.time.Duration;
import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 幂等相关配置（前缀 idempotency）。
 *
 * <p>保留时长支持两种配置方式：
 * <ul>
 *     <li><b>分状态配置（推荐）</b>：{@code processing-ttl} / {@code succeeded-ttl} /
 *     {@code failed-ttl}，分别控制处理中、成功、失败三种记录的保留时长；
 *     窗口在状态切换时按新状态重新起算。</li>
 *     <li><b>老配置（兼容）</b>：{@code idempotency.ttl}，三段窗口都取该值，
 *     且保持旧语义——从抢占成功时刻起算的固定截止时间，状态切换不重算。</li>
 * </ul>
 *
 * <p>任何时长配成 0、负数或无法解析，或容量配成非正整数，应用都会在启动阶段直接失败，
 * 并给出明确的配置项名称与非法值。
 */
@ConfigurationProperties(prefix = "idempotency")
public class IdempotencyProperties {

    /** 处理中记录的默认保留时长：较短，避免键被长时间占住。 */
    public static final Duration DEFAULT_PROCESSING_TTL = Duration.ofMinutes(2);

    /** 失败记录的默认保留时长：留一个够用的重试窗口。 */
    public static final Duration DEFAULT_FAILED_TTL = Duration.ofMinutes(30);

    /** 成功记录的默认保留时长：较长，方便窗口内重放首次结果。 */
    public static final Duration DEFAULT_SUCCEEDED_TTL = Duration.ofHours(24);

    /** 处理中提示客户端的默认轮询间隔（当剩余时间无法估算时使用）。 */
    public static final Duration DEFAULT_PROCESSING_RETRY_AFTER = Duration.ofSeconds(2);

    /** 内存存储默认容量上限。 */
    public static final int DEFAULT_MAX_RECORDS = 10_000;

    /**
     * 老配置：统一的记录存活时间。仅为兼容旧版本保留；
     * 显式配置后，三段保留窗口都取该值，且沿用“抢占时刻固定截止”的旧语义。
     */
    private Duration ttl;

    /** 处理中（PROCESSING）记录的保留时长，从抢占成功时刻起算。 */
    private Duration processingTtl;

    /** 成功（SUCCEEDED）记录的保留时长，从业务成功固化时刻起算。 */
    private Duration succeededTtl;

    /** 失败（FAILED）记录的保留时长，从业务失败标记时刻起算。 */
    private Duration failedTtl;

    /** “处理中”响应里建议的轮询间隔；处理窗口已过但首请仍在跑时也用它作为兜底提示。 */
    private Duration processingRetryAfter = DEFAULT_PROCESSING_RETRY_AFTER;

    /** 幂等键允许的最大长度，默认 128。 */
    private int maxKeyLength = 128;

    /** 内存存储最多保留的记录条数，达到上限后按明确的淘汰规则腾位。 */
    private int maxRecords = DEFAULT_MAX_RECORDS;

    /** 校验并解析后的三段保留窗口（启动时算好，运行期直接使用）。 */
    private RetentionWindows retentionWindows;

    @PostConstruct
    void validateAndResolve() {
        boolean hasPerStateConfig = processingTtl != null || succeededTtl != null || failedTtl != null;
        Duration resolvedProcessing;
        Duration resolvedSucceeded;
        Duration resolvedFailed;
        boolean rebaseOnTransition;

        if (ttl != null) {
            requirePositive(ttl, "idempotency.ttl");
            // 老配置：三段窗口统一取 ttl；只要没有显式配置任何分段时长，就完全沿用旧语义
            //（从抢占时刻起算的固定截止时间，状态切换不重算）。
            resolvedProcessing = processingTtl != null ? processingTtl : ttl;
            resolvedSucceeded = succeededTtl != null ? succeededTtl : ttl;
            resolvedFailed = failedTtl != null ? failedTtl : ttl;
            rebaseOnTransition = hasPerStateConfig;
        } else {
            resolvedProcessing = processingTtl != null ? processingTtl : DEFAULT_PROCESSING_TTL;
            resolvedSucceeded = succeededTtl != null ? succeededTtl : DEFAULT_SUCCEEDED_TTL;
            resolvedFailed = failedTtl != null ? failedTtl : DEFAULT_FAILED_TTL;
            rebaseOnTransition = true;
        }

        requirePositive(resolvedProcessing, "idempotency.processing-ttl");
        requirePositive(resolvedSucceeded, "idempotency.succeeded-ttl");
        requirePositive(resolvedFailed, "idempotency.failed-ttl");
        requirePositive(processingRetryAfter, "idempotency.processing-retry-after");
        if (maxKeyLength <= 0) {
            throw new IllegalStateException(
                    "配置项 idempotency.max-key-length 非法：必须为正整数，当前值=" + maxKeyLength);
        }
        if (maxRecords <= 0) {
            throw new IllegalStateException(
                    "配置项 idempotency.max-records 非法：必须为正整数，当前值=" + maxRecords);
        }

        this.retentionWindows = new RetentionWindows(
                resolvedProcessing, resolvedSucceeded, resolvedFailed, rebaseOnTransition);
    }

    private static void requirePositive(Duration value, String property) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalStateException(
                    "配置项 " + property + " 非法：必须为正数时长（如 2m / 30m / 24h），当前值=" + value);
        }
    }

    /** 三段保留窗口（启动校验后必然可用）。 */
    public RetentionWindows getRetentionWindows() {
        if (retentionWindows == null) {
            // 未经过 Spring 生命周期直接 new 出来时（如单测），按默认值即时解析一次
            validateAndResolve();
        }
        return retentionWindows;
    }

    public Duration getTtl() {
        return ttl;
    }

    public void setTtl(Duration ttl) {
        this.ttl = ttl;
    }

    public Duration getProcessingTtl() {
        return processingTtl;
    }

    public void setProcessingTtl(Duration processingTtl) {
        this.processingTtl = processingTtl;
    }

    public Duration getSucceededTtl() {
        return succeededTtl;
    }

    public void setSucceededTtl(Duration succeededTtl) {
        this.succeededTtl = succeededTtl;
    }

    public Duration getFailedTtl() {
        return failedTtl;
    }

    public void setFailedTtl(Duration failedTtl) {
        this.failedTtl = failedTtl;
    }

    public Duration getProcessingRetryAfter() {
        return processingRetryAfter;
    }

    public void setProcessingRetryAfter(Duration processingRetryAfter) {
        this.processingRetryAfter = processingRetryAfter;
    }

    public int getMaxKeyLength() {
        return maxKeyLength;
    }

    public void setMaxKeyLength(int maxKeyLength) {
        this.maxKeyLength = maxKeyLength;
    }

    public int getMaxRecords() {
        return maxRecords;
    }

    public void setMaxRecords(int maxRecords) {
        this.maxRecords = maxRecords;
    }
}
