package com.github.feng111627.idempotentwritegatewayjava.idempotency;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 幂等相关配置（前缀 idempotency）。
 *
 * <p>支持两种用法：
 * <ul>
 *     <li><b>新用法（推荐）</b>：分状态配置
 *         {@code idempotency.processing-ttl} /
 *         {@code idempotency.succeeded-ttl} /
 *         {@code idempotency.failed-ttl}，以及容量上限
 *         {@code idempotency.max-records}；</li>
 *     <li><b>老用法（兼容）</b>：只配 {@code idempotency.ttl}，
 *         三段保留时长都取该值，状态流转时过期点不重新锚定——与升级前行为完全一致。</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "idempotency")
public class IdempotencyProperties {

    /** 【老配置，兼容保留】统一存活时间。仅在未使用任何新配置项时生效，默认 24 小时。 */
    private Duration ttl = Duration.ofHours(24);

    /** 处理中记录的保留时长，默认 2 分钟。 */
    private Duration processingTtl = Duration.ofMinutes(2);

    /** 成功记录的保留时长，默认 24 小时。 */
    private Duration succeededTtl = Duration.ofHours(24);

    /** 失败记录的保留时长（重试窗口），默认 30 分钟。 */
    private Duration failedTtl = Duration.ofMinutes(30);

    /** 内存中最多保留的记录条数，默认 10000。 */
    private int maxRecords = 10_000;

    /** 幂等键允许的最大长度，默认 128。 */
    private int maxKeyLength = 128;

    /** 该字段仅用于判断老配置 idempotency.ttl 是否被用户显式设置。 */
    private boolean ttlExplicitlySet;

    /** 该字段仅用于判断是否有任意新配置项被显式设置。 */
    private boolean newStyleExplicitlySet;

    public Duration getTtl() {
        return ttl;
    }

    public void setTtl(Duration ttl) {
        this.ttl = ttl;
        this.ttlExplicitlySet = true;
    }

    public Duration getProcessingTtl() {
        return processingTtl;
    }

    public void setProcessingTtl(Duration processingTtl) {
        this.processingTtl = processingTtl;
        this.newStyleExplicitlySet = true;
    }

    public Duration getSucceededTtl() {
        return succeededTtl;
    }

    public void setSucceededTtl(Duration succeededTtl) {
        this.succeededTtl = succeededTtl;
        this.newStyleExplicitlySet = true;
    }

    public Duration getFailedTtl() {
        return failedTtl;
    }

    public void setFailedTtl(Duration failedTtl) {
        this.failedTtl = failedTtl;
        this.newStyleExplicitlySet = true;
    }

    public int getMaxRecords() {
        return maxRecords;
    }

    public void setMaxRecords(int maxRecords) {
        this.maxRecords = maxRecords;
        this.newStyleExplicitlySet = true;
    }

    public int getMaxKeyLength() {
        return maxKeyLength;
    }

    public void setMaxKeyLength(int maxKeyLength) {
        this.maxKeyLength = maxKeyLength;
    }

    public boolean isTtlExplicitlySet() {
        return ttlExplicitlySet;
    }

    public boolean isNewStyleExplicitlySet() {
        return newStyleExplicitlySet;
    }
}
