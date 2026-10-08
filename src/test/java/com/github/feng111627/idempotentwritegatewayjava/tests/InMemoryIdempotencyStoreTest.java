package com.github.feng111627.idempotentwritegatewayjava.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.github.feng111627.idempotentwritegatewayjava.idempotency.ClaimResult;
import com.github.feng111627.idempotentwritegatewayjava.idempotency.IdempotencyRecord;
import com.github.feng111627.idempotentwritegatewayjava.idempotency.IdempotencyStatus;
import com.github.feng111627.idempotentwritegatewayjava.idempotency.IdempotencyStorageException;
import com.github.feng111627.idempotentwritegatewayjava.idempotency.InMemoryIdempotencyStore;
import com.github.feng111627.idempotentwritegatewayjava.idempotency.KeyState;
import com.github.feng111627.idempotentwritegatewayjava.idempotency.KeyStatusView;
import com.github.feng111627.idempotentwritegatewayjava.idempotency.RejectionReason;
import com.github.feng111627.idempotentwritegatewayjava.idempotency.RetentionWindows;

/**
 * 存储层单元测试：抢占、并发互斥、成功重放、失败重试、过期重放、
 * 分状态保留窗口、in-flight 护栏、容量淘汰与状态查询、存储异常。
 */
class InMemoryIdempotencyStoreTest {

    private static final Logger log = LoggerFactory.getLogger(InMemoryIdempotencyStoreTest.class);

    /** 可控时钟，避免真实 sleep 来验证过期逻辑。 */
    private static final class ClockControlledStore extends InMemoryIdempotencyStore {
        private final AtomicLong now = new AtomicLong(1_000_000L);

        ClockControlledStore(RetentionWindows windows, int maxRecords) {
            super(windows, maxRecords, Duration.ofSeconds(2));
        }

        @Override
        protected long nowMillis() {
            return now.get();
        }

        void advance(Duration duration) {
            now.addAndGet(duration.toMillis());
        }
    }

    /** 老语义窗口：统一 10s、状态切换不重算（等价老版本 idempotency.ttl=10s）。 */
    private static final RetentionWindows LEGACY_WINDOWS =
            new RetentionWindows(Duration.ofSeconds(10), Duration.ofSeconds(10),
                    Duration.ofSeconds(10), false);

    /** 分状态窗口：处理 2s / 失败 5s / 成功 100s，状态切换重算。 */
    private static final RetentionWindows PER_STATE_WINDOWS =
            new RetentionWindows(Duration.ofSeconds(2), Duration.ofSeconds(100),
                    Duration.ofSeconds(5), true);

    private ClockControlledStore store;

    @BeforeEach
    void setUp() {
        store = new ClockControlledStore(LEGACY_WINDOWS, 1000);
    }

    @Test
    @DisplayName("【抢占】首次提交获得执行权，记录为 PROCESSING")
    void firstClaimIsGrantedAndProcessing() {
        log.info("====== 用例: firstClaimIsGrantedAndProcessing ======");
        ClaimResult result = store.claim("key-1", "hashA", LEGACY_WINDOWS);

        assertInstanceOf(ClaimResult.Granted.class, result);
        assertEquals(IdempotencyStatus.PROCESSING, store.peek("key-1").getStatus());
        log.info("用例通过: 首次抢占=Granted，状态=PROCESSING");
    }

    @Test
    @DisplayName("【并发互斥】处理中同键同内容再来请求，得到 PROCESSING 而非再次执行")
    void concurrentSameKeyGetsProcessing() {
        log.info("====== 用例: concurrentSameKeyGetsProcessing ======");
        assertInstanceOf(ClaimResult.Granted.class, store.claim("key-2", "hashA", LEGACY_WINDOWS));

        ClaimResult concurrent = store.claim("key-2", "hashA", LEGACY_WINDOWS);

        assertInstanceOf(ClaimResult.Processing.class, concurrent);
        assertEquals(10, ((ClaimResult.Processing) concurrent).retryAfterSeconds(),
                "Retry-After 应按处理窗口剩余时间给出");
        log.info("用例通过: 并发同键请求得到 Processing(retryAfter=10s)，业务不会重复执行");
    }

    @Test
    @DisplayName("【内容冲突】同键不同请求内容被拒绝，原因 REQUEST_MISMATCH")
    void sameKeyDifferentBodyIsRejected() {
        log.info("====== 用例: sameKeyDifferentBodyIsRejected ======");
        store.claim("key-3", "hashA", LEGACY_WINDOWS);

        ClaimResult mismatch = store.claim("key-3", "hash-DIFFERENT", LEGACY_WINDOWS);

        assertInstanceOf(ClaimResult.Rejected.class, mismatch);
        assertEquals(RejectionReason.REQUEST_MISMATCH,
                ((ClaimResult.Rejected) mismatch).reason());
        log.info("用例通过: 同键不同内容被拒绝，原因=REQUEST_MISMATCH");
    }

    @Test
    @DisplayName("【成功重放】完成后同键请求拿到 Replaying，含首次固化的响应")
    void succeededResultIsReplayed() {
        log.info("====== 用例: succeededResultIsReplayed ======");
        store.claim("key-4", "hashA", LEGACY_WINDOWS);
        store.complete("key-4", 201, "application/json", "{\"orderId\":\"ord_1\"}");

        ClaimResult replay = store.claim("key-4", "hashA", LEGACY_WINDOWS);

        assertInstanceOf(ClaimResult.Replaying.class, replay);
        IdempotencyRecord record = ((ClaimResult.Replaying) replay).record();
        assertEquals(201, record.getHttpStatus());
        assertEquals("{\"orderId\":\"ord_1\"}", record.getBody());
        assertEquals(IdempotencyStatus.SUCCEEDED, record.getStatus());
        log.info("用例通过: 重放结果与首次完全一致（status=201, body 一致）");
    }

    @Test
    @DisplayName("【失败重试】业务失败标记 FAILED 后，同内容重试重新获得执行权")
    void failedRecordAllowsSameBodyRetry() {
        log.info("====== 用例: failedRecordAllowsSameBodyRetry ======");
        store.claim("key-5", "hashA", LEGACY_WINDOWS);
        store.fail("key-5");
        store.releaseInFlight("key-5");
        assertEquals(IdempotencyStatus.FAILED, store.peek("key-5").getStatus());

        ClaimResult retry = store.claim("key-5", "hashA", LEGACY_WINDOWS);
        assertInstanceOf(ClaimResult.Retryable.class, retry);

        store.complete("key-5", 200, "application/json", "{\"ok\":true}");
        store.releaseInFlight("key-5");
        assertInstanceOf(ClaimResult.Replaying.class, store.claim("key-5", "hashA", LEGACY_WINDOWS));
        log.info("用例通过: FAILED → 同内容可重试 → 成功后转为可重放");
    }

    @Test
    @DisplayName("【失败重试】FAILED 记录仍在窗口内时，换请求内容重试仍被拒绝")
    void failedRecordRejectsDifferentBody() {
        log.info("====== 用例: failedRecordRejectsDifferentBody ======");
        store.claim("key-6", "hashA", LEGACY_WINDOWS);
        store.fail("key-6");
        store.releaseInFlight("key-6");

        ClaimResult mismatch = store.claim("key-6", "hashB", LEGACY_WINDOWS);
        assertInstanceOf(ClaimResult.Rejected.class, mismatch);
        assertEquals(RejectionReason.REQUEST_MISMATCH,
                ((ClaimResult.Rejected) mismatch).reason());
        log.info("用例通过: FAILED 记录不允许搭配不同请求内容重试");
    }

    @Test
    @DisplayName("【过期重放】PROCESSING 记录超过窗口且业务已结束后，同键可以重新处理")
    void expiredProcessingAllowsReprocessing() {
        log.info("====== 用例: expiredProcessingAllowsReprocessing ======");
        store.claim("key-7", "hashA", LEGACY_WINDOWS);
        store.releaseInFlight("key-7"); // 业务已结束（如实例宕机后重启则根本没有 in-flight 登记）
        store.advance(Duration.ofSeconds(11));

        ClaimResult again = store.claim("key-7", "hashA", LEGACY_WINDOWS);
        assertInstanceOf(ClaimResult.Granted.class, again);
        log.info("用例通过: 过期且无在途执行后同键重新获得 Granted");
    }

    @Test
    @DisplayName("【过期重放】SUCCEEDED 记录过期后不再重放，同键可重新提交（允许换新内容）")
    void expiredSucceededAllowsNewRequest() {
        log.info("====== 用例: expiredSucceededAllowsNewRequest ======");
        store.claim("key-8", "hashA", LEGACY_WINDOWS);
        store.complete("key-8", 200, "application/json", "{}");
        store.releaseInFlight("key-8");
        store.advance(Duration.ofSeconds(11));

        ClaimResult again = store.claim("key-8", "hash-NEW-BODY", LEGACY_WINDOWS);
        assertInstanceOf(ClaimResult.Granted.class, again);
        log.info("用例通过: 过期后旧结果不再重放，同键可用于新的请求内容");
    }

    @Test
    @DisplayName("【in-flight 护栏】处理窗口已过但首请仍在跑：同键并发仍只能得到 PROCESSING，绝不二次执行")
    void expiredProcessingButInFlightStillExclusive() {
        log.info("====== 用例: expiredProcessingButInFlightStillExclusive（核心取舍）======");
        // 首次抢占，业务一直没结束（不 releaseInFlight）
        assertInstanceOf(ClaimResult.Granted.class, store.claim("hot-key", "hashA", LEGACY_WINDOWS));
        store.advance(Duration.ofSeconds(11)); // 处理窗口已过

        // 同内容：不能 Granted
        ClaimResult same = store.claim("hot-key", "hashA", LEGACY_WINDOWS);
        assertInstanceOf(ClaimResult.Processing.class, same);
        // 即使换内容（过期后本允许绑定新内容）也不能放行第二次执行
        ClaimResult diff = store.claim("hot-key", "hashB", LEGACY_WINDOWS);
        assertInstanceOf(ClaimResult.Processing.class, diff);

        // 状态查询应明确区分 PROCESSING_TIMEOUT
        KeyStatusView view = store.inspect("hot-key");
        assertEquals(KeyState.PROCESSING_TIMEOUT, view.state());
        assertEquals(2, view.retryAfterSeconds(), "窗口已过时用兜底轮询提示");

        // 首请终于结束（在老的固定截止语义下，固化时记录已过期，迟到的成功结果不再重放，
        // 之后同键同内容按窗口过期处理——这正是“窗口只代表怀疑卡死、不代表强杀”的取舍）
        store.complete("hot-key", 200, "application/json", "{\"ok\":true}");
        store.releaseInFlight("hot-key");
        assertInstanceOf(ClaimResult.Granted.class, store.claim("hot-key", "hashA", LEGACY_WINDOWS));
        log.info("用例通过: 窗口过期 + 首请在途 → 并发请求全部 PROCESSING/查询=PROCESSING_TIMEOUT，业务零重复；"
                + "老语义下迟到固化后键按过期重新处理");

        // 分状态重算语义下，迟到的成功结果会按成功窗口重新起算，仍然可以重放
        ClockControlledStore ps = new ClockControlledStore(PER_STATE_WINDOWS, 1000);
        assertInstanceOf(ClaimResult.Granted.class, ps.claim("hot2", "hashA", PER_STATE_WINDOWS));
        ps.advance(Duration.ofSeconds(3)); // 超过 2s 处理窗口，业务还在跑
        assertInstanceOf(ClaimResult.Processing.class, ps.claim("hot2", "hashA", PER_STATE_WINDOWS));
        ps.complete("hot2", 200, "application/json", "{\"ok\":true}"); // 成功窗口从此刻起 100s
        assertInstanceOf(ClaimResult.Replaying.class, ps.claim("hot2", "hashA", PER_STATE_WINDOWS));
        log.info("用例通过: 分状态重算语义下，处理窗口过后才完成的成功结果仍可重放");
    }

    @Test
    @DisplayName("【分状态窗口】成功窗口在固化时刻重新起算：处理窗口过后成功记录仍在成功窗口内可重放")
    void perStateWindowsRebaseOnTransition() {
        log.info("====== 用例: perStateWindowsRebaseOnTransition ======");
        ClockControlledStore perState = new ClockControlledStore(PER_STATE_WINDOWS, 1000);

        // t=0 抢占，处理窗口仅 2s
        perState.claim("ps-key", "hashA", PER_STATE_WINDOWS);
        perState.advance(Duration.ofSeconds(1)); // 业务跑了 1s
        perState.complete("ps-key", 200, "application/json", "{}"); // 成功窗口从此刻起 100s
        perState.releaseInFlight("ps-key");

        // 距离抢占已过去 3s（超过 2s 处理窗口），但成功记录刚起算，仍然有效可重放
        perState.advance(Duration.ofSeconds(2));
        assertInstanceOf(ClaimResult.Replaying.class, perState.claim("ps-key", "hashA", PER_STATE_WINDOWS));
        log.info("用例通过: 成功窗口独立起算，不受较短的处理窗口影响");

        // 失败窗口独立：t=0 抢占另一个键 → 立即失败（失败窗口 5s 从失败时刻起算）
        perState.claim("ps-fail", "hashA", PER_STATE_WINDOWS);
        perState.fail("ps-fail");
        perState.releaseInFlight("ps-fail");
        perState.advance(Duration.ofSeconds(4));
        assertInstanceOf(ClaimResult.Retryable.class, perState.claim("ps-fail", "hashA", PER_STATE_WINDOWS));
        perState.advance(Duration.ofSeconds(2)); // 重试后处理窗口 2s 已过且业务已结束
        perState.releaseInFlight("ps-fail");
        perState.advance(Duration.ofSeconds(1));
        // 距上次失败标记超过 5s，失败记录过期 → 可重新提交
        assertInstanceOf(ClaimResult.Granted.class, perState.claim("ps-fail", "hashA", PER_STATE_WINDOWS));
        log.info("用例通过: 失败窗口从失败时刻独立起算，过期后同键可重新提交");
    }

    @Test
    @DisplayName("【容量淘汰】容量满：先清过期记录；再淘汰最老终态记录；被淘汰键重新提交按新请求处理")
    void capacityEvictionKeepsCorrectness() {
        log.info("====== 用例: capacityEvictionKeepsCorrectness ======");
        ClockControlledStore small = new ClockControlledStore(LEGACY_WINDOWS, 3);
        // 造 3 条成功记录（终态），占满容量
        for (int i = 1; i <= 3; i++) {
            small.claim("k" + i, "h" + i, LEGACY_WINDOWS);
            small.complete("k" + i, 200, "application/json", "body-" + i);
            small.releaseInFlight("k" + i);
        }
        assertEquals(3, small.currentSize());

        // 第 4 个新键：容量满且没有过期记录 → 淘汰最老的 k1，新键抢占成功
        assertInstanceOf(ClaimResult.Granted.class, small.claim("k4", "h4", LEGACY_WINDOWS));
        assertEquals(3, small.currentSize());
        KeyStatusView evictedView = small.inspect("k1");
        assertEquals(KeyState.NOT_FOUND, evictedView.state(), "被淘汰的键查询为 NOT_FOUND");

        // 被淘汰的键重新提交：不报错、也不重放旧结果，而是作为全新请求 Granted
        ClaimResult resubmit = small.claim("k1", "h1", LEGACY_WINDOWS);
        // k1 此时在容量中又满了…… 会继续淘汰当前最老终态（k2），k1 仍应 Granted
        assertInstanceOf(ClaimResult.Granted.class, resubmit);
        assertEquals(3, small.currentSize());
        log.info("用例通过: 容量满时按“先过期、再最老终态”淘汰；被淘汰键重新提交=全新 Granted，不重放旧结果");
    }

    @Test
    @DisplayName("【容量淘汰】处理中记录不被强制淘汰：容量全被处理中占满时 fail-closed 拒绝新抢占")
    void processingRecordsAreNeverEvicted() {
        log.info("====== 用例: processingRecordsAreNeverEvicted ======");
        ClockControlledStore tiny = new ClockControlledStore(LEGACY_WINDOWS, 2);
        tiny.claim("p1", "h1", LEGACY_WINDOWS); // 不 release：业务在途
        tiny.claim("p2", "h2", LEGACY_WINDOWS); // 不 release：业务在途
        assertEquals(2, tiny.currentSize());

        IdempotencyStorageException ex = assertThrows(IdempotencyStorageException.class,
                () -> tiny.claim("p3", "h3", LEGACY_WINDOWS));
        assertTrue(ex.getMessage().contains("容量"), "异常信息应说明是容量原因: " + ex.getMessage());
        assertEquals(2, tiny.currentSize(), "处理中记录一条都不能被挤掉");
        log.info("用例通过: 处理中记录免疫淘汰；容量被处理中占满时抛存储异常走 503 fail-closed");
    }

    @Test
    @DisplayName("【状态查询】五种分支：不存在/处理中/成功/失败可重试/已过期")
    void inspectCoversAllBranches() {
        log.info("====== 用例: inspectCoversAllBranches ======");
        assertEquals(KeyState.NOT_FOUND, store.inspect("never").state());

        store.claim("wk", "hashA", LEGACY_WINDOWS);
        KeyStatusView processing = store.inspect("wk");
        assertEquals(KeyState.PROCESSING, processing.state());
        assertEquals(10, processing.retryAfterSeconds());
        assertTrue(processing.expiresAtEpochMillis() > 0);

        store.complete("wk", 200, "application/json", "{}");
        assertEquals(KeyState.SUCCEEDED, store.inspect("wk").state());

        store.claim("wf", "hashA", LEGACY_WINDOWS);
        store.fail("wf");
        store.releaseInFlight("wf");
        assertEquals(KeyState.FAILED_RETRYABLE, store.inspect("wf").state());

        store.advance(Duration.ofSeconds(11));
        assertEquals(KeyState.NOT_FOUND, store.inspect("wk").state(), "成功记录过期=NOT_FOUND（可新提交）");
        assertEquals(KeyState.NOT_FOUND, store.inspect("wf").state(), "失败记录过期=NOT_FOUND");
        log.info("用例通过: NOT_FOUND / PROCESSING / SUCCEEDED / FAILED_RETRYABLE / 过期 分支全部正确");
    }

    @Test
    @DisplayName("【存储异常】对不存在（已过期/淘汰）的键固化成功结果，抛存储异常以走兜底")
    void completeMissingKeyThrowsStorageException() {
        log.info("====== 用例: completeMissingKeyThrowsStorageException ======");
        IdempotencyStorageException ex = assertThrows(IdempotencyStorageException.class,
                () -> store.complete("missing", 200, "application/json", "{}"));
        log.info("用例通过: 缺失记录固化失败抛出 IdempotencyStorageException: {}",
                ex.getMessage());
    }

    @Test
    @DisplayName("【存储异常】重复固化已成功的记录被拒绝，防止结果被覆盖")
    void doubleCompleteThrowsStorageException() {
        log.info("====== 用例: doubleCompleteThrowsStorageException ======");
        store.claim("key-9", "hashA", LEGACY_WINDOWS);
        store.complete("key-9", 200, "application/json", "first");

        assertThrows(IdempotencyStorageException.class,
                () -> store.complete("key-9", 200, "application/json", "second"));
        IdempotencyRecord record = store.peek("key-9");
        assertEquals("first", record.getBody());
        log.info("用例通过: 重复固化被阻止，首次结果不被覆盖");
    }
}
