package com.github.feng111627.idempotentwritegatewayjava.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.github.feng111627.idempotentwritegatewayjava.idempotency.ClaimResult;
import com.github.feng111627.idempotentwritegatewayjava.idempotency.KeyState;
import com.github.feng111627.idempotentwritegatewayjava.idempotency.KeyStateView;
import com.github.feng111627.idempotentwritegatewayjava.idempotency.RetentionPolicy;
import com.github.feng111627.idempotentwritegatewayjava.idempotency.InMemoryIdempotencyStore;

/**
 * 状态查询单元测试：覆盖 NOT_FOUND / PROCESSING / SUCCEEDED /
 * FAILED_RETRYABLE / EXPIRED 五种分支，以及处理中时的重试建议时间。
 */
class KeyStateQueryTest {

    private static final Logger log = LoggerFactory.getLogger(KeyStateQueryTest.class);

    private static final class ClockStore extends InMemoryIdempotencyStore {
        private final AtomicLong now = new AtomicLong(5_000_000L);

        ClockStore(RetentionPolicy policy) {
            super(policy);
        }

        @Override
        protected long nowMillis() {
            return now.get();
        }

        void advance(Duration d) {
            now.addAndGet(d.toMillis());
        }
    }

    private ClockStore store;
    private static final RetentionPolicy POLICY = new RetentionPolicy(
            Duration.ofSeconds(10), Duration.ofSeconds(100), Duration.ofSeconds(30),
            10_000, false);

    @BeforeEach
    void setUp() {
        store = new ClockStore(POLICY);
    }

    @Test
    @DisplayName("【查询】从未提交过的键：NOT_FOUND，可当全新提交")
    void neverSubmittedIsNotFound() {
        log.info("====== 用例: neverSubmittedIsNotFound ======");
        KeyStateView view = store.query("never-seen");
        assertEquals(KeyState.NOT_FOUND, view.state());
        assertNull(view.retryAfterSeconds());
        assertNull(view.expiresAtEpochMillis());
        log.info("用例通过: 未提交键查询结果=NOT_FOUND");
    }

    @Test
    @DisplayName("【查询】处理中：PROCESSING，并给出 retryAfterSeconds 与过期时刻")
    void processingShowsRetryAfter() {
        log.info("====== 用例: processingShowsRetryAfter ======");
        store.claim("k-p", "h", POLICY); // 在途，未释放

        KeyStateView view = store.query("k-p");
        assertEquals(KeyState.PROCESSING, view.state());
        assertNotNull(view.retryAfterSeconds());
        assertEquals(10, view.retryAfterSeconds(), "刚抢占，剩余处理中窗口约 10s");
        assertNotNull(view.expiresAtEpochMillis());
        log.info("用例通过: PROCESSING 返回 retryAfterSeconds={}s，调用方无需盲目重试",
                view.retryAfterSeconds());
    }

    @Test
    @DisplayName("【查询】处理中窗口已过但首请仍在跑：仍报 PROCESSING（护栏），给出固定轮询建议")
    void expiredWindowButInFlightStillProcessing() {
        log.info("====== 用例: expiredWindowButInFlightStillProcessing ======");
        store.claim("k-live", "h", POLICY); // 不释放护栏
        store.advance(Duration.ofSeconds(11));

        KeyStateView view = store.query("k-live");
        assertEquals(KeyState.PROCESSING, view.state(),
                "首请还在真实执行时，对外必须仍是处理中，不能误导客户端当新提交");
        assertNotNull(view.retryAfterSeconds());
        log.info("用例通过: 跨窗口在途仍=PROCESSING，retryAfterSeconds={}s", view.retryAfterSeconds());
    }

    @Test
    @DisplayName("【查询】成功过：SUCCEEDED；超过成功窗口：EXPIRED")
    void succeededThenExpired() {
        log.info("====== 用例: succeededThenExpired ======");
        store.claim("k-s", "h", POLICY);
        store.complete("k-s", 200, "application/json", "{}");
        store.releaseInFlight("k-s");
        assertEquals(KeyState.SUCCEEDED, store.query("k-s").state());

        store.advance(Duration.ofSeconds(101)); // 超过成功窗口
        KeyStateView expired = store.query("k-s");
        assertEquals(KeyState.EXPIRED, expired.state());
        log.info("用例通过: 成功期=SUCCEEDED，成功窗口过后=EXPIRED（可当新提交）");
    }

    @Test
    @DisplayName("【查询】失败过且在失败窗口内：FAILED_RETRYABLE；失败窗口过后：EXPIRED")
    void failedRetryableThenExpired() {
        log.info("====== 用例: failedRetryableThenExpired ======");
        store.claim("k-f", "h", POLICY);
        store.fail("k-f");
        store.releaseInFlight("k-f");
        assertEquals(KeyState.FAILED_RETRYABLE, store.query("k-f").state());
        log.info("查询: FAILED 窗口内=FAILED_RETRYABLE（可同内容重试）");

        store.advance(Duration.ofSeconds(31)); // 超过失败窗口
        assertEquals(KeyState.EXPIRED, store.query("k-f").state());
        log.info("用例通过: 失败窗口内=FAILED_RETRYABLE，过后=EXPIRED");
    }

    @Test
    @DisplayName("【查询】处理中窗口到期且首请已结束（兜底停留）：EXPIRED，同键可重新提交")
    void processingWindowExpiredAfterFinishIsExpired() {
        log.info("====== 用例: processingWindowExpiredAfterFinishIsExpired ======");
        store.claim("k-e", "h", POLICY);
        store.releaseInFlight("k-e"); // 业务已结束、但结果未固化（兜底）
        store.advance(Duration.ofSeconds(11));

        assertEquals(KeyState.EXPIRED, store.query("k-e").state());
        // 且确实可以重新抢占
        assertEquals(ClaimResult.Granted.class, store.claim("k-e", "h", POLICY).getClass());
        log.info("用例通过: 兜底停留的处理中记录到期后=EXPIRED，且可重新抢占执行");
    }
}
