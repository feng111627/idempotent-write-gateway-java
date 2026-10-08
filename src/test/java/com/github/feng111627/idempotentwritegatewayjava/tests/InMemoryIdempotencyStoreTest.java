package com.github.feng111627.idempotentwritegatewayjava.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.github.feng111627.idempotentwritegatewayjava.idempotency.ClaimResult;
import com.github.feng111627.idempotentwritegatewayjava.idempotency.IdempotencyConfig;
import com.github.feng111627.idempotentwritegatewayjava.idempotency.IdempotencyRecord;
import com.github.feng111627.idempotentwritegatewayjava.idempotency.IdempotencyStatus;
import com.github.feng111627.idempotentwritegatewayjava.idempotency.IdempotencyStorageException;
import com.github.feng111627.idempotentwritegatewayjava.idempotency.InMemoryIdempotencyStore;
import com.github.feng111627.idempotentwritegatewayjava.idempotency.RejectionReason;
import com.github.feng111627.idempotentwritegatewayjava.idempotency.RetentionPolicy;

/**
 * 存储层单元测试：覆盖抢占、并发互斥语义、成功重放、失败重试、过期重放与存储异常。
 * 使用统一 10s 的策略（新配置模式，三段时长一致），便于复用原过期断言。
 */
class InMemoryIdempotencyStoreTest {

    private static final Logger log = LoggerFactory.getLogger(InMemoryIdempotencyStoreTest.class);

    /** 可控时钟，避免真实 sleep 来验证过期逻辑。 */
    private static final class ClockControlledStore extends InMemoryIdempotencyStore {
        private final AtomicLong now = new AtomicLong(1_000_000L);

        ClockControlledStore(RetentionPolicy policy) {
            super(policy);
        }

        @Override
        protected long nowMillis() {
            return now.get();
        }

        void advance(Duration duration) {
            now.addAndGet(duration.toMillis());
        }
    }

    private ClockControlledStore store;
    private static final RetentionPolicy POLICY = new RetentionPolicy(
            Duration.ofSeconds(10), Duration.ofSeconds(10), Duration.ofSeconds(10),
            10_000, false);

    @BeforeEach
    void setUp() {
        store = new ClockControlledStore(POLICY);
    }

    /** 模拟一次“执行完但结果未固化”的结束：释放在途护栏，记录保留原状。 */
    private void finishWithoutPersist(String key) {
        store.releaseInFlight(key);
    }

    @Test
    @DisplayName("【抢占】首次提交获得执行权，记录为 PROCESSING")
    void firstClaimIsGrantedAndProcessing() {
        log.info("====== 用例: firstClaimIsGrantedAndProcessing ======");
        ClaimResult result = store.claim("key-1", "hashA", POLICY);

        assertInstanceOf(ClaimResult.Granted.class, result);
        assertEquals(IdempotencyStatus.PROCESSING, store.peek("key-1").getStatus());
        log.info("用例通过: 首次抢占=Granted，状态=PROCESSING");
    }

    @Test
    @DisplayName("【并发互斥】处理中同键同内容再来请求，得到 PROCESSING 而非再次执行")
    void concurrentSameKeyGetsProcessing() {
        log.info("====== 用例: concurrentSameKeyGetsProcessing ======");
        assertInstanceOf(ClaimResult.Granted.class, store.claim("key-2", "hashA", POLICY));

        ClaimResult concurrent = store.claim("key-2", "hashA", POLICY);

        assertInstanceOf(ClaimResult.Processing.class, concurrent);
        log.info("用例通过: 并发同键请求得到 Processing，业务不会重复执行");
    }

    @Test
    @DisplayName("【内容冲突】同键不同请求内容被拒绝，原因 REQUEST_MISMATCH")
    void sameKeyDifferentBodyIsRejected() {
        log.info("====== 用例: sameKeyDifferentBodyIsRejected ======");
        store.claim("key-3", "hashA", POLICY);

        ClaimResult mismatch = store.claim("key-3", "hash-DIFFERENT", POLICY);

        assertInstanceOf(ClaimResult.Rejected.class, mismatch);
        assertEquals(RejectionReason.REQUEST_MISMATCH,
                ((ClaimResult.Rejected) mismatch).reason());
        log.info("用例通过: 同键不同内容被拒绝，原因=REQUEST_MISMATCH");
    }

    @Test
    @DisplayName("【成功重放】完成后同键请求拿到 Replaying，含首次固化的响应")
    void succeededResultIsReplayed() {
        log.info("====== 用例: succeededResultIsReplayed ======");
        store.claim("key-4", "hashA", POLICY);
        store.complete("key-4", 201, "application/json", "{\"orderId\":\"ord_1\"}");
        finishWithoutPersist("key-4");

        ClaimResult replay = store.claim("key-4", "hashA", POLICY);

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
        store.claim("key-5", "hashA", POLICY);
        store.fail("key-5");
        finishWithoutPersist("key-5");
        assertEquals(IdempotencyStatus.FAILED, store.peek("key-5").getStatus());

        ClaimResult retry = store.claim("key-5", "hashA", POLICY);
        assertInstanceOf(ClaimResult.Retryable.class, retry);

        store.complete("key-5", 200, "application/json", "{\"ok\":true}");
        finishWithoutPersist("key-5");
        assertInstanceOf(ClaimResult.Replaying.class, store.claim("key-5", "hashA", POLICY));
        log.info("用例通过: FAILED → 同内容可重试 → 成功后转为可重放");
    }

    @Test
    @DisplayName("【失败重试】FAILED 记录仍在保留窗口内时，换请求内容重试仍被拒绝")
    void failedRecordRejectsDifferentBody() {
        log.info("====== 用例: failedRecordRejectsDifferentBody ======");
        store.claim("key-6", "hashA", POLICY);
        store.fail("key-6");
        finishWithoutPersist("key-6");

        ClaimResult mismatch = store.claim("key-6", "hashB", POLICY);
        assertInstanceOf(ClaimResult.Rejected.class, mismatch);
        assertEquals(RejectionReason.REQUEST_MISMATCH,
                ((ClaimResult.Rejected) mismatch).reason());
        log.info("用例通过: FAILED 记录不允许搭配不同请求内容重试");
    }

    @Test
    @DisplayName("【过期重放】首请已结束、PROCESSING 记录超过窗口后，同键可以重新处理")
    void expiredProcessingAllowsReprocessing() {
        log.info("====== 用例: expiredProcessingAllowsReprocessing ======");
        store.claim("key-7", "hashA", POLICY);
        finishWithoutPersist("key-7");
        store.advance(Duration.ofSeconds(11));

        ClaimResult again = store.claim("key-7", "hashA", POLICY);
        assertInstanceOf(ClaimResult.Granted.class, again);
        log.info("用例通过: 过期后同键重新获得 Granted");
    }

    @Test
    @DisplayName("【过期重放】SUCCEEDED 记录过期后不再重放，同键可重新提交（允许换新内容）")
    void expiredSucceededAllowsNewRequest() {
        log.info("====== 用例: expiredSucceededAllowsNewRequest ======");
        store.claim("key-8", "hashA", POLICY);
        store.complete("key-8", 200, "application/json", "{}");
        finishWithoutPersist("key-8");
        store.advance(Duration.ofSeconds(11));

        ClaimResult again = store.claim("key-8", "hash-NEW-BODY", POLICY);
        assertInstanceOf(ClaimResult.Granted.class, again);
        log.info("用例通过: 过期后旧结果不再重放，同键可用于新的请求内容");
    }

    // ===================== 本轮新增：分状态保留时长 =====================

    @Test
    @DisplayName("【分状态TTL】成功后按 succeededTtl 重新锚定过期时刻，成功记录比处理中留得更久")
    void succeededReanchorsToSucceededTtl() {
        log.info("====== 用例: succeededReanchorsToSucceededTtl ======");
        RetentionPolicy tiered = new RetentionPolicy(
                Duration.ofSeconds(2), Duration.ofSeconds(100), Duration.ofSeconds(10),
                10_000, false);
        ClockControlledStore s2 = new ClockControlledStore(tiered);

        s2.claim("k-s", "hashA", tiered);
        long procExpiry = s2.peek("k-s").getExpiresAtMillis();
        // 推进到处理中窗口之外、但首请很快完成（在护栏内固化成功）
        s2.advance(Duration.ofSeconds(1));
        s2.complete("k-s", 200, "application/json", "{}");
        s2.releaseInFlight("k-s");

        IdempotencyRecord rec = s2.peek("k-s");
        assertEquals(IdempotencyStatus.SUCCEEDED, rec.getStatus());
        // 成功后的过期时刻 = 固化时刻 + succeededTtl，明显晚于原 processing 过期时刻
        org.junit.jupiter.api.Assertions.assertTrue(rec.getExpiresAtMillis() > procExpiry,
                "成功记录应按 succeededTtl 重新锚定过期时刻");
        // 超过原处理中窗口 2s、但仍在成功窗口内：应重放
        s2.advance(Duration.ofSeconds(2));
        assertInstanceOf(ClaimResult.Replaying.class, s2.claim("k-s", "hashA", tiered));
        // 超过成功窗口 100s：不再重放，可重新提交
        s2.advance(Duration.ofSeconds(100));
        assertInstanceOf(ClaimResult.Granted.class, s2.claim("k-s", "hashA", tiered));
        log.info("用例通过: 成功按 succeededTtl 锚定；处理中窗口过后仍重放；成功窗口过后可重新提交");
    }

    @Test
    @DisplayName("【分状态TTL】失败后按 failedTtl 重新锚定，重试成功后再按 succeededTtl 留久")
    void failedReanchorsToFailedTtl() {
        log.info("====== 用例: failedReanchorsToFailedTtl ======");
        RetentionPolicy tiered = new RetentionPolicy(
                Duration.ofSeconds(2), Duration.ofSeconds(100), Duration.ofSeconds(30),
                10_000, false);
        ClockControlledStore s2 = new ClockControlledStore(tiered);

        s2.claim("k-f", "hashA", tiered);
        s2.fail("k-f");
        s2.releaseInFlight("k-f");

        // 处理中窗口(2s)过后但失败窗口(30s)内：仍按 FAILED 处理，同内容可重试
        s2.advance(Duration.ofSeconds(5));
        ClaimResult retry = s2.claim("k-f", "hashA", tiered);
        assertInstanceOf(ClaimResult.Retryable.class, retry);

        // 这次成功
        s2.complete("k-f", 200, "application/json", "{}");
        s2.releaseInFlight("k-f");
        s2.advance(Duration.ofSeconds(40)); // 远超失败窗口、在成功窗口内
        assertInstanceOf(ClaimResult.Replaying.class, s2.claim("k-f", "hashA", tiered));
        log.info("用例通过: FAILED 按 failedTtl 锚定重试窗口；重试成功后按 succeededTtl 长期重放");
    }

    @Test
    @DisplayName("【分状态TTL】失败窗口过期后，同键可以重新提交（三种窗口过期均可重新提交）")
    void failedWindowExpiryAllowsResubmit() {
        log.info("====== 用例: failedWindowExpiryAllowsResubmit ======");
        RetentionPolicy tiered = new RetentionPolicy(
                Duration.ofSeconds(2), Duration.ofSeconds(100), Duration.ofSeconds(30),
                10_000, false);
        ClockControlledStore s2 = new ClockControlledStore(tiered);

        s2.claim("k-fe", "hashA", tiered);
        s2.fail("k-fe");
        s2.releaseInFlight("k-fe");
        s2.advance(Duration.ofSeconds(31)); // 超过失败窗口

        // 过期后同键同内容：作为全新提交 Granted，而不是 Rejected/Processing
        assertInstanceOf(ClaimResult.Granted.class, s2.claim("k-fe", "hashA", tiered));
        log.info("用例通过: 失败窗口过期后同键可重新提交");
    }

    // ===================== 本轮新增：处理中窗口到期但首请仍在跑 =====================

    @Test
    @DisplayName("【护栏安全】处理中窗口已过但首请仍在真实执行：同键新请求只能 PROCESSING，绝不二次执行")
    void expiredProcessingWindowButStillInFlightIsProtected() {
        log.info("====== 用例: expiredProcessingWindowButStillInFlightIsProtected ======");
        RetentionPolicy shortProc = new RetentionPolicy(
                Duration.ofSeconds(2), Duration.ofSeconds(100), Duration.ofSeconds(30),
                10_000, false);
        ClockControlledStore s2 = new ClockControlledStore(shortProc);

        // 首请抢到执行权（注意：此时不调用 releaseInFlight，模拟业务仍在跑）
        assertInstanceOf(ClaimResult.Granted.class, s2.claim("k-live", "hashA", shortProc));
        s2.advance(Duration.ofSeconds(3)); // 处理中窗口已过，业务“还在跑”

        ClaimResult late = s2.claim("k-live", "hashA", shortProc);
        assertInstanceOf(ClaimResult.Processing.class, late,
                "窗口到期不等于请求结束：首请仍在跑时必须继续挡住同键请求");
        ClaimResult.Processing p = (ClaimResult.Processing) late;
        org.junit.jupiter.api.Assertions.assertTrue(p.retryAfterSeconds() >= 1,
                "处理中响应应给出可执行的重试建议秒数");
        // 即使来的是“新内容”，也绝不能在首请未结束时抢占，仍返回处理中而非 MISMATCH 后放行
        assertInstanceOf(ClaimResult.Processing.class, s2.claim("k-live", "hashOTHER", shortProc));

        // 首请终于执行成功：跨窗口固化为 SUCCEEDED，此后同键请求重放，业务不重复
        s2.complete("k-live", 200, "application/json", "{\"orderId\":\"final\"}");
        s2.releaseInFlight("k-live");
        ClaimResult replay = s2.claim("k-live", "hashA", shortProc);
        assertInstanceOf(ClaimResult.Replaying.class, replay);
        assertEquals("{\"orderId\":\"final\"}",
                ((ClaimResult.Replaying) replay).record().getBody());
        log.info("用例通过: 首请跨处理中窗口仍在执行期间同键请求一律 PROCESSING；完成后重放，业务零重复");
    }

    @Test
    @DisplayName("【护栏安全】首请跨过处理中窗口才失败：护栏期不放行，结束后按 FAILED 窗口重试")
    void inFlightFailureAcrossWindowThenRetry() {
        log.info("====== 用例: inFlightFailureAcrossWindowThenRetry ======");
        RetentionPolicy shortProc = new RetentionPolicy(
                Duration.ofSeconds(2), Duration.ofSeconds(100), Duration.ofSeconds(30),
                10_000, false);
        ClockControlledStore s2 = new ClockControlledStore(shortProc);

        assertInstanceOf(ClaimResult.Granted.class, s2.claim("k-x", "hashA", shortProc));
        s2.advance(Duration.ofSeconds(3));
        // 业务还在跑期间：挡住
        assertInstanceOf(ClaimResult.Processing.class, s2.claim("k-x", "hashA", shortProc));
        // 业务失败结束：重新锚定 FAILED
        s2.fail("k-x");
        s2.releaseInFlight("k-x");
        assertEquals(IdempotencyStatus.FAILED, s2.peek("k-x").getStatus());
        // 同内容可重试
        assertInstanceOf(ClaimResult.Retryable.class, s2.claim("k-x", "hashA", shortProc));
        log.info("用例通过: 跨窗口失败在护栏期不放行；结束后进入 FAILED 窗口允许同内容重试");
    }

    @Test
    @DisplayName("【护栏安全】首请结束并释放护栏后，处理中窗口已过的记录允许重新处理")
    void afterReleaseAndWindowExpiryKeyReusable() {
        log.info("====== 用例: afterReleaseAndWindowExpiryKeyReusable ======");
        RetentionPolicy shortProc = new RetentionPolicy(
                Duration.ofSeconds(2), Duration.ofSeconds(100), Duration.ofSeconds(30),
                10_000, false);
        ClockControlledStore s2 = new ClockControlledStore(shortProc);

        assertInstanceOf(ClaimResult.Granted.class, s2.claim("k-r", "hashA", shortProc));
        // 模拟“结果固化也失败”的兜底：只释放护栏，记录仍是 PROCESSING
        s2.releaseInFlight("k-r");
        s2.advance(Duration.ofSeconds(3));

        // 护栏已释放且窗口已过：同键可以重新处理
        assertInstanceOf(ClaimResult.Granted.class, s2.claim("k-r", "hashA", shortProc));
        log.info("用例通过: 兜底场景护栏释放后，处理中窗口到期键重新可用");
    }

    // ===================== 本轮新增：容量上限与淘汰 =====================

    @Test
    @DisplayName("【容量淘汰】容量满时优先淘汰过期记录，释放槽位给新键")
    void evictionRemovesExpiredFirst() {
        log.info("====== 用例: evictionRemovesExpiredFirst ======");
        RetentionPolicy cap = new RetentionPolicy(
                Duration.ofSeconds(10), Duration.ofSeconds(10), Duration.ofSeconds(10),
                3, false);
        ClockControlledStore s3 = new ClockControlledStore(cap);

        s3.claim("a", "h", cap);
        s3.complete("a", 200, "application/json", "{}");
        s3.releaseInFlight("a");
        s3.advance(Duration.ofSeconds(11)); // a 先过期
        // b、c 在 a 过期之后创建
        s3.claim("b", "h", cap);
        s3.complete("b", 200, "application/json", "{}");
        s3.releaseInFlight("b");
        s3.claim("c", "h", cap);
        s3.complete("c", 200, "application/json", "{}");
        s3.releaseInFlight("c");
        // 此刻 size=3（a 已过期但仍占槽）
        // 第 4 个键到达：容量满，但 a 已过期应被清理，新键正常 Granted
        ClaimResult granted = s3.claim("d", "h", cap);
        assertInstanceOf(ClaimResult.Granted.class, granted);
        org.junit.jupiter.api.Assertions.assertNull(s3.peek("a"), "过期记录 a 应被淘汰");
        org.junit.jupiter.api.Assertions.assertEquals(3, s3.currentSize(), "总量仍受上限约束");
        log.info("用例通过: 容量触顶先清过期记录，新键照常抢占且总数不超上限");
    }

    @Test
    @DisplayName("【容量淘汰】无过期记录时淘汰最老的终态记录，处理中记录绝不被淘汰")
    void evictionPicksOldestTerminalNeverProcessing() {
        log.info("====== 用例: evictionPicksOldestTerminalNeverProcessing ======");
        RetentionPolicy cap = new RetentionPolicy(
                Duration.ofSeconds(1000), Duration.ofSeconds(1000), Duration.ofSeconds(1000),
                3, false);
        ClockControlledStore s3 = new ClockControlledStore(cap);

        // 一条受保护的处理中记录（业务在跑，不释放护栏）
        s3.claim("proc", "h", cap);
        // 两条终态成功记录
        s3.claim("succ1", "h", cap);
        s3.complete("succ1", 200, "application/json", "{}");
        s3.releaseInFlight("succ1");
        s3.advance(Duration.ofMillis(1));
        s3.claim("succ2", "h", cap);
        s3.complete("succ2", 200, "application/json", "{}");
        s3.releaseInFlight("succ2");
        assertEquals(3, s3.currentSize());

        // 再来新键：只能淘汰终态记录（succ1 更老），proc 必须留下
        assertInstanceOf(ClaimResult.Granted.class, s3.claim("newbie", "h", cap));
        org.junit.jupiter.api.Assertions.assertNotNull(s3.peek("proc"),
                "处理中（在途）记录绝不能因容量被淘汰，否则业务可能被执行两遍");
        org.junit.jupiter.api.Assertions.assertNull(s3.peek("succ1"), "最老终态记录 succ1 应被淘汰");
        org.junit.jupiter.api.Assertions.assertNotNull(s3.peek("succ2"));
        log.info("用例通过: 容量满淘汰最老终态记录；在途处理中记录受保护不淘汰");
    }

    @Test
    @DisplayName("【容量淘汰】被淘汰的键再次提交时表现为全新提交（Granted），不报错也不重放旧结果")
    void evictedKeyBehavesAsFreshSubmission() {
        log.info("====== 用例: evictedKeyBehavesAsFreshSubmission ======");
        RetentionPolicy cap = new RetentionPolicy(
                Duration.ofSeconds(1000), Duration.ofSeconds(1000), Duration.ofSeconds(1000),
                2, false);
        ClockControlledStore s3 = new ClockControlledStore(cap);

        s3.claim("victim", "h", cap);
        s3.complete("victim", 200, "application/json", "{\"v\":1}");
        s3.releaseInFlight("victim");
        s3.claim("other1", "h", cap);
        s3.complete("other1", 200, "application/json", "{}");
        s3.releaseInFlight("other1");
        // 容量=2 已满；新键触发淘汰，victim（更老）被踢
        s3.claim("other2", "h", cap);
        s3.complete("other2", 200, "application/json", "{}");
        s3.releaseInFlight("other2");
        org.junit.jupiter.api.Assertions.assertNull(s3.peek("victim"));

        // victim 再次提交（即使是同一内容）：Granted 全新处理，而不是 Replaying 旧结果
        ClaimResult again = s3.claim("victim", "h", cap);
        assertInstanceOf(ClaimResult.Granted.class, again);
        log.info("用例通过: 被淘汰键重新提交=Granted 全新处理，不报错、不重放旧结果");
    }

    @Test
    @DisplayName("【容量兜底】容量满且剩余全是受保护处理中记录时，fail-closed 返回 CAPACITY_FULL")
    void capacityFullWithOnlyProcessingRejectsFailClosed() {
        log.info("====== 用例: capacityFullWithOnlyProcessingRejectsFailClosed ======");
        RetentionPolicy cap = new RetentionPolicy(
                Duration.ofSeconds(1000), Duration.ofSeconds(1000), Duration.ofSeconds(1000),
                2, false);
        ClockControlledStore s3 = new ClockControlledStore(cap);
        s3.claim("p1", "h", cap); // 不释放护栏，模拟两条都在执行
        s3.claim("p2", "h", cap);
        assertEquals(2, s3.currentSize());

        ClaimResult rejected = s3.claim("p3", "h", cap);
        assertInstanceOf(ClaimResult.Rejected.class, rejected);
        assertEquals(RejectionReason.CAPACITY_FULL, ((ClaimResult.Rejected) rejected).reason());
        assertEquals(2, s3.currentSize(), "拒绝时不得挤掉任何处理中记录");
        log.info("用例通过: 容量满且只剩受保护处理中记录 → CAPACITY_FULL fail-closed，记录不被踢");
    }

    @Test
    @DisplayName("【容量边界并发】多个不同键同时打满容量：被接受的都真正执行，被淘汰的终态不影响正确性")
    void concurrentClaimsAtCapacityBoundaryNeverDuplicatePerKey() throws Exception {
        log.info("====== 用例: concurrentClaimsAtCapacityBoundaryNeverDuplicatePerKey ======");
        RetentionPolicy cap = new RetentionPolicy(
                Duration.ofSeconds(1000), Duration.ofSeconds(1000), Duration.ofSeconds(1000),
                50, false);
        final InMemoryIdempotencyStore live = new InMemoryIdempotencyStore(cap);
        int threads = 200;
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(20);
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        java.util.List<java.util.concurrent.Future<ClaimResult>> futures = new java.util.ArrayList<>();
        for (int i = 0; i < threads; i++) {
            final String k = "bulk-" + i;
            futures.add(pool.submit(() -> {
                start.await();
                return live.claim(k, "h", cap);
            }));
        }
        start.countDown();
        int granted = 0;
        int capacityRejected = 0;
        for (java.util.concurrent.Future<ClaimResult> f : futures) {
            ClaimResult r = f.get();
            if (r instanceof ClaimResult.Granted) {
                granted++;
            } else if (r instanceof ClaimResult.Rejected rr && rr.reason() == RejectionReason.CAPACITY_FULL) {
                capacityRejected++;
            }
        }
        pool.shutdown();
        // 每个不同键最多被接受一次；被接受数不超过容量
        org.junit.jupiter.api.Assertions.assertTrue(granted <= 50, "接受数不得超过容量");
        org.junit.jupiter.api.Assertions.assertEquals(threads, granted + capacityRejected,
                "所有请求要么被接受要么 CAPACITY_FULL，无其他结果");
        org.junit.jupiter.api.Assertions.assertEquals(granted, live.currentSize(),
                "每个被接受的不同键恰好一条处理中记录");
        log.info("用例通过: 容量边界并发下 {} 个不同键：接受={}（每键最多一次真实执行），CAPACITY_FULL={}",
                threads, granted, capacityRejected);
    }

    @Test
    @DisplayName("【存储异常】老配置模式下对不存在（已过期）的键固化成功结果，抛存储异常以走兜底")
    void completeMissingKeyThrowsStorageException() {
        log.info("====== 用例: completeMissingKeyThrowsStorageException ======");
        ClockControlledStore legacyStore = new ClockControlledStore(new RetentionPolicy(
                Duration.ofSeconds(10), Duration.ofSeconds(10), Duration.ofSeconds(10),
                10_000, true));
        IdempotencyStorageException ex = assertThrows(IdempotencyStorageException.class,
                () -> legacyStore.complete("missing", 200, "application/json", "{}"));
        log.info("用例通过: 老模式缺失记录固化失败抛出 IdempotencyStorageException: {}",
                ex.getMessage());
    }

    @Test
    @DisplayName("【存储异常】重复固化已成功的记录被拒绝，防止结果被覆盖")
    void doubleCompleteThrowsStorageException() {
        log.info("====== 用例: doubleCompleteThrowsStorageException ======");
        store.claim("key-9", "hashA", POLICY);
        store.complete("key-9", 200, "application/json", "first");
        finishWithoutPersist("key-9");

        assertThrows(IdempotencyStorageException.class,
                () -> store.complete("key-9", 200, "application/json", "second"));
        IdempotencyRecord record = store.peek("key-9");
        assertEquals("first", record.getBody());
        log.info("用例通过: 重复固化被阻止，首次结果不被覆盖");
    }
}
