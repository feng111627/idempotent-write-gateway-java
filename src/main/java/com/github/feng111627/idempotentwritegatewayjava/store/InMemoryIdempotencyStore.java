package com.github.feng111627.idempotentwritegatewayjava.store;

import com.github.feng111627.idempotentwritegatewayjava.model.IdempotencyRecord;
import com.github.feng111627.idempotentwritegatewayjava.model.IdempotencyState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 基于 ConcurrentHashMap 的内存存储，默认实现。单实例部署足够；多实例需换成 Redis/DB 实现。
 *
 * 互斥保证：
 * - 每个幂等键有一把短锁，acquire/complete 在锁内只做内存读写（纳秒级），
 *   绝不在持锁期间执行业务，因此后来的请求会立刻拿到 409 而不是阻塞到首请业务结束；
 * - 同一个幂等键的并发请求只有一个能完成“检查状态 + 写入 PROCESSING”，
 *   其余请求看到在途记录，返回 InProgress。
 *
 * 租约机制：PROCESSING 记录超过 processingTtl 视为过期（首请崩溃的兜底），
 *          过期后该键可被重新抢占；迟到的 complete 因 createdAt 对不上不会覆盖新记录。
 */
@Component
public class InMemoryIdempotencyStore implements IdempotencyStore {

    private static final Logger log = LoggerFactory.getLogger(InMemoryIdempotencyStore.class);

    private final ConcurrentHashMap<String, IdempotencyRecord> records = new ConcurrentHashMap<>();
    /** 每键一把短锁；锁对象一旦放入 map 不再移除（避免 remove/re-add 导致两把锁并存）。 */
    private final ConcurrentHashMap<String, Object> keyLocks = new ConcurrentHashMap<>();
    private final Clock clock;

    public InMemoryIdempotencyStore(Clock clock) {
        this.clock = clock;
    }

    private Object lockOf(String key) {
        return keyLocks.computeIfAbsent(key, k -> new Object());
    }

    @Override
    public AcquireResult acquire(String idempotencyKey, String requestFingerprint,
                                 long nowMillis, long processingTtlMillis) {
        synchronized (lockOf(idempotencyKey)) {
            IdempotencyRecord existing = records.get(idempotencyKey);

            // 不存在或已过期（含 PROCESSING 租约超时）：本次请求成为首请
            if (existing == null || existing.expired(nowMillis)) {
                IdempotencyRecord processing = IdempotencyRecord.processing(
                        idempotencyKey, requestFingerprint, nowMillis, processingTtlMillis);
                records.put(idempotencyKey, processing);
                return new AcquireResult.Acquired(processing);
            }

            return switch (existing.state()) {
                case PROCESSING -> {
                    long retryAfterSeconds = Math.max(1L,
                            (existing.expiresAtMillis() - nowMillis + 999L) / 1000L);
                    yield new AcquireResult.InProgress(retryAfterSeconds);
                }
                case SUCCESS, FAILED -> {
                    if (!existing.requestFingerprint().equals(requestFingerprint)) {
                        yield new AcquireResult.Conflict(existing.requestFingerprint());
                    }
                    yield new AcquireResult.Completed(existing);
                }
            };
        }
    }

    @Override
    public void complete(IdempotencyRecord finalRecord, long nowMillis) {
        synchronized (lockOf(finalRecord.idempotencyKey())) {
            IdempotencyRecord existing = records.get(finalRecord.idempotencyKey());
            // 只有仍是“我这次首请”的 PROCESSING 记录才允许写入终态，
            // 防止租约过期后新首请的记录被迟到完成覆盖。
            if (existing != null
                    && existing.state() == IdempotencyState.PROCESSING
                    && existing.createdAtMillis() == finalRecord.createdAtMillis()) {
                records.put(finalRecord.idempotencyKey(), finalRecord);
            } else {
                log.warn("忽略迟到的终态写入 key={}, finalState={}, existingState={}, existingCreatedAt={}",
                        finalRecord.idempotencyKey(), finalRecord.state(),
                        existing == null ? null : existing.state(),
                        existing == null ? null : existing.createdAtMillis());
            }
        }
    }

    /**
     * 定时清理过期记录，避免内存无限增长。
     */
    @Scheduled(fixedDelayString = "${idempotency.cleanup-interval:PT1M}")
    public void purgeExpired() {
        long now = clock.millis();
        int before = records.size();
        records.entrySet().removeIf(e -> e.getValue().expired(now));
        int removed = before - records.size();
        if (removed > 0) {
            log.debug("幂等记录清理: 移除 {} 条过期记录, 剩余 {} 条", removed, records.size());
        }
    }

    /**
     * 清空全部记录（主要供测试重置使用）。
     */
    public void clear() {
        records.clear();
    }
}
