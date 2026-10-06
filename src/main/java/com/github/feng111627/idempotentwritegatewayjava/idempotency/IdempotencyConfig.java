package com.github.feng111627.idempotentwritegatewayjava.idempotency;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 幂等模块装配：注册配置属性与存储 Bean。
 *
 * <p>当前默认装配 {@link InMemoryIdempotencyStore}（单实例）。
 * 替换存储时只需提供另一个 {@link IdempotencyStore} Bean（如 Redis 实现）即可。
 */
@Configuration
@EnableConfigurationProperties(IdempotencyProperties.class)
public class IdempotencyConfig {

    @Bean
    public IdempotencyStore idempotencyStore() {
        return new InMemoryIdempotencyStore();
    }
}
