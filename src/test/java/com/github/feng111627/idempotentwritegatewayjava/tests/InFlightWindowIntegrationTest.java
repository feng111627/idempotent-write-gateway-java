package com.github.feng111627.idempotentwritegatewayjava.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
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
import org.springframework.test.web.servlet.MvcResult;
import com.github.feng111627.idempotentwritegatewayjava.idempotency.InMemoryIdempotencyStore;
import com.github.feng111627.idempotentwritegatewayjava.idempotency.RetentionPolicy;
import com.github.feng111627.idempotentwritegatewayjava.tests.support.SlowWriteController;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 端到端验证：处理中保留窗口故意设得很短，首请业务还在真实执行时，
 * 窗口就已经到期——此时同键并发请求绝不能把业务再做一遍，只能拿到 409；
 * 首请结束后结果可重放。
 */
@SpringBootTest(properties = "idempotency.processing-ttl=1s")
@AutoConfigureMockMvc
class InFlightWindowIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(InFlightWindowIntegrationTest.class);

    /** 可控时钟的存储：业务在真实线程里阻塞，时钟由测试推进，跨过 1s 的处理中窗口。 */
    static class ClockStore extends InMemoryIdempotencyStore {
        private final AtomicLong clock = new AtomicLong(System.currentTimeMillis());

        ClockStore(RetentionPolicy policy) {
            super(policy);
        }

        @Override
        protected long nowMillis() {
            return clock.get();
        }

        void advance(Duration d) {
            clock.addAndGet(d.toMillis());
        }
    }

    @TestConfiguration
    static class Config {
        @Bean
        @Primary
        ClockStore clockStore(RetentionPolicy policy) {
            return new ClockStore(policy);
        }
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private ClockStore store;
    @Autowired private SlowWriteController slowController;

    @BeforeEach
    void reset() {
        store.clear();
        slowController.reset();
    }

    @Test
    @DisplayName("【处理中窗口到期+首请仍在跑】窗口过后并发请求仍只 409，业务只做一次，完成后可重放")
    void requestOutlivingProcessingWindowIsNotDuplicated() throws Exception {
        log.info("====== 场景: 处理中窗口（1s）到期但首请仍在真实执行 ======");
        String key = "slow-key-1";

        // 1) 首请在独立线程发起并进入业务阻塞点
        Thread first = new Thread(() -> {
            try {
                mockMvc.perform(post("/test/slow")
                                .header("Idempotency-Key", key)
                                .contentType("application/json").content("{}"))
                        .andExpect(status().isOk());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }, "first-request");
        first.start();
        slowController.awaitEntered();
        log.info("首请已进入业务并阻塞，开始推进时钟跨过处理中窗口");

        // 2) 时钟跨过处理中窗口：记录已“过期”，但业务还在跑
        store.advance(Duration.ofSeconds(5));

        // 3) 此时同键再来请求：必须 409 PROCESSING（在途护栏），业务绝不二次执行
        MvcResult blocked = mockMvc.perform(post("/test/slow")
                        .header("Idempotency-Key", key)
                        .contentType("application/json").content("{}"))
                .andExpect(status().isConflict())
                .andReturn();
        String retryAfter = blocked.getResponse().getHeader("Retry-After");
        assertTrue(retryAfter != null && Integer.parseInt(retryAfter) >= 1,
                "处理中响应应带 Retry-After（秒），实际=" + retryAfter);
        log.info("窗口过后的并发请求得到 409 + Retry-After={}s", retryAfter);

        // 4) 放开首请业务，等待其完成
        slowController.releaseBusiness();
        first.join();

        // 5) 再提交：重放首请结果，业务总执行次数仍为 1（businessRan 只被置过一次）
        MvcResult replay = mockMvc.perform(post("/test/slow")
                        .header("Idempotency-Key", key)
                        .contentType("application/json").content("{}"))
                .andExpect(status().isOk())
                .andExpect(result -> assertTrue(
                        result.getResponse().containsHeader("Idempotency-Replayed"),
                        "完成后同键应命中重放，带 Idempotency-Replayed 头"))
                .andReturn();
        assertTrue(slowController.isBusinessRan(), "业务确实执行过");
        log.info("场景通过: 跨窗口在途期间同键请求=409 不重复执行；首请完成后同键=200 重放，业务仅一次");
    }
}
