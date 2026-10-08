package com.github.feng111627.idempotentwritegatewayjava.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import com.github.feng111627.idempotentwritegatewayjava.idempotency.IdempotencyKeyRequired;
import com.github.feng111627.idempotentwritegatewayjava.idempotency.IdempotencyProperties;
import com.github.feng111627.idempotentwritegatewayjava.idempotency.InMemoryIdempotencyStore;
import com.github.feng111627.idempotentwritegatewayjava.idempotency.KeyState;

/**
 * “处理窗口到期 ≠ 原请求已结束”这一核心边界的端到端验证：
 * 业务执行时间超过 processing-ttl 时，在它真正跑完之前，
 * 同键并发请求（无论同内容还是换内容）都只能得到 409，业务绝不能被做两遍；
 * 状态查询返回 PROCESSING_TIMEOUT；首请结束后恢复正常。
 */
@SpringBootTest(properties = "idempotency.processing-ttl=1s")
@AutoConfigureMockMvc
class InFlightGuardIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(InFlightGuardIntegrationTest.class);

    /** 慢业务：进入后等待闩锁释放，用于稳定制造“窗口过期但业务仍在跑”。 */
    static class SlowBusinessService {
        final CountDownLatch entered = new CountDownLatch(1);
        volatile CountDownLatch release = new CountDownLatch(1);
        final java.util.concurrent.atomic.AtomicInteger executions =
                new java.util.concurrent.atomic.AtomicInteger(0);

        java.util.Map<String, Object> slowCreate() throws InterruptedException {
            executions.incrementAndGet();
            entered.countDown();
            release.await();
            return java.util.Map.of("orderId", "slow-ord", "status", "CREATED");
        }

        void reset() {
            release.countDown();
            release = new CountDownLatch(1);
            executions.set(0);
        }
    }

    @RestController
    @RequestMapping("/api/slow-orders")
    static class SlowOrderController {
        private final SlowBusinessService slow;

        SlowOrderController(SlowBusinessService slow) {
            this.slow = slow;
        }

        @PostMapping
        @IdempotencyKeyRequired
        java.util.Map<String, Object> create() throws InterruptedException {
            return slow.slowCreate();
        }
    }

    /** 可控时钟：首请执行期间把时间推过处理窗口。 */
    static class GuardedStore extends InMemoryIdempotencyStore {
        private final AtomicLong clock = new AtomicLong(System.currentTimeMillis());

        GuardedStore(IdempotencyProperties properties) {
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
    static class GuardTestConfig {
        @Bean
        @Primary
        GuardedStore guardedStore(IdempotencyProperties properties) {
            return new GuardedStore(properties);
        }

        @Bean
        SlowBusinessService slowBusinessService() {
            return new SlowBusinessService();
        }

        @Bean
        SlowOrderController slowOrderController(SlowBusinessService slow) {
            return new SlowOrderController(slow);
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private GuardedStore store;

    @Autowired
    private SlowBusinessService slow;

    @BeforeEach
    void reset() {
        store.clear();
        slow.reset();
    }

    @Test
    @DisplayName("【处理窗口过期+首请在跑】同键并发（含换内容）一律 409、业务只 1 次；查询=PROCESSING_TIMEOUT")
    void processingWindowExpiryWhileInFlightStaysExclusive() throws Exception {
        log.info("====== 场景: processing-ttl=1s，但首请业务一直没结束 ======");
        ExecutorService pool = Executors.newFixedThreadPool(3);

        // 首请在后台执行，进入慢业务后被挂住
        Future<Integer> first = pool.submit(() -> mockMvc.perform(post("/api/slow-orders")
                        .header("Idempotency-Key", "slow-key")
                        .contentType("application/json")
                        .content("{\"productId\":\"p\",\"quantity\":1}"))
                .andReturn().getResponse().getStatus());
        assertTrue(slow.entered.await(5, java.util.concurrent.TimeUnit.SECONDS),
                "首请应当已经进入业务执行");

        // 处理窗口 1s，把时钟推到 5s 后——记录逻辑上已过期，但首请还在跑
        store.advance(Duration.ofSeconds(5));

        // 同内容并发：409
        mockMvc.perform(post("/api/slow-orders")
                        .header("Idempotency-Key", "slow-key")
                        .contentType("application/json")
                        .content("{\"productId\":\"p\",\"quantity\":1}"))
                .andExpect(status().isConflict());

        // 即使换内容（过期后本允许绑定新内容）：仍然 409，不能放行第二次执行
        mockMvc.perform(post("/api/slow-orders")
                        .header("Idempotency-Key", "slow-key")
                        .contentType("application/json")
                        .content("{\"productId\":\"OTHER\",\"quantity\":9}"))
                .andExpect(status().isConflict());

        // 状态查询：PROCESSING_TIMEOUT + 兜底轮询提示
        String stateBody = mockMvc.perform(get("/api/idempotency/status")
                        .header("Idempotency-Key", "slow-key"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertTrue(stateBody.contains("\"state\":\"" + KeyState.PROCESSING_TIMEOUT.name() + "\""),
                stateBody);
        assertTrue(stateBody.contains("\"retryAfterSeconds\":2"),
                "窗口已过时应给兜底轮询提示，实际: " + stateBody);

        assertEquals(1, slow.executions.get(), "护栏期间业务只能执行 1 次");

        // 放行首请：200 返回
        slow.release.countDown();
        assertEquals(200, first.get());
        assertEquals(1, slow.executions.get(), "首请结束，业务总执行次数仍为 1");

        // 首请结束后：成功窗口已按分状态策略重新起算（默认 24h），同内容恢复重放
        mockMvc.perform(post("/api/slow-orders")
                        .header("Idempotency-Key", "slow-key")
                        .contentType("application/json")
                        .content("{\"productId\":\"p\",\"quantity\":1}"))
                .andExpect(status().isOk())
                .andExpect(r -> assertEquals("true",
                        r.getResponse().getHeader("Idempotency-Replayed")));
        assertEquals(1, slow.executions.get(), "护栏解除后同内容只重放，不重复执行");

        pool.shutdown();
        log.info("场景通过: 窗口过期+首请在跑 → 并发（含换内容）全 409、查询=PROCESSING_TIMEOUT、"
                + "业务仅 1 次；首请完成后恢复重放");
    }
}
