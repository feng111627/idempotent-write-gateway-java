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
import com.github.feng111627.idempotentwritegatewayjava.idempotency.IdempotencyRecord;
import com.github.feng111627.idempotentwritegatewayjava.idempotency.IdempotencyStatus;
import com.github.feng111627.idempotentwritegatewayjava.idempotency.IdempotencyStorageException;
import com.github.feng111627.idempotentwritegatewayjava.idempotency.InMemoryIdempotencyStore;
import com.github.feng111627.idempotentwritegatewayjava.idempotency.RejectionReason;

/**
 * 存储层单元测试：覆盖抢占、并发互斥语义、成功重放、失败重试、过期重放与存储异常。
 */
class InMemoryIdempotencyStoreTest {

    private static final Logger log = LoggerFactory.getLogger(InMemoryIdempotencyStoreTest.class);

    /** 可控时钟，避免真实 sleep 来验证过期逻辑。 */
    private static final class ClockControlledStore extends InMemoryIdempotencyStore {
        private final AtomicLong now = new AtomicLong(1_000_000L);

        @Override
        protected long nowMillis() {
            return now.get();
        }

        void advance(Duration duration) {
            now.addAndGet(duration.toMillis());
        }
    }

    private ClockControlledStore store;
    private static final Duration TTL = Duration.ofSeconds(10);

    @BeforeEach
    void setUp() {
        store = new ClockControlledStore();
    }

    @Test
    @DisplayName("【抢占】首次提交获得执行权，记录为 PROCESSING")
    void firstClaimIsGrantedAndProcessing() {
        log.info("====== 用例: firstClaimIsGrantedAndProcessing ======");
        ClaimResult result = store.claim("key-1", "hashA", TTL);

        assertInstanceOf(ClaimResult.Granted.class, result);
        assertEquals(IdempotencyStatus.PROCESSING, store.peek("key-1").getStatus());
        log.info("用例通过: 首次抢占=Granted，状态=PROCESSING");
    }

    @Test
    @DisplayName("【并发互斥】处理中同键同内容再来请求，得到 PROCESSING 而非再次执行")
    void concurrentSameKeyGetsProcessing() {
        log.info("====== 用例: concurrentSameKeyGetsProcessing ======");
        assertInstanceOf(ClaimResult.Granted.class, store.claim("key-2", "hashA", TTL));

        ClaimResult concurrent = store.claim("key-2", "hashA", TTL);

        assertInstanceOf(ClaimResult.Processing.class, concurrent);
        log.info("用例通过: 并发同键请求得到 Processing，业务不会重复执行");
    }

    @Test
    @DisplayName("【内容冲突】同键不同请求内容被拒绝，原因 REQUEST_MISMATCH")
    void sameKeyDifferentBodyIsRejected() {
        log.info("====== 用例: sameKeyDifferentBodyIsRejected ======");
        store.claim("key-3", "hashA", TTL);

        ClaimResult mismatch = store.claim("key-3", "hash-DIFFERENT", TTL);

        assertInstanceOf(ClaimResult.Rejected.class, mismatch);
        assertEquals(RejectionReason.REQUEST_MISMATCH,
                ((ClaimResult.Rejected) mismatch).reason());
        log.info("用例通过: 同键不同内容被拒绝，原因=REQUEST_MISMATCH");
    }

    @Test
    @DisplayName("【成功重放】完成后同键请求拿到 Replaying，含首次固化的响应")
    void succeededResultIsReplayed() {
        log.info("====== 用例: succeededResultIsReplayed ======");
        store.claim("key-4", "hashA", TTL);
        store.complete("key-4", 201, "application/json", "{\"orderId\":\"ord_1\"}");

        ClaimResult replay = store.claim("key-4", "hashA", TTL);

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
        store.claim("key-5", "hashA", TTL);
        store.fail("key-5");
        assertEquals(IdempotencyStatus.FAILED, store.peek("key-5").getStatus());

        ClaimResult retry = store.claim("key-5", "hashA", TTL);
        assertInstanceOf(ClaimResult.Retryable.class, retry);

        store.complete("key-5", 200, "application/json", "{\"ok\":true}");
        assertInstanceOf(ClaimResult.Replaying.class, store.claim("key-5", "hashA", TTL));
        log.info("用例通过: FAILED → 同内容可重试 → 成功后转为可重放");
    }

    @Test
    @DisplayName("【失败重试】FAILED 记录仍在 TTL 内时，换请求内容重试仍被拒绝")
    void failedRecordRejectsDifferentBody() {
        log.info("====== 用例: failedRecordRejectsDifferentBody ======");
        store.claim("key-6", "hashA", TTL);
        store.fail("key-6");

        ClaimResult mismatch = store.claim("key-6", "hashB", TTL);
        assertInstanceOf(ClaimResult.Rejected.class, mismatch);
        assertEquals(RejectionReason.REQUEST_MISMATCH,
                ((ClaimResult.Rejected) mismatch).reason());
        log.info("用例通过: FAILED 记录不允许搭配不同请求内容重试");
    }

    @Test
    @DisplayName("【过期重放】PROCESSING 记录超过 TTL 后，同键可以重新处理")
    void expiredProcessingAllowsReprocessing() {
        log.info("====== 用例: expiredProcessingAllowsReprocessing ======");
        store.claim("key-7", "hashA", TTL);
        store.advance(Duration.ofSeconds(11));

        ClaimResult again = store.claim("key-7", "hashA", TTL);
        assertInstanceOf(ClaimResult.Granted.class, again);
        log.info("用例通过: 过期后同键重新获得 Granted");
    }

    @Test
    @DisplayName("【过期重放】SUCCEEDED 记录过期后不再重放，同键可重新提交（允许换新内容）")
    void expiredSucceededAllowsNewRequest() {
        log.info("====== 用例: expiredSucceededAllowsNewRequest ======");
        store.claim("key-8", "hashA", TTL);
        store.complete("key-8", 200, "application/json", "{}");
        store.advance(Duration.ofSeconds(11));

        ClaimResult again = store.claim("key-8", "hash-NEW-BODY", TTL);
        assertInstanceOf(ClaimResult.Granted.class, again);
        log.info("用例通过: 过期后旧结果不再重放，同键可用于新的请求内容");
    }

    @Test
    @DisplayName("【存储异常】对不存在（已过期）的键固化成功结果，抛存储异常以走兜底")
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
        store.claim("key-9", "hashA", TTL);
        store.complete("key-9", 200, "application/json", "first");

        assertThrows(IdempotencyStorageException.class,
                () -> store.complete("key-9", 200, "application/json", "second"));
        IdempotencyRecord record = store.peek("key-9");
        assertEquals("first", record.getBody());
        log.info("用例通过: 重复固化被阻止，首次结果不被覆盖");
    }
}
