package com.github.feng111627.idempotentwritegatewayjava.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.web.servlet.MockMvc;
import com.github.feng111627.idempotentwritegatewayjava.idempotency.IdempotencyStorageException;
import com.github.feng111627.idempotentwritegatewayjava.idempotency.IdempotencyStore;
import com.github.feng111627.idempotentwritegatewayjava.idempotency.InMemoryIdempotencyStore;
import com.github.feng111627.idempotentwritegatewayjava.order.OrderService;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 存储异常场景（抢占阶段）：claim 直接抛异常时必须 fail-closed ——
 * 返回 503 且业务一次都不执行，不能在“没有幂等兜底”的情况下放行业务。
 */
@SpringBootTest
@AutoConfigureMockMvc
class StorageClaimFailureIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(StorageClaimFailureIntegrationTest.class);

    /** claim 永远抛存储异常的存储。 */
    static class AlwaysFailingClaimStore extends InMemoryIdempotencyStore {
        @Override
        public com.github.feng111627.idempotentwritegatewayjava.idempotency.ClaimResult claim(
                String key, String requestHash, java.time.Duration ttl) {
            throw new IdempotencyStorageException("【模拟】Redis 连接被拒绝");
        }
    }

    @TestConfiguration
    static class FailingStoreConfig {
        @Bean
        @Primary
        IdempotencyStore failingStore() {
            return new AlwaysFailingClaimStore();
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private OrderService orderService;

    @BeforeEach
    void reset() {
        orderService.resetInvocationCount();
    }

    @Test
    @DisplayName("【存储异常-抢占前】存储不可用 → 503 + 明确错误码，业务零执行")
    void claimFailureReturns503AndSkipsBusiness() throws Exception {
        log.info("====== 场景: 抢占阶段存储不可用，fail-closed ======");
        String content = mockMvc.perform(post("/api/orders")
                        .header("Idempotency-Key", "storage-down-key")
                        .contentType("application/json")
                        .content("{\"productId\":\"p\",\"quantity\":1}"))
                .andExpect(status().isServiceUnavailable())
                .andReturn().getResponse().getContentAsString();

        assertTrue(content.contains("IDEMPOTENCY_STORAGE_UNAVAILABLE"),
                "错误响应必须能看出是存储不可用，实际: " + content);
        assertEquals(0, orderService.getInvocationCount(),
                "fail-closed：存储不可用时绝不允许业务执行");
        log.info("场景通过: 存储不可用返回 503/IDEMPOTENCY_STORAGE_UNAVAILABLE，业务执行=0");
    }
}
