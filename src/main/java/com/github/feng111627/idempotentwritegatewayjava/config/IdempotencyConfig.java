package com.github.feng111627.idempotentwritegatewayjava.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Clock;

/**
 * 幂等模块 Bean 装配：系统时钟（测试可替换）、JSON 序列化器。
 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties(IdempotencyProperties.class)
public class IdempotencyConfig {

    @Bean
    public Clock systemClock() {
        return Clock.systemUTC();
    }

    @Bean
    public ObjectMapper idempotencyObjectMapper() {
        return new ObjectMapper();
    }
}
