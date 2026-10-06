package com.github.feng111627.idempotentwritegatewayjava.support;

import com.github.feng111627.idempotentwritegatewayjava.store.InMemoryIdempotencyStore;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.Clock;

/**
 * 集成测试装配：用可控时钟替换系统时钟，并把内存存储包成可注入故障的存储。
 */
@TestConfiguration
public class IdempotencyTestConfig {

    @Bean
    @Primary
    public Clock testClock() {
        return new TestClock();
    }

    @Bean
    @Primary
    public FaultyIdempotencyStore faultyIdempotencyStore(Clock clock) {
        return new FaultyIdempotencyStore(new InMemoryIdempotencyStore(clock));
    }
}
