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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import com.github.feng111627.idempotentwritegatewayjava.idempotency.IdempotencyProperties;
import com.github.feng111627.idempotentwritegatewayjava.idempotency.InMemoryIdempotencyStore;
import com.github.feng111627.idempotentwritegatewayjava.order.OrderService;

/**
 * 分状态保留时长的端到端验证：处理 1s、失败 2s、成功 1h。
 * <ul>
 *     <li>成功窗口与处理窗口独立：处理窗口过后，成功记录仍可重放；</li>
 *     <li>成功记录在成功窗口到期后不再重放、同键恢复新提交；</li>
 *     <li>失败记录在失败窗口内可同内容重试，到期后恢复新提交。</li>
 * </ul>
 */
@SpringBootTest(properties = {
        "idempotency.processing-ttl=1s",
        "idempotency.failed-ttl=2s",
        "idempotency.succeeded-ttl=1h"
})
@AutoConfigureMockMvc
class PerStateRetentionIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(PerStateRetentionIntegrationTest.class);

    static class PerStateStore extends InMemoryIdempotencyStore {
        private final AtomicLong clock = new AtomicLong(System.currentTimeMillis());

        PerStateStore(IdempotencyProperties properties) {
            super(properties.getRetentionWindows(), properties.getMaxRecords(),
                    properties.getProcessingRetryAfter());
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
    static class PerStateStoreConfig {
        @Bean
        @Primary
        PerStateStore perStateStore(IdempotencyProperties properties) {
            return new PerStateStore(properties);
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PerStateStore store;

    @Autowired
    private OrderService orderService;

    @BeforeEach
    void reset() {
        store.clear();
        orderService.resetInvocationCount();
    }

    private static final String BODY = "{\"productId\":\"prod-100\",\"quantity\":2}";

    @Test
    @DisplayName("【分状态时长】成功窗口独立于处理窗口：处理 1s 过后成功记录仍可重放；成功窗口到期后才失效")
    void succeededWindowIndependentFromProcessingWindow() throws Exception {
        log.info("====== 场景: 处理窗口=1s、成功窗口=1h，三段各算各的 ======");
        mockMvc.perform(post("/api/orders")
                        .header("Idempotency-Key", "ps-ok")
                        .contentType("application/json").content(BODY))
                .andExpect(status().isOk());
        assertEquals(1, orderService.getInvocationCount());

        // 推进 3s（远超 1s 处理窗口，但成功窗口有 1h）
        store.advance(Duration.ofSeconds(3));

        mockMvc.perform(get("/api/idempotency/status").header("Idempotency-Key", "ps-ok"))
                .andExpect(status().isOk())
                .andExpect(r -> assertTrue(r.getResponse().getContentAsString()
                        .contains("\"state\":\"SUCCEEDED\""), r.getResponse().getContentAsString()));
        mockMvc.perform(post("/api/orders")
                        .header("Idempotency-Key", "ps-ok")
                        .contentType("application/json").content(BODY))
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotency-Replayed", "true"));
        assertEquals(1, orderService.getInvocationCount(), "成功窗口内只重放、不重新执行");
        log.info("场景通过: 处理窗口过后成功记录依然 SUCCEEDED 可重放");

        // 推进到成功窗口到期（再走约 1 小时）
        store.advance(Duration.ofMinutes(60));
        mockMvc.perform(get("/api/idempotency/status").header("Idempotency-Key", "ps-ok"))
                .andExpect(status().isOk())
                .andExpect(r -> assertTrue(r.getResponse().getContentAsString()
                        .contains("\"state\":\"NOT_FOUND\""), r.getResponse().getContentAsString()));
        mockMvc.perform(post("/api/orders")
                        .header("Idempotency-Key", "ps-ok")
                        .contentType("application/json").content(BODY))
                .andExpect(status().isOk());
        assertEquals(2, orderService.getInvocationCount(), "成功窗口到期后同键按新提交重新执行");
        log.info("场景通过: 成功窗口到期 → 状态查询 NOT_FOUND、同键重新提交真实再执行");
    }

    @Test
    @DisplayName("【分状态时长】失败窗口 2s：窗口内同内容可重试；窗口到期后同键（含换内容）恢复新提交")
    void failedWindowControlsRetrySlot() throws Exception {
        log.info("====== 场景: 失败窗口=2s 独立起算 ======");
        String badBody = "{\"productId\":\"p\",\"quantity\":0}";
        mockMvc.perform(post("/api/orders")
                        .header("Idempotency-Key", "ps-fail")
                        .contentType("application/json").content(badBody))
                .andExpect(status().isBadRequest());
        int executions = orderService.getInvocationCount();

        // 1s 后（仍在失败窗口）：同内容允许重试（再进业务，仍失败）
        store.advance(Duration.ofSeconds(1));
        mockMvc.perform(post("/api/orders")
                        .header("Idempotency-Key", "ps-fail")
                        .contentType("application/json").content(badBody))
                .andExpect(status().isBadRequest());
        assertEquals(executions + 1, orderService.getInvocationCount(), "失败窗口内同内容重试真实再执行");

        // 再过 2s（距上次失败标记超过 2s 失败窗口）：失败记录到期，同键可绑新内容作为新提交
        store.advance(Duration.ofSeconds(2));
        mockMvc.perform(post("/api/orders")
                        .header("Idempotency-Key", "ps-fail")
                        .contentType("application/json").content(BODY))
                .andExpect(status().isOk());
        log.info("场景通过: 失败窗口内可同内容重试；窗口到期后同键恢复为新提交（允许换新内容）");
    }
}
