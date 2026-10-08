package com.github.feng111627.idempotentwritegatewayjava.idempotency;

import java.time.Duration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 幂等模块装配：注册配置属性、分状态保留策略与存储 Bean。
 *
 * <p>当前默认装配 {@link InMemoryIdempotencyStore}（单实例）。
 * 替换存储时只需提供另一个 {@link IdempotencyStore} Bean（如 Redis 实现）即可。
 */
@Configuration
@EnableConfigurationProperties(IdempotencyProperties.class)
public class IdempotencyConfig {

    /**
     * 根据配置构建保留策略。配置不合法时抛出 {@link IllegalStateException}，
     * 让应用启动失败并明确指出问题项。
     */
    @Bean
    public RetentionPolicy retentionPolicy(IdempotencyProperties properties) {
        return buildPolicy(properties);
    }

    /** 使用默认分状态时长构建策略（主要供测试与非 Spring 场景使用）。 */
    public static RetentionPolicy defaultPolicy() {
        return buildPolicy(new IdempotencyProperties());
    }

    public static RetentionPolicy buildPolicy(IdempotencyProperties p) {
        boolean legacy = p.isTtlExplicitlySet() && !p.isNewStyleExplicitlySet();
        if (p.isTtlExplicitlySet() && p.isNewStyleExplicitlySet()) {
            throw new IllegalStateException(
                    "幂等配置不合法：idempotency.ttl 与分状态配置 "
                    + "(idempotency.processing-ttl/succeeded-ttl/failed-ttl/max-records) "
                    + "不能同时使用，请二选一：继续用 idempotency.ttl，或迁移到分状态配置");
        }
        Duration processing;
        Duration succeeded;
        Duration failed;
        int maxRecords;
        if (legacy) {
            requirePositive("idempotency.ttl", p.getTtl());
            processing = p.getTtl();
            succeeded = p.getTtl();
            failed = p.getTtl();
            // 老用法不改变容量默认值；容量是全新能力，不影响老语义
            maxRecords = p.getMaxRecords();
        } else {
            requirePositive("idempotency.processing-ttl", p.getProcessingTtl());
            requirePositive("idempotency.succeeded-ttl", p.getSucceededTtl());
            requirePositive("idempotency.failed-ttl", p.getFailedTtl());
            processing = p.getProcessingTtl();
            succeeded = p.getSucceededTtl();
            failed = p.getFailedTtl();
            maxRecords = p.getMaxRecords();
        }
        if (maxRecords <= 0) {
            throw new IllegalStateException(
                    "幂等配置不合法：idempotency.max-records 必须是正整数（当前值="
                    + maxRecords + "）");
        }
        if (p.getMaxKeyLength() <= 0) {
            throw new IllegalStateException(
                    "幂等配置不合法：idempotency.max-key-length 必须是正整数（当前值="
                    + p.getMaxKeyLength() + "）");
        }
        return new RetentionPolicy(processing, succeeded, failed, maxRecords, legacy);
    }

    private static void requirePositive(String name, Duration value) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalStateException(
                    "幂等配置不合法：" + name + " 必须是正数时长（当前值=" + value
                    + "），请检查格式（如 30s、5m、24h）且不能为 0 或负数");
        }
    }

    @Bean
    public IdempotencyStore idempotencyStore(RetentionPolicy retentionPolicy) {
        return new InMemoryIdempotencyStore(retentionPolicy);
    }
}
