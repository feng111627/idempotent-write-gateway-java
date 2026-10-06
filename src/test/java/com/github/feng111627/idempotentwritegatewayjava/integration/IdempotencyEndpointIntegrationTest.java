package com.github.feng111627.idempotentwritegatewayjava.integration;

import com.github.feng111627.idempotentwritegatewayjava.demo.OrderController;
import com.github.feng111627.idempotentwritegatewayjava.support.FaultyIdempotencyStore;
import com.github.feng111627.idempotentwritegatewayjava.support.IdempotencyTestConfig;
import com.github.feng111627.idempotentwritegatewayjava.support.TestClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Clock;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 端到端集成测试：真实 Header、JSON body、状态码、重试/重放响应头、业务副作用计数。
 * 覆盖：缺失/非法键、重复提交、同键冲突、失败重放、并发抢占、过期重放、存储异常。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(IdempotencyTestConfig.class)
class IdempotencyEndpointIntegrationTest {

    private static final String ORDERS_URI = "/api/orders";
    private static final String HEADER = "Idempotency-Key";
    private static final String VALID_KEY = "order-key-20261006-0001";
    private static final String BODY = "{\"productId\":\"P-1001\",\"quantity\":2}";
    private static final String BODY_CHANGED = "{\"productId\":\"P-1001\",\"quantity\":3}";
    private static final String INVALID_BODY = "{\"productId\":\"P-1001\",\"quantity\":0}";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private Clock clock;

    @Autowired
    private FaultyIdempotencyStore faultyStore;

    @Autowired
    private OrderController orderController;

    private int baselineExecutions;

    @BeforeEach
    void resetState() {
        faultyStore.reset();
        ((TestClock) clock).reset();
        baselineExecutions = orderController.getBusinessExecutionCount();
        orderController.installGate(null);
    }

    /** 本用例期间业务真正执行的次数（计数器是单例累计的，按前后差值断言）。 */
    private int executionsInThisTest() {
        return orderController.getBusinessExecutionCount() - baselineExecutions;
    }

    @Nested
    @DisplayName("幂等键获取与校验")
    class KeyValidation {

        @Test
        @DisplayName("缺少 Idempotency-Key 请求头：400 IDEMPOTENCY_KEY_MISSING")
        void missingKeyRejected() throws Exception {
            mockMvc.perform(post(ORDERS_URI).contentType(MediaType.APPLICATION_JSON).content(BODY))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().string(org.hamcrest.Matchers.containsString(
                            "IDEMPOTENCY_KEY_MISSING")));
        }

        @Test
        @DisplayName("幂等键格式非法（太短/含空格）：400 IDEMPOTENCY_KEY_INVALID")
        void invalidKeyRejected() throws Exception {
            mockMvc.perform(post(ORDERS_URI).header(HEADER, "short")
                            .contentType(MediaType.APPLICATION_JSON).content(BODY))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().string(org.hamcrest.Matchers.containsString(
                            "IDEMPOTENCY_KEY_INVALID")));
            assertEquals(0, executionsInThisTest(), "非法键不得执行业务");
        }
    }

    @Nested
    @DisplayName("重复提交与内容冲突")
    class DuplicateAndConflict {

        @Test
        @DisplayName("同键同内容连点两次：都成功且返回体完全一致，业务只执行一次，第二次带重放头")
        void duplicateReturnsSameResponse() throws Exception {
            MvcResult first = mockMvc.perform(post(ORDERS_URI).header(HEADER, VALID_KEY)
                            .contentType(MediaType.APPLICATION_JSON).content(BODY))
                    .andExpect(status().isOk())
                    .andReturn();

            MvcResult second = mockMvc.perform(post(ORDERS_URI).header(HEADER, VALID_KEY)
                            .contentType(MediaType.APPLICATION_JSON).content(BODY))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Idempotency-Replayed", "true"))
                    .andReturn();

            assertEquals(first.getResponse().getContentAsString(),
                    second.getResponse().getContentAsString(), "重放返回必须与首次完全一致");
            assertEquals(1, executionsInThisTest());
        }

        @Test
        @DisplayName("同一个键携带不同请求内容：422 IDEMPOTENCY_KEY_CONFLICT")
        void sameKeyDifferentBodyConflict() throws Exception {
            mockMvc.perform(post(ORDERS_URI).header(HEADER, VALID_KEY)
                            .contentType(MediaType.APPLICATION_JSON).content(BODY))
                    .andExpect(status().isOk());

            mockMvc.perform(post(ORDERS_URI).header(HEADER, VALID_KEY)
                            .contentType(MediaType.APPLICATION_JSON).content(BODY_CHANGED))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(content().string(org.hamcrest.Matchers.containsString(
                            "IDEMPOTENCY_KEY_CONFLICT")));
            assertEquals(1, executionsInThisTest());
        }

        @Test
        @DisplayName("业务失败后 TTL 内重复提交：400 原样重放同一失败码，业务不重复执行")
        void businessFailureReplayed() throws Exception {
            mockMvc.perform(post(ORDERS_URI).header(HEADER, VALID_KEY)
                            .contentType(MediaType.APPLICATION_JSON).content(INVALID_BODY))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().string(org.hamcrest.Matchers.containsString(
                            "ORDER_QUANTITY_INVALID")));

            mockMvc.perform(post(ORDERS_URI).header(HEADER, VALID_KEY)
                            .contentType(MediaType.APPLICATION_JSON).content(INVALID_BODY))
                    .andExpect(status().isBadRequest())
                    .andExpect(header().string("Idempotency-Replayed", "true"))
                    .andExpect(content().string(org.hamcrest.Matchers.containsString(
                            "ORDER_QUANTITY_INVALID")));
            assertEquals(0, executionsInThisTest(), "非法请求不得产生副作用");
        }
    }

    @Nested
    @DisplayName("过期重放（记录生命周期）")
    class ExpiryOverHttp {

        @Test
        @DisplayName("成功记录过期后同键重新处理：业务再次执行，返回新订单")
        void replayAfterSuccessTtlExpiry() throws Exception {
            MvcResult first = mockMvc.perform(post(ORDERS_URI).header(HEADER, VALID_KEY)
                            .contentType(MediaType.APPLICATION_JSON).content(BODY))
                    .andExpect(status().isOk()).andReturn();
            String firstOrderId = orderId(first);
            int countAfterFirst = executionsInThisTest();

            ((TestClock) clock).advanceSeconds(3600L + 1L); // successTtl=1h

            MvcResult second = mockMvc.perform(post(ORDERS_URI).header(HEADER, VALID_KEY)
                            .contentType(MediaType.APPLICATION_JSON).content(BODY))
                    .andExpect(status().isOk())
                    .andExpect(header().doesNotExist("Idempotency-Replayed"))
                    .andReturn();
            String secondOrderId = orderId(second);
            assertTrue(!firstOrderId.equals(secondOrderId), "过期后应是一笔新订单");
            assertEquals(countAfterFirst + 1, executionsInThisTest(),
                    "过期重放后业务应再执行一次");
        }

        @Test
        @DisplayName("失败记录过期后允许真正重试并成功")
        void retryAfterFailureTtlExpiry() throws Exception {
            mockMvc.perform(post(ORDERS_URI).header(HEADER, VALID_KEY)
                            .contentType(MediaType.APPLICATION_JSON).content(INVALID_BODY))
                    .andExpect(status().isBadRequest());

            ((TestClock) clock).advanceSeconds(60L + 1L); // failureTtl=1m

            mockMvc.perform(post(ORDERS_URI).header(HEADER, VALID_KEY)
                            .contentType(MediaType.APPLICATION_JSON).content(BODY))
                    .andExpect(status().isOk())
                    .andExpect(content().string(org.hamcrest.Matchers.containsString("P-1001")));
            assertEquals(1, executionsInThisTest());
        }
    }

    @Nested
    @DisplayName("存储异常兜底")
    class StorageFailureOverHttp {

        @Test
        @DisplayName("存储 acquire 失败：503 IDEMPOTENCY_STORAGE_UNAVAILABLE 且业务未执行")
        void storageDownOnAcquireReturns503() throws Exception {
            faultyStore.failNextAcquire(1);

            mockMvc.perform(post(ORDERS_URI).header(HEADER, VALID_KEY)
                            .contentType(MediaType.APPLICATION_JSON).content(BODY))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(content().string(org.hamcrest.Matchers.containsString(
                            "IDEMPOTENCY_STORAGE_UNAVAILABLE")));
            assertEquals(0, executionsInThisTest(), "存储不可用时禁止执行业务");
        }

        @Test
        @DisplayName("业务成功后终态写失败：客户端仍拿到 200 成功结果（不报错、不要求重试）")
        void storageDownOnCompleteStillSucceeds() throws Exception {
            faultyStore.failNextComplete(1);

            mockMvc.perform(post(ORDERS_URI).header(HEADER, VALID_KEY)
                            .contentType(MediaType.APPLICATION_JSON).content(BODY))
                    .andExpect(status().isOk())
                    .andExpect(content().string(org.hamcrest.Matchers.containsString("P-1001")));
            assertEquals(1, executionsInThisTest());
        }
    }

    @Nested
    @DisplayName("并发抢占")
    class TrueConcurrency {

        @Test
        @DisplayName("同键并发请求在途：得到 409 IDEMPOTENCY_IN_PROGRESS + Retry-After，业务不执行")
        void processingRecordReturns409Contract() throws Exception {
            // 预置一条 PROCESSING（首请在途），再发同键请求必须得到 409 + Retry-After。
            faultyStore.acquire(VALID_KEY, "fp-placeholder", ((TestClock) clock).millis(),
                    120_000L);

            mockMvc.perform(post(ORDERS_URI).header(HEADER, VALID_KEY)
                            .contentType(MediaType.APPLICATION_JSON).content(BODY))
                    .andExpect(status().isConflict())
                    .andExpect(header().string("Retry-After", org.hamcrest.Matchers.notNullValue()))
                    .andExpect(content().string(org.hamcrest.Matchers.containsString(
                            "IDEMPOTENCY_IN_PROGRESS")));
            assertEquals(0, executionsInThisTest());
        }

        @Test
        @DisplayName("5 个真实线程同键同时提交：恰好 1 次业务执行、1 个 200，其余 409；放行后重试拿到一致结果")
        void fiveThreadsSameKeyExactlyOneBusinessRun() throws Exception {
            int threads = 5;
            CountDownLatch gate = new CountDownLatch(1);
            CountDownLatch start = new CountDownLatch(1);
            orderController.installGate(gate);

            ExecutorService pool = Executors.newFixedThreadPool(threads);
            try {
                java.util.List<Future<MvcResult>> futures = new java.util.ArrayList<>();
                for (int i = 0; i < threads; i++) {
                    futures.add(pool.submit(() -> {
                        start.await();
                        return mockMvc.perform(post(ORDERS_URI).header(HEADER, VALID_KEY)
                                .contentType(MediaType.APPLICATION_JSON).content(BODY)).andReturn();
                    }));
                }
                start.countDown();

                // 等 4 个抢不到锁的请求先落定为 409
                org.awaitility.Awaitility.await().atMost(5, TimeUnit.SECONDS).until(() ->
                        countStatus(futures, 409) >= threads - 1);

                gate.countDown(); // 放行首请业务

                int ok = 0;
                int conflict = 0;
                String uniqueOrder = null;
                for (Future<MvcResult> f : futures) {
                    MvcResult result = f.get(10, TimeUnit.SECONDS);
                    int sc = result.getResponse().getStatus();
                    if (sc == 200) {
                        ok++;
                        uniqueOrder = result.getResponse().getContentAsString();
                    } else if (sc == 409) {
                        conflict++;
                    }
                }

                assertEquals(1, ok, "同键并发只能有一个 200");
                assertEquals(threads - 1, conflict, "其余请求必须是 409 处理中");
                assertEquals(1, executionsInThisTest(), "业务副作用恰好一次");
                assertEquals(1, orderController.getMaxConcurrentInBusiness(),
                        "同一幂等键的业务体任何时刻不得并发执行");

                // 首请完成后，原先收到 409 的请求重试：拿到与首请完全一致的 200（带重放头）
                MvcResult retried = mockMvc.perform(post(ORDERS_URI).header(HEADER, VALID_KEY)
                                .contentType(MediaType.APPLICATION_JSON).content(BODY))
                        .andExpect(status().isOk())
                        .andExpect(header().string("Idempotency-Replayed", "true"))
                        .andReturn();
                assertEquals(uniqueOrder, retried.getResponse().getContentAsString(),
                        "并发失败方重试结果必须与首请一致");
                assertEquals(1, executionsInThisTest(), "重试不得再次执行业务");
            } finally {
                orderController.installGate(null);
                pool.shutdown();
                assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS),
                        "所有并发请求线程必须在本用例结束前退出，避免污染后续用例");
            }
        }

        private long countStatus(java.util.List<Future<MvcResult>> futures, int status) {
            return futures.stream().filter(f -> f.isDone() && !f.isCancelled())
                    .filter(f -> {
                        try {
                            return f.get().getResponse().getStatus() == status;
                        } catch (Exception e) {
                            return false;
                        }
                    }).count();
        }
    }

    private static String orderId(MvcResult result) throws Exception {
        String json = result.getResponse().getContentAsString();
        int start = json.indexOf("\"orderId\":\"") + "\"orderId\":\"".length();
        int end = json.indexOf('"', start);
        return json.substring(start, end);
    }
}
