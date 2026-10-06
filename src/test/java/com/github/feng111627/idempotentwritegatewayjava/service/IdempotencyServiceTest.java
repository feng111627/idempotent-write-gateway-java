package com.github.feng111627.idempotentwritegatewayjava.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.feng111627.idempotentwritegatewayjava.config.IdempotencyProperties;
import com.github.feng111627.idempotentwritegatewayjava.core.ExecutionOutcome;
import com.github.feng111627.idempotentwritegatewayjava.core.IdempotencyService;
import com.github.feng111627.idempotentwritegatewayjava.error.BusinessException;
import com.github.feng111627.idempotentwritegatewayjava.error.IdempotencyConflictException;
import com.github.feng111627.idempotentwritegatewayjava.error.IdempotencyInProgressException;
import com.github.feng111627.idempotentwritegatewayjava.error.StorageUnavailableException;
import com.github.feng111627.idempotentwritegatewayjava.error.StoredFailureException;
import com.github.feng111627.idempotentwritegatewayjava.store.AcquireResult;
import com.github.feng111627.idempotentwritegatewayjava.store.InMemoryIdempotencyStore;
import com.github.feng111627.idempotentwritegatewayjava.support.FaultyIdempotencyStore;
import com.github.feng111627.idempotentwritegatewayjava.support.TestClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 幂等服务核心单测：重复提交 / 并发抢占 / 过期重放 / 存储异常 四大类场景。
 */
class IdempotencyServiceTest {

    private static final String KEY = "key-0001-abcdef";
    private static final String SAME_FP = "fp-A";
    private static final String OTHER_FP = "fp-B";

    private TestClock clock;
    private FaultyIdempotencyStore store;
    private IdempotencyService service;
    private AtomicInteger businessRuns;

    @BeforeEach
    void setUp() {
        clock = new TestClock();
        IdempotencyProperties props = new IdempotencyProperties();
        store = new FaultyIdempotencyStore(new InMemoryIdempotencyStore(clock));
        service = new IdempotencyService(store, props, clock, new ObjectMapper());
        businessRuns = new AtomicInteger();
    }

    private com.github.feng111627.idempotentwritegatewayjava.core.ExecutionOutcome runFirst() {
        return service.execute(KEY, SAME_FP, () -> {
            businessRuns.incrementAndGet();
            return java.util.Map.of("orderId", "ORD-1", "amount", 100);
        });
    }

    private ExecutionOutcome replayAgain() {
        return service.execute(KEY, SAME_FP, () -> {
            businessRuns.incrementAndGet();
            return java.util.Map.of("orderId", "SHOULD-NOT-HAPPEN", "amount", 999);
        });
    }

    @Nested
    @DisplayName("重复提交场景")
    class DuplicateSubmission {

        @Test
        @DisplayName("同键同内容重复提交：业务只执行一次，后续返回与首次一致的缓存结果")
        void duplicateSameRequestExecutesOnce() {
            ExecutionOutcome first = runFirst();
            ExecutionOutcome second = replayAgain();
            ExecutionOutcome third = replayAgain();

            assertInstanceOf(ExecutionOutcome.Executed.class, first);
            assertInstanceOf(ExecutionOutcome.Replayed.class, second);
            assertInstanceOf(ExecutionOutcome.Replayed.class, third);

            ExecutionOutcome.Replayed replayed = (ExecutionOutcome.Replayed) second;
            assertEquals(200, replayed.httpStatus());
            assertTrue(replayed.responseBody().contains("ORD-1"), "重放内容必须与首次一致");
            assertTrue(replayed.responseBody().contains("\"amount\":100"), "重放内容必须与首次一致");
            assertEquals(1, businessRuns.get(), "业务副作用只能产生一次");
        }

        @Test
        @DisplayName("同键不同内容重复提交：拒绝并报 IDEMPOTENCY_KEY_CONFLICT")
        void sameKeyDifferentContentRejected() {
            runFirst();

            IdempotencyConflictException ex = assertThrows(IdempotencyConflictException.class,
                    () -> service.execute(KEY, OTHER_FP, () -> {
                        businessRuns.incrementAndGet();
                        return "不该执行";
                    }));
            assertEquals(422, ex.getHttpStatus());
            assertEquals("IDEMPOTENCY_KEY_CONFLICT", ex.getCode());
            assertEquals(1, businessRuns.get(), "冲突请求不得执行业务");
        }

        @Test
        @DisplayName("业务失败后 TTL 内同键重试：不重复执行业务，原样重放同一失败")
        void failureIsReplayedWithinTtl() {
            Runnable thrower = () -> {
                throw new BusinessException("ORDER_QUANTITY_INVALID", "quantity 必须为正整数");
            };

            BusinessException first = assertThrows(BusinessException.class,
                    () -> service.execute(KEY, SAME_FP, () -> {
                        businessRuns.incrementAndGet();
                        throw new BusinessException("ORDER_QUANTITY_INVALID", "quantity 必须为正整数");
                    }));
            assertEquals(422, first.getHttpStatus());

            StoredFailureException second = assertThrows(StoredFailureException.class,
                    () -> service.execute(KEY, SAME_FP, () -> {
                        businessRuns.incrementAndGet();
                        return "不该执行";
                    }));
            assertEquals(422, second.getHttpStatus());
            assertEquals("ORDER_QUANTITY_INVALID", second.getCode());
            assertEquals(1, businessRuns.get(), "失败 TTL 内重试不得重复执行业务");
        }
    }

    @Nested
    @DisplayName("并发抢占场景")
    class ConcurrentPreemption {

        @Test
        @DisplayName("首请处理中，同键同内容并发请求得到 409 IDEMPOTENCY_IN_PROGRESS，业务不重复执行")
        void concurrentWhileProcessingRejected() {
            // 首请进入业务后不立即结束（模拟长耗时业务），期间的并发抢占只能看到 PROCESSING
            ExecutionOutcome first;
            synchronized (this) {
                first = service.execute(KEY, SAME_FP, () -> {
                    businessRuns.incrementAndGet();
                    // 直接在“业务执行中”手工发起第二个抢占请求
                    AcquireResult concurrent = store.acquire(KEY, SAME_FP, clock.millis(),
                            new IdempotencyProperties().getProcessingTtl().toMillis());
                    assertInstanceOf(AcquireResult.InProgress.class, concurrent);
                    return java.util.Map.of("orderId", "ORD-X");
                });
            }

            assertInstanceOf(ExecutionOutcome.Executed.class, first);
            // 首请完成后再来一次，应当拿到与首次一致的重放结果
            ExecutionOutcome after = replayAgain();
            assertInstanceOf(ExecutionOutcome.Replayed.class, after);
            assertTrue(((ExecutionOutcome.Replayed) after).responseBody().contains("ORD-X"));
            assertEquals(1, businessRuns.get(), "并发下只能有一次真正执行业务");
        }

        @Test
        @DisplayName("处理中记录携带 Retry-After（秒）")
        void inProgressCarriesRetryAfter() {
            store.acquire(KEY, SAME_FP, clock.millis(),
                    new IdempotencyProperties().getProcessingTtl().toMillis());

            IdempotencyInProgressException ex = assertThrows(IdempotencyInProgressException.class,
                    () -> service.execute(KEY, SAME_FP, () -> {
                        businessRuns.incrementAndGet();
                        return "不该执行";
                    }));
            assertEquals(409, ex.getHttpStatus());
            assertTrue(ex.getRetryAfter().toSeconds() > 0, "Retry-After 必须为正");
            assertEquals(0, businessRuns.get());
        }
    }

    @Nested
    @DisplayName("过期重放（生命周期）场景")
    class Expiry {

        @Test
        @DisplayName("成功记录超过 successTtl 后同键可重新处理，业务再次真正执行")
        void expiredSuccessAllowsReprocessing() {
            runFirst();
            clock.advanceSeconds(24 * 3600L); // 默认 successTtl=24h，刚好过期

            ExecutionOutcome again = service.execute(KEY, SAME_FP, () -> {
                businessRuns.incrementAndGet();
                return java.util.Map.of("orderId", "ORD-2");
            });
            assertInstanceOf(ExecutionOutcome.Executed.class, again);
            assertEquals(2, businessRuns.get(), "过期后应重新执行业务");
        }

        @Test
        @DisplayName("失败记录超过 failureTtl 后允许真正重试并成功")
        void expiredFailureAllowsRetry() {
            assertThrows(BusinessException.class,
                    () -> service.execute(KEY, SAME_FP, () -> {
                        businessRuns.incrementAndGet();
                        throw new BusinessException("ORDER_QUANTITY_INVALID", "quantity 非法");
                    }));
            clock.advanceSeconds(5 * 60L + 1L); // 默认 failureTtl=5m

            ExecutionOutcome retried = service.execute(KEY, SAME_FP, () -> {
                businessRuns.incrementAndGet();
                return java.util.Map.of("orderId", "ORD-RETRY");
            });
            assertInstanceOf(ExecutionOutcome.Executed.class, retried);
            assertEquals(2, businessRuns.get(), "失败 TTL 过后应允许真正重试");
        }

        @Test
        @DisplayName("首请崩溃（PROCESSING 租约超时）后，同键可被新请求抢占")
        void processingLeaseExpiryAllowsTakeover() {
            store.acquire(KEY, SAME_FP, clock.millis(),
                    new IdempotencyProperties().getProcessingTtl().toMillis());
            clock.advanceSeconds(10 * 60L + 1L); // 默认 processingTtl=10m

            ExecutionOutcome takeover = runFirst();
            assertInstanceOf(ExecutionOutcome.Executed.class, takeover);
            assertEquals(1, businessRuns.get());
        }
    }

    @Nested
    @DisplayName("存储异常兜底场景")
    class StorageFailure {

        @Test
        @DisplayName("acquire 阶段存储不可用：抛 503 且业务完全不执行（fail-closed）")
        void acquireFailureRejectsBeforeBusiness() {
            store.failNextAcquire(1);

            StorageUnavailableException ex = assertThrows(StorageUnavailableException.class,
                    () -> service.execute(KEY, SAME_FP, () -> {
                        businessRuns.incrementAndGet();
                        return "不该执行";
                    }));
            assertEquals(503, ex.getHttpStatus());
            assertEquals("IDEMPOTENCY_STORAGE_UNAVAILABLE", ex.getCode());
            assertEquals(0, businessRuns.get(), "存储不可用时禁止执行业务");
        }

        @Test
        @DisplayName("业务成功后 complete 落库失败：不向调用方报错，真实结果照常返回")
        void completeFailureAfterSuccessStillReturnsResult() {
            store.failNextComplete(1);

            ExecutionOutcome outcome = runFirst();
            assertInstanceOf(ExecutionOutcome.Executed.class, outcome);
            assertEquals(1, businessRuns.get(), "业务已经做完，结果必须返回，不能制造错误重试");
        }

        @Test
        @DisplayName("业务失败后 complete 落库失败：业务异常照常抛出，不被存储异常掩盖")
        void completeFailureAfterBusinessFailurePreservesCause() {
            store.failNextComplete(1);

            BusinessException ex = assertThrows(BusinessException.class,
                    () -> service.execute(KEY, SAME_FP, () -> {
                        businessRuns.incrementAndGet();
                        throw new BusinessException("ORDER_QUANTITY_INVALID", "quantity 非法");
                    }));
            assertEquals("ORDER_QUANTITY_INVALID", ex.getCode());
            assertEquals(1, businessRuns.get());
        }
    }
}
