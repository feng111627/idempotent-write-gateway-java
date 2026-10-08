package com.github.feng111627.idempotentwritegatewayjava.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
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
import org.springframework.test.web.servlet.MvcResult;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import com.github.feng111627.idempotentwritegatewayjava.idempotency.InMemoryIdempotencyStore;
import com.github.feng111627.idempotentwritegatewayjava.order.OrderService;

/**
 * 端到端集成测试：经过真实的 Filter → Controller → Service 链路，
 * 覆盖需求要求的四类场景：重复提交、并发抢占、过期重放、存储异常。
 */
@SpringBootTest(properties = {
        "idempotency.processing-ttl=1s",
        "idempotency.succeeded-ttl=1s",
        "idempotency.failed-ttl=1s"})
@AutoConfigureMockMvc
class IdempotencyWebIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyWebIntegrationTest.class);

    /** 可控时钟 + 可注入故障的存储，供测试在容器中替换默认 Bean。 */
    static class TestStore extends InMemoryIdempotencyStore {
        private final AtomicLong clock = new AtomicLong(System.currentTimeMillis());
        private volatile boolean failOnComplete;

        TestStore(com.github.feng111627.idempotentwritegatewayjava.idempotency
                          .RetentionPolicy policy) {
            super(policy);
        }

        @Override
        protected long nowMillis() {
            return clock.get();
        }

        void advance(Duration d) {
            clock.addAndGet(d.toMillis());
        }

        @Override
        public void complete(String key, int statusCode, String contentType, String body) {
            if (failOnComplete) {
                throw new com.github.feng111627.idempotentwritegatewayjava.idempotency
                        .IdempotencyStorageException("【模拟】结果固化写失败");
            }
            super.complete(key, statusCode, contentType, body);
        }

        void setFailOnComplete(boolean value) {
            this.failOnComplete = value;
        }
    }

    @TestConfiguration
    static class TestStoreConfig {
        @Bean
        @Primary
        TestStore testStore(com.github.feng111627.idempotentwritegatewayjava.idempotency
                                    .RetentionPolicy policy) {
            return new TestStore(policy);
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private OrderService orderService;

    @Autowired
    private TestStore store;

    @BeforeEach
    void clean() {
        store.clear();
        store.setFailOnComplete(false);
        orderService.resetInvocationCount();
    }

    private static final String ORDER_BODY = "{\"productId\":\"prod-100\",\"quantity\":2}";

    @Test
    @DisplayName("【重复提交】同一幂等键连续提交两次：业务只执行一次，第二次原样重放首次结果")
    void duplicateSubmissionReplaysFirstResult() throws Exception {
        log.info("====== 场景: 重复提交（网络重试/用户连点）======");
        int before = orderService.getInvocationCount();

        MvcResult first = mockMvc.perform(post("/api/orders")
                        .header("Idempotency-Key", "dup-key-001")
                        .contentType("application/json")
                        .content(ORDER_BODY))
                .andExpect(status().isOk())
                .andReturn();

        MvcResult second = mockMvc.perform(post("/api/orders")
                        .header("Idempotency-Key", "dup-key-001")
                        .contentType("application/json")
                        .content(ORDER_BODY))
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotency-Replayed", "true"))
                .andReturn();

        assertEquals(first.getResponse().getContentAsString(),
                second.getResponse().getContentAsString(), "重放响应体必须与首次完全一致");
        assertEquals(before + 1, orderService.getInvocationCount(), "业务必须只真正执行一次");
        log.info("场景通过: 两次提交响应一致，业务执行次数增量=1，第二次带 Idempotency-Replayed:true");
    }

    @Test
    @DisplayName("【键缺失】不带 Idempotency-Key 返回 400 + IDEMPOTENCY_MISSING_KEY")
    void missingKeyRejectedWithClearReason() throws Exception {
        log.info("====== 场景: 幂等键缺失 ======");
        mockMvc.perform(post("/api/orders").contentType("application/json").content(ORDER_BODY))
                .andExpect(status().isBadRequest())
                .andExpect(result -> assertTrue(
                        result.getResponse().getContentAsString().contains("IDEMPOTENCY_MISSING_KEY")));
        assertEquals(0, orderService.getInvocationCount(), "键缺失时不得执行业务");
        log.info("场景通过: 缺失键被 400 拒绝，错误码可区分，业务零执行");
    }

    @Test
    @DisplayName("【键格式错误】非法键返回 400 + IDEMPOTENCY_INVALID_KEY_FORMAT")
    void invalidKeyFormatRejectedWithClearReason() throws Exception {
        log.info("====== 场景: 幂等键格式错误 ======");
        mockMvc.perform(post("/api/orders")
                        .header("Idempotency-Key", "bad key with space")
                        .contentType("application/json").content(ORDER_BODY))
                .andExpect(status().isBadRequest())
                .andExpect(result -> assertTrue(result.getResponse().getContentAsString()
                        .contains("IDEMPOTENCY_INVALID_KEY_FORMAT")));
        log.info("场景通过: 格式错误被 400 拒绝，错误码=IDEMPOTENCY_INVALID_KEY_FORMAT");
    }

    @Test
    @DisplayName("【内容冲突】同键搭配不同请求体返回 400 + IDEMPOTENCY_REQUEST_MISMATCH")
    void sameKeyDifferentBodyRejected() throws Exception {
        log.info("====== 场景: 同一个键配了不同请求内容 ======");
        mockMvc.perform(post("/api/orders")
                        .header("Idempotency-Key", "conflict-key")
                        .contentType("application/json").content(ORDER_BODY))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/orders")
                        .header("Idempotency-Key", "conflict-key")
                        .contentType("application/json")
                        .content("{\"productId\":\"prod-100\",\"quantity\":99}"))
                .andExpect(status().isBadRequest())
                .andExpect(result -> assertTrue(result.getResponse().getContentAsString()
                        .contains("IDEMPOTENCY_REQUEST_MISMATCH")));
        assertEquals(1, orderService.getInvocationCount(), "冲突请求不得再次执行业务");
        log.info("场景通过: 内容冲突被 400 拒绝，错误码=IDEMPOTENCY_REQUEST_MISMATCH");
    }

    @Test
    @DisplayName("【并发抢占】同键 N 个并发请求：恰好 1 次真正执行业务，其余得到重放或处理中")
    void concurrentRequestsExecuteExactlyOnce() throws Exception {
        log.info("====== 场景: 并发抢占（同一幂等键同时打进来）======");
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
                                .header("Idempotency-Key", "concurrent-key-1")
                                .contentType("application/json")
                                .content(ORDER_BODY))
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

        log.info("并发结果分布: {}", statusCount);
        int ok = statusCount.getOrDefault(200, 0);
        int processing = statusCount.getOrDefault(409, 0);
        assertEquals(concurrency, ok + processing, "每个请求必须是 200（首次或重放）或 409（处理中）");
        assertTrue(ok >= 1, "至少应有一个请求成功");
        // 成功的请求里，除首次外都必须是重放；业务总执行次数恰好 1
        assertEquals(1, orderService.getInvocationCount(),
                "并发下业务副作用必须只产生一次");
        log.info("场景通过: {} 个并发请求中 200={}（含重放），409处理中={}，业务真实执行=1",
                concurrency, ok, processing);
    }

    @Test
    @DisplayName("【过期重放】记录超过 TTL 后，同一个键可以重新处理并再次执行业务")
    void expiredKeyCanBeReprocessed() throws Exception {
        log.info("====== 场景: 过期重放 ======");
        mockMvc.perform(post("/api/orders")
                        .header("Idempotency-Key", "ttl-key-1")
                        .contentType("application/json").content(ORDER_BODY))
                .andExpect(status().isOk());
        assertEquals(1, orderService.getInvocationCount());

        // 未过期：仍然重放，不执行
        mockMvc.perform(post("/api/orders")
                        .header("Idempotency-Key", "ttl-key-1")
                        .contentType("application/json").content(ORDER_BODY))
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotency-Replayed", "true"));
        assertEquals(1, orderService.getInvocationCount());

        // 推进时钟超过 TTL
        store.advance(Duration.ofSeconds(2));

        // 过期后：同键重新抢占成功，业务再次执行
        mockMvc.perform(post("/api/orders")
                        .header("Idempotency-Key", "ttl-key-1")
                        .contentType("application/json").content(ORDER_BODY))
                .andExpect(status().isOk());
        assertEquals(2, orderService.getInvocationCount(), "过期后同键应允许重新处理");
        log.info("场景通过: TTL 内重放不执行，过期后同键可重新处理，业务再次真实执行");
    }

    @Test
    @DisplayName("【业务失败可重试】业务返回非 2xx 时记录 FAILED，相同内容可用同键重试成功")
    void businessFailureIsRetryableWithSameKey() throws Exception {
        log.info("====== 场景: 业务失败后的重试规则 ======");
        String badBody = "{\"productId\":\"prod-1\",\"quantity\":0}";
        String goodBody = "{\"productId\":\"prod-1\",\"quantity\":1}";

        // 非法数量：业务抛 IllegalArgumentException → 全局处理为 400，记录 FAILED
        mockMvc.perform(post("/api/orders")
                        .header("Idempotency-Key", "retry-key-1")
                        .contentType("application/json").content(badBody))
                .andExpect(status().isBadRequest());
        assertEquals(com.github.feng111627.idempotentwritegatewayjava.idempotency
                .IdempotencyStatus.FAILED, store.peek("retry-key-1").getStatus());

        // FAILED + 不同内容：仍然拒绝
        mockMvc.perform(post("/api/orders")
                        .header("Idempotency-Key", "retry-key-1")
                        .contentType("application/json").content(goodBody))
                .andExpect(status().isBadRequest())
                .andExpect(result -> assertTrue(result.getResponse().getContentAsString()
                        .contains("IDEMPOTENCY_REQUEST_MISMATCH")));

        // FAILED + 相同内容：允许重试，但内容本身仍非法所以还是 400 且保持 FAILED
        mockMvc.perform(post("/api/orders")
                        .header("Idempotency-Key", "retry-key-1")
                        .contentType("application/json").content(badBody))
                .andExpect(status().isBadRequest());

        // 用一个新键演示“同内容修正后重试成功”：首次合法直接成功
        mockMvc.perform(post("/api/orders")
                        .header("Idempotency-Key", "retry-key-2")
                        .contentType("application/json").content(goodBody))
                .andExpect(status().isOk());
        log.info("场景通过: 业务失败 → FAILED；TTL 内只允许同内容重试；内容冲突仍拒绝");
    }

    @Test
    @DisplayName("【存储异常-固化后】结果固化写失败：客户端仍收到 200，之后重试得到 409，业务不重复")
    void storageDownDuringCompleteDoesNotDuplicate() throws Exception {
        log.info("====== 场景: 业务成功后固化结果失败（明确兜底）======");
        store.setFailOnComplete(true);

        MvcResult result = mockMvc.perform(post("/api/orders")
                        .header("Idempotency-Key", "complete-fail-key")
                        .contentType("application/json").content(ORDER_BODY))
                .andExpect(status().isOk())
                .andReturn();
        assertTrue(result.getResponse().getContentAsString().contains("orderId"),
                "业务已成功，真实结果必须照常返回给客户端");
        int executionsAfterFirst = orderService.getInvocationCount();

        // 固化失败后记录停留在 PROCESSING：TTL 内重试得到 409 PROCESSING，杜绝重复副作用
        mockMvc.perform(post("/api/orders")
                        .header("Idempotency-Key", "complete-fail-key")
                        .contentType("application/json").content(ORDER_BODY))
                .andExpect(status().isConflict());
        assertEquals(executionsAfterFirst, orderService.getInvocationCount(),
                "兜底期间重试不得再次执行业务");

        // TTL 过期后键重新可用
        store.advance(Duration.ofSeconds(2));
        mockMvc.perform(post("/api/orders")
                        .header("Idempotency-Key", "complete-fail-key")
                        .contentType("application/json").content(ORDER_BODY))
                .andExpect(status().isOk());
        log.info("场景通过: 固化失败不向客户端报错、不返回假结果；TTL 内重试 409；过期后恢复");
    }
}
