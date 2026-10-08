package com.github.feng111627.idempotentwritegatewayjava.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
import com.github.feng111627.idempotentwritegatewayjava.idempotency.IdempotencyProperties;
import com.github.feng111627.idempotentwritegatewayjava.idempotency.InMemoryIdempotencyStore;
import com.github.feng111627.idempotentwritegatewayjava.order.OrderService;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 容量上限与淘汰规则的端到端验证：
 * <ol>
 *     <li>容量满时淘汰最老的终态记录，被淘汰键重新提交表现为全新请求（不报错、不重放旧结果）；</li>
 *     <li>处理中记录免疫淘汰；容量被处理中记录占满时新请求 503 fail-closed；</li>
 *     <li>容量边界 + 同键并发时业务仍然只执行一次。</li>
 * </ol>
 */
@SpringBootTest(properties = {"idempotency.processing-ttl=10m", "idempotency.max-records=3"})
@AutoConfigureMockMvc
class CapacityEvictionIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(CapacityEvictionIntegrationTest.class);

    static class CapacityTestStore extends InMemoryIdempotencyStore {
        // 每次读时钟都单调前进 1ms，保证记录创建时间严格有序，淘汰“最老”结果确定
        private final AtomicLong clock = new AtomicLong(System.currentTimeMillis());
        private final com.github.feng111627.idempotentwritegatewayjava.idempotency.RetentionWindows windows;

        CapacityTestStore(IdempotencyProperties properties) {
            super(properties.getRetentionWindows(), properties.getMaxRecords(),
                    properties.getProcessingRetryAfter());
            this.windows = properties.getRetentionWindows();
        }

        com.github.feng111627.idempotentwritegatewayjava.idempotency.RetentionWindows windowsForTest() {
            return windows;
        }

        @Override
        protected long nowMillis() {
            return clock.get();
        }

        /** 模拟时间流逝，同时保证记录创建时间严格有序。 */
        void tick() {
            clock.incrementAndGet();
        }
    }

    @TestConfiguration
    static class CapacityTestStoreConfig {
        @Bean
        @Primary
        CapacityTestStore capacityTestStore(IdempotencyProperties properties) {
            return new CapacityTestStore(properties);
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private OrderService orderService;

    @Autowired
    private CapacityTestStore store;

    @BeforeEach
    void clean() {
        store.clear();
        orderService.resetInvocationCount();
    }

    private static String bodyFor(String productId) {
        return "{\"productId\":\"" + productId + "\",\"quantity\":1}";
    }

    @Test
    @DisplayName("【容量淘汰-端到端】满容量时最老成功记录被淘汰；被淘汰键重新提交=全新执行且不重放")
    void evictedKeyBehavesAsNewSubmission() throws Exception {
        log.info("====== 场景: 容量淘汰后被淘汰键重新提交 ======");
        // 3 个不同键全部成功，占满容量；每单之间推进 1ms，保证创建时间严格有序
        for (int i = 1; i <= 3; i++) {
            store.tick();
            mockMvc.perform(post("/api/orders")
                            .header("Idempotency-Key", "cap-k" + i)
                            .contentType("application/json").content(bodyFor("p" + i)))
                    .andExpect(status().isOk());
        }
        store.tick();
        String firstResponse = mockMvc.perform(post("/api/orders")
                        .header("Idempotency-Key", "cap-k1")
                        .contentType("application/json").content(bodyFor("p1")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        int executionsBeforeEviction = orderService.getInvocationCount(); // 3（重放不执行）
        log.info("淘汰前：3 条成功记录占满容量，重放 cap-k1 不增加执行数={}", executionsBeforeEviction);

        // 第 4 个新键：容量满 → 淘汰最老终态 cap-k1 → cap-k4 成功
        store.tick();
        mockMvc.perform(post("/api/orders")
                        .header("Idempotency-Key", "cap-k4")
                        .contentType("application/json").content(bodyFor("p4")))
                .andExpect(status().isOk());
        assertEquals(3, store.currentSize(), "淘汰一条、新增一条，总量仍为上限");

        // 被淘汰的 cap-k1 状态查询：NOT_FOUND（可当新提交）
        String statusBody = mockMvc.perform(get("/api/idempotency/status")
                        .header("Idempotency-Key", "cap-k1"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertTrue(statusBody.contains("\"state\":\"NOT_FOUND\""), statusBody);

        // 被淘汰键重新提交：全新执行（业务计数 +1）、不带重放头、响应是新订单
        int executionsBeforeResubmit = orderService.getInvocationCount(); // 4（含 cap-k4）
        store.tick();
        String resubmitted = mockMvc.perform(post("/api/orders")
                        .header("Idempotency-Key", "cap-k1")
                        .contentType("application/json").content(bodyFor("p1")))
                .andExpect(status().isOk())
                .andExpect(r -> assertTrue(
                        r.getResponse().getHeaderValue("Idempotency-Replayed") == null,
                        "被淘汰键重新提交不能回放旧结果"))
                .andReturn().getResponse().getContentAsString();
        assertEquals(executionsBeforeResubmit + 1, orderService.getInvocationCount(),
                "被淘汰键重新提交必须真实再执行一次业务");
        // 新订单号与被淘汰前的响应不同，证明不是旧结果重放
        assertTrue(!resubmitted.equals(firstResponse),
                "新提交的响应必须是新产出的，而非旧结果重放");
        log.info("场景通过: 被淘汰 cap-k1 查询=NOT_FOUND，重新提交真实再执行一次且结果为新订单");
    }

    @Test
    @DisplayName("【容量-处理中免疫】容量被处理中占满：新键 503 不执行业务；首请结束后恢复受理")
    void processingRecordsImmuneToEviction() throws Exception {
        log.info("====== 场景: 容量全被处理中占满时 fail-closed ======");
        // 通过直接抢占制造 2 条处理中记录（业务在途，不结束）
        String h1 = sha(bodyFor("hold1"));
        String h2 = sha(bodyFor("hold2"));
        store.claim("busy-1", h1, store.windowsForTest());
        store.claim("busy-2", h2, store.windowsForTest());
        // 再正常打满一条成功记录（它会成为可淘汰候选，但本题只关心处理中占座）
        // 容量=3，此时处理中 2 条。新键提交时：无过期记录，可淘汰的终态记录也没有 →
        // 为突出“处理中免疫”，先再造第 3 条处理中
        store.claim("busy-3", sha(bodyFor("hold3")), store.windowsForTest());
        assertEquals(3, store.currentSize());

        String rejectBody = mockMvc.perform(post("/api/orders")
                        .header("Idempotency-Key", "new-key")
                        .contentType("application/json").content(bodyFor("newp")))
                .andExpect(status().isServiceUnavailable())
                .andReturn().getResponse().getContentAsString();
        assertTrue(rejectBody.contains("IDEMPOTENCY_STORAGE_UNAVAILABLE"), rejectBody);
        assertEquals(0, orderService.getInvocationCount(), "503 期间业务零执行");
        log.info("场景通过: 容量全被处理中占满 → 新键 503/IDEMPOTENCY_STORAGE_UNAVAILABLE，业务零执行");

        // 释放两个处理中（模拟业务失败结束），容量腾位后新键可正常受理
        store.fail("busy-1");
        store.fail("busy-2");
        // 此时有 2 条 FAILED 终态记录可被淘汰
        mockMvc.perform(post("/api/orders")
                        .header("Idempotency-Key", "new-key")
                        .contentType("application/json").content(bodyFor("newp")))
                .andExpect(status().isOk());
        assertEquals(1, orderService.getInvocationCount(), "腾出容量后新键正常真实执行一次");
        log.info("场景通过: 处理中业务结束后容量恢复，新键正常受理");
    }

    @Test
    @DisplayName("【容量+窗口+并发】容量已满、正处于淘汰边界时同键并发：同一时刻仍只有 1 次真实执行")
    void concurrentClaimsAtCapacityBoundaryExecuteOnce() throws Exception {
        log.info("====== 场景: 容量满 + 窗口边界 + 同键多请求同时打进来 ======");
        // 先占满容量：3 条成功记录（都可作为淘汰候选）
        for (int i = 1; i <= 3; i++) {
            store.tick();
            mockMvc.perform(post("/api/orders")
                            .header("Idempotency-Key", "full-k" + i)
                            .contentType("application/json").content(bodyFor("fp" + i)))
                    .andExpect(status().isOk());
        }
        int executionsBefore = orderService.getInvocationCount();
        assertEquals(3, store.currentSize());

        // 同一新键，20 个请求同时打进来，正好需要在临界区内先淘汰一条再抢占
        int concurrency = 20;
        ExecutorService pool = Executors.newFixedThreadPool(concurrency);
        CountDownLatch ready = new CountDownLatch(concurrency);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();
        for (int i = 0; i < concurrency; i++) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                start.await();
                return mockMvc.perform(post("/api/orders")
                                .header("Idempotency-Key", "race-key")
                                .contentType("application/json").content(bodyFor("race-p")))
                        .andReturn().getResponse().getStatus();
            }));
        }
        ready.await();
        start.countDown();

        Map<Integer, Integer> statusCount = new HashMap<>();
        for (Future<Integer> f : futures) {
            statusCount.merge(f.get(), 1, Integer::sum);
        }
        pool.shutdown();
        log.info("容量边界并发结果分布: {}", statusCount);

        assertEquals(executionsBefore + 1, orderService.getInvocationCount(),
                "容量淘汰边界上同键并发，业务副作用仍然只能产生一次");
        int ok = statusCount.getOrDefault(200, 0);
        int processing = statusCount.getOrDefault(409, 0);
        assertEquals(concurrency, ok + processing, "结果只能是 200（首次/重放）或 409（处理中）");
        assertEquals(3, store.currentSize(), "记录数始终不超过容量上限");
        log.info("场景通过: 容量边界上 {} 并发，200={}（含重放）、409={}，业务真实执行=1，记录数≤上限",
                concurrency, ok, processing);
    }

    private static String sha(String body) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            return java.util.HexFormat.of().formatHex(digest.digest(
                    body.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
