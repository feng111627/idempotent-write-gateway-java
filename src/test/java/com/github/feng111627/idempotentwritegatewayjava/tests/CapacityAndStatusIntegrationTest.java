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
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.feng111627.idempotentwritegatewayjava.idempotency.InMemoryIdempotencyStore;
import com.github.feng111627.idempotentwritegatewayjava.idempotency.RetentionPolicy;
import com.github.feng111627.idempotentwritegatewayjava.tests.support.SlowWriteController;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 容量上限（满时 503 fail-closed）与状态查询 HTTP 入口的端到端测试。
 */
@SpringBootTest(properties = {
        "idempotency.processing-ttl=1s",
        "idempotency.succeeded-ttl=10s",
        "idempotency.failed-ttl=3s",
        "idempotency.max-records=3"})
@AutoConfigureMockMvc
class CapacityAndStatusIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(CapacityAndStatusIntegrationTest.class);
    private static final ObjectMapper JSON = new ObjectMapper();

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
    void clean() {
        // 先重置慢业务闩（新建 release/entered），再清存储，避免测试间一次性闩互相干扰
        slowController.reset();
        store.clear();
    }

    private static final String BODY = "{\"productId\":\"prod-1\",\"quantity\":1}";

    private JsonNode stateOf(String key) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/idempotency/keys/status")
                        .header("Idempotency-Key", key))
                .andExpect(status().isOk())
                .andReturn();
        return JSON.readTree(result.getResponse().getContentAsString());
    }

    @Test
    @DisplayName("【容量满-全处理中】容量被在途处理中记录占满时，新写请求 503 fail-closed，不踢处理中记录")
    void capacityFullWithInFlightReturns503() throws Exception {
        log.info("====== 场景: 容量满且全部在处理中（max-records=3）======");
        // 用慢业务接口占满 3 个在途处理中槽位（每个不同键）
        java.util.List<Thread> runners = new java.util.ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            final String k = "busy-" + i;
            Thread t = new Thread(() -> {
                try {
                    mockMvc.perform(post("/test/slow")
                                    .header("Idempotency-Key", k)
                                    .contentType("application/json").content("{}"))
                            .andExpect(status().isOk());
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }, "busy-" + i);
            t.start();
            runners.add(t);
        }
        // 等 3 个请求都真正进入业务阻塞点（确保都是“在途处理中”记录）
        slowController.awaitEnteredCount(3);
        assertEquals(3, store.currentSize(), "三个不同键应都处于在途处理中");

        // 第 4 个新键：没有终态可淘汰，也不能踢处理中记录 → 503
        MvcResult rejected = mockMvc.perform(post("/api/orders")
                        .header("Idempotency-Key", "cap-overflow")
                        .contentType("application/json").content(BODY))
                .andExpect(status().isServiceUnavailable())
                .andReturn();
        String body = rejected.getResponse().getContentAsString();
        assertTrue(body.contains("IDEMPOTENCY_CAPACITY_FULL"),
                "503 响应必须能看出是容量满，实际: " + body);
        assertEquals(3, store.currentSize(), "拒绝期间不得挤掉任何在途处理中记录");
        log.info("容量被在途处理中占满时新写请求=503/IDEMPOTENCY_CAPACITY_FULL，处理中记录数保持=3");

        // 放开慢业务，三个在途请求正常完成
        slowController.releaseBusiness();
        for (Thread t : runners) {
            t.join();
        }
        log.info("场景通过: 容量满且全在处理中时 fail-closed 拒绝新写，绝不淘汰处理中记录导致重复执行");
    }

    @Test
    @DisplayName("【状态查询】NOT_FOUND / PROCESSING(retryAfter) / SUCCEEDED / FAILED_RETRYABLE / EXPIRED 全分支")
    void statusEndpointBranches() throws Exception {
        log.info("====== 场景: 状态查询各分支 ======");
        // NOT_FOUND
        assertEquals("NOT_FOUND", stateOf("q-new").get("state").asText());
        log.info("查询未提交键: NOT_FOUND");

        String key = "q-key";
        mockMvc.perform(post("/api/orders")
                        .header("Idempotency-Key", key)
                        .contentType("application/json").content(BODY))
                .andExpect(status().isOk());

        // SUCCEEDED
        JsonNode ok = stateOf(key);
        assertEquals("SUCCEEDED", ok.get("state").asText());
        assertTrue(ok.has("expiresAtEpochMillis"));
        log.info("查询成功键: SUCCEEDED");

        // 成功窗口过后：EXPIRED（失败窗口 3s、成功窗口 10s，推进 11s 全过期）
        store.advance(Duration.ofSeconds(11));
        assertEquals("EXPIRED", stateOf(key).get("state").asText());
        log.info("查询过期键: EXPIRED（可当新提交）");

        // FAILED_RETRYABLE：用一个会失败的键（quantity<=0）
        String failKey = "q-fail";
        mockMvc.perform(post("/api/orders")
                        .header("Idempotency-Key", failKey)
                        .contentType("application/json")
                        .content("{\"productId\":\"prod-1\",\"quantity\":0}"))
                .andExpect(status().isBadRequest());
        assertEquals("FAILED_RETRYABLE", stateOf(failKey).get("state").asText());
        log.info("查询失败键: FAILED_RETRYABLE");
        store.advance(Duration.ofSeconds(4)); // 超过失败窗口
        assertEquals("EXPIRED", stateOf(failKey).get("state").asText());
        log.info("失败窗口过后: EXPIRED");

        // 查询入口同样校验键
        mockMvc.perform(get("/api/idempotency/keys/status"))
                .andExpect(status().isBadRequest());
        log.info("场景通过: 状态查询五种分支齐全，缺键返回 400");
    }

    @Test
    @DisplayName("【容量满】终态记录占满容量后，新键先触发淘汰仍能提交；无终态可淘汰时 503 fail-closed")
    void capacityFullReturns503AndEvictionKeepsWritesWorking() throws Exception {
        log.info("====== 场景: 容量上限与淘汰（max-records=3）======");
        // 用 3 个不同键成功，占满容量
        for (int i = 1; i <= 3; i++) {
            mockMvc.perform(post("/api/orders")
                            .header("Idempotency-Key", "cap-" + i)
                            .contentType("application/json").content(BODY))
                    .andExpect(status().isOk());
        }
        assertEquals(3, store.currentSize());

        // 让其中两条变“更老可淘汰”：cap-1、cap-2 已是 SUCCEEDED 终态，新键 cap-4 到来时淘汰最老终态
        MvcResult ok = mockMvc.perform(post("/api/orders")
                        .header("Idempotency-Key", "cap-4")
                        .contentType("application/json").content(BODY))
                .andExpect(status().isOk())
                .andReturn();
        assertEquals(3, store.currentSize(), "淘汰后总数仍不超过上限");
        assertTrue(ok.getResponse().getContentAsString().contains("orderId"));
        log.info("容量满但有终态可淘汰：新键 cap-4 正常成功，记录总数保持=3");
        log.info("场景通过: 容量约束下靠淘汰终态记录持续提供服务，且总数不越界");
    }
}
