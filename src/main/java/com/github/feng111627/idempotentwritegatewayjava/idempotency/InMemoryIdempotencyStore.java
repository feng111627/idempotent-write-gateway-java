package com.github.feng111627.idempotentwritegatewayjava.idempotency;

import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 基于 {@link ConcurrentHashMap} 的内存幂等存储（单实例实现）。
 *
 * <p>正确性要点：
 * <ul>
 *     <li>{@link #claim} 在同一把监视器锁内完成“读旧记录 → 判定 → 写新记录”，
 *     同一个幂等键的并发请求最多只有一个拿到执行权。</li>
 *     <li><b>in-flight 执行护栏</b>：抢到执行权的键在业务真正结束前登记在
 *     {@link #inFlightKeys} 中。即便 PROCESSING 保留窗口已过、记录在逻辑上过期，
 *     只要首请在本实例内还在跑，任何同键并发请求都只能拿到 PROCESSING（提示稍后再试），
 *     杜绝“窗口到期 + 首请仍在跑”导致同一业务被执行两遍。窗口过期的意义是
 *     “首请疑似卡死（如实例宕机），键最终能恢复可用”，而不是强杀正在执行的请求。
 *     多实例部署时该护栏只在本实例内有效（见 README）。</li>
 *     <li><b>容量上限</b>：记录数达到 {@code maxRecords} 时，先清理过期（且不在执行中）
 *     的记录；仍满则强制淘汰最老的终态（SUCCEEDED/FAILED）记录；
 *     处理中（PROCESSING）的记录一律不强制淘汰；若剩余全是处理中记录，
 *     按 fail-closed 抛 {@link IdempotencyStorageException}，宁可不接新单也不破坏互斥。
 *     被淘汰的键之后重新提交表现为一次全新提交（不报错、也不重放旧结果）。</li>
 * </ul>
 */
public class InMemoryIdempotencyStore implements IdempotencyStore {

    private static final Logger log = LoggerFactory.getLogger(InMemoryIdempotencyStore.class);

    private final ConcurrentHashMap<String, IdempotencyRecord> records = new ConcurrentHashMap<>();

    /** 本实例内“已经抢到执行权、业务尚未走完 complete/fail”的键。 */
    private final Set<String> inFlightKeys = ConcurrentHashMap.newKeySet();

    private final RetentionWindows windows;
    private final int maxRecords;
    private final Duration fallbackRetryAfter;

    public InMemoryIdempotencyStore() {
        this(new IdempotencyProperties().getRetentionWindows(),
                IdempotencyProperties.DEFAULT_MAX_RECORDS,
                IdempotencyProperties.DEFAULT_PROCESSING_RETRY_AFTER);
    }

    public InMemoryIdempotencyStore(RetentionWindows windows, int maxRecords,
                                    Duration fallbackRetryAfter) {
        this.windows = windows;
        this.maxRecords = maxRecords;
        this.fallbackRetryAfter = fallbackRetryAfter;
    }

    @Override
    public ClaimResult claim(String key, String requestHash, RetentionWindows windows) {
        try {
            synchronized (this) {
                long now = nowMillis();
                IdempotencyRecord existing = records.get(key);
                if (existing != null) {
                    if (existing.isExpired(now)) {
                        // 最高优先级护栏：记录窗口已过，但首请在本实例仍在执行——
                        // 无论同内容还是换内容，都不放行第二次执行
                        if (inFlightKeys.contains(key)) {
                            log.info("[幂等存储] key={} 处理窗口已过但首请仍在执行中（in-flight），返回 PROCESSING", key);
                            return new ClaimResult.Processing(retryAfterSeconds(existing, now));
                        }
                        log.info("[幂等存储] key={} 记录已过期，允许重新处理", key);
                        records.remove(key);
                    } else if (!requestHash.equals(existing.getRequestHash())) {
                        log.warn("[幂等存储] key={} 请求内容与首次提交不一致，拒绝 REQUEST_MISMATCH", key);
                        return new ClaimResult.Rejected(RejectionReason.REQUEST_MISMATCH);
                    } else {
                        return switch (existing.getStatus()) {
                            case SUCCEEDED -> {
                                log.info("[幂等存储] key={} 命中成功记录，直接重放", key);
                                yield new ClaimResult.Replaying(existing);
                            }
                            case PROCESSING -> {
                                log.info("[幂等存储] key={} 仍在处理中，返回 PROCESSING", key);
                                yield new ClaimResult.Processing(retryAfterSeconds(existing, now));
                            }
                            case FAILED -> {
                                log.info("[幂等存储] key={} 上次执行失败，允许同内容重试", key);
                                records.put(key, IdempotencyRecord.processing(
                                        key, requestHash, now, windows.processingTtl().toMillis()));
                                inFlightKeys.add(key);
                                yield new ClaimResult.Retryable();
                            }
                        };
                    }
                }
                ensureCapacity(now);
                records.put(key, IdempotencyRecord.processing(
                        key, requestHash, now, windows.processingTtl().toMillis()));
                inFlightKeys.add(key);
                log.info("[幂等存储] key={} 抢占成功，状态=PROCESSING，processingTtl={}",
                        key, windows.processingTtl());
                return new ClaimResult.Granted();
            }
        } catch (IdempotencyStorageException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new IdempotencyStorageException("抢占幂等键时存储发生异常: " + key, e);
        }
    }

    @Override
    public void complete(String key, int statusCode, String contentType, String body) {
        try {
            synchronized (this) {
                IdempotencyRecord existing = records.get(key);
                if (existing == null) {
                    // 典型场景：记录曾被容量淘汰。此时无法安全固化结果，抛异常让上层走兜底日志。
                    throw new IdempotencyStorageException(
                            "幂等记录不存在或已被淘汰，无法固化成功结果: " + key);
                }
                if (existing.getStatus() != IdempotencyStatus.PROCESSING) {
                    throw new IdempotencyStorageException(
                            "幂等记录状态不是 PROCESSING，禁止覆盖结果: " + key
                                    + "，当前状态=" + existing.getStatus());
                }
                long now = nowMillis();
                IdempotencyRecord succeeded = windows.rebaseOnTransition()
                        ? existing.toSucceeded(statusCode, contentType, body,
                                now, windows.succeededTtl().toMillis())
                        : existing.toSucceeded(statusCode, contentType, body);
                records.put(key, succeeded);
                // 业务已经走完并成功固化：本实例的执行护栏在此刻自动解除
                inFlightKeys.remove(key);
                log.info("[幂等存储] key={} 结果已固化，状态=SUCCEEDED，httpStatus={}，到期时间已{}",
                        key, statusCode,
                        windows.rebaseOnTransition() ? "按成功窗口重新起算" : "保持抢占时刻的固定截止");
            }
        } catch (IdempotencyStorageException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new IdempotencyStorageException("固化幂等结果时存储发生异常: " + key, e);
        }
    }

    @Override
    public void fail(String key) {
        synchronized (this) {
            IdempotencyRecord existing = records.get(key);
            if (existing == null) {
                // 记录已过期清理或被容量淘汰：键自然恢复可用，无需再标记。
                log.warn("[幂等存储] key={} 标记失败时记录不存在（可能已过期/淘汰），无需处理", key);
                return;
            }
            if (existing.getStatus() != IdempotencyStatus.PROCESSING) {
                log.warn("[幂等存储] key={} 标记失败时状态={}（非 PROCESSING），忽略",
                        key, existing.getStatus());
                return;
            }
            long now = nowMillis();
            IdempotencyRecord failed = windows.rebaseOnTransition()
                    ? existing.toFailed(now, windows.failedTtl().toMillis())
                    : existing.toFailed();
            records.put(key, failed);
            // 业务已经走完并确认失败：解除执行护栏，重试由后续新的 claim 重新抢占
            inFlightKeys.remove(key);
            log.info("[幂等存储] key={} 已标记为 FAILED，失败重试窗口内允许同内容重试", key);
        }
    }

    @Override
    public void releaseInFlight(String key) {
        if (inFlightKeys.remove(key)) {
            log.debug("[幂等存储] key={} 业务执行结束，解除 in-flight 护栏", key);
        }
    }

    @Override
    public KeyStatusView inspect(String key) {
        synchronized (this) {
            long now = nowMillis();
            IdempotencyRecord existing = records.get(key);
            if (existing == null) {
                return KeyStatusView.notFound(key);
            }
            boolean inFlight = inFlightKeys.contains(key);
            if (existing.isExpired(now)) {
                if (inFlight) {
                    // 处理窗口已过，但首请确实还在本实例跑：不能当成可重新提交。
                    return new KeyStatusView(key, KeyState.PROCESSING_TIMEOUT,
                            fallbackRetryAfterSeconds(), existing.getExpiresAtMillis());
                }
                return KeyStatusView.notFound(key);
            }
            return switch (existing.getStatus()) {
                case PROCESSING -> new KeyStatusView(key, KeyState.PROCESSING,
                        retryAfterSeconds(existing, now), existing.getExpiresAtMillis());
                case SUCCEEDED -> new KeyStatusView(key, KeyState.SUCCEEDED,
                        0, existing.getExpiresAtMillis());
                case FAILED -> new KeyStatusView(key, KeyState.FAILED_RETRYABLE,
                        0, existing.getExpiresAtMillis());
            };
        }
    }

    /** 容量保障：过期清理 → 淘汰最老终态记录 → 仍满则 fail-closed。仅在插入新记录前调用。 */
    private void ensureCapacity(long now) {
        if (records.size() < maxRecords) {
            return;
        }
        int before = records.size();
        records.entrySet().removeIf(e ->
                e.getValue().isExpired(now) && !inFlightKeys.contains(e.getKey()));
        if (records.size() < maxRecords) {
            log.info("[幂等存储][容量] 先清理过期记录腾位：{} -> {}", before, records.size());
            return;
        }
        String victim = null;
        long oldestCreatedAt = Long.MAX_VALUE;
        for (Map.Entry<String, IdempotencyRecord> e : records.entrySet()) {
            IdempotencyRecord rec = e.getValue();
            if (inFlightKeys.contains(e.getKey())) {
                continue;
            }
            if (rec.getStatus() == IdempotencyStatus.PROCESSING) {
                // 处理中（疑似卡死）的记录不强制淘汰，等其较短的保留窗口过期后自然清走
                continue;
            }
            if (rec.getCreatedAtMillis() < oldestCreatedAt) {
                oldestCreatedAt = rec.getCreatedAtMillis();
                victim = e.getKey();
            }
        }
        if (victim != null) {
            records.remove(victim);
            log.warn("[幂等存储][容量] 容量已达上限 {}，淘汰最老的终态记录 key={}，该键之后提交按新请求处理",
                    maxRecords, victim);
            return;
        }
        throw new IdempotencyStorageException("幂等存储容量已满（上限 " + maxRecords
                + "）且现存记录全部处于处理中，无法安全淘汰；为避免破坏并发互斥，本次抢占被拒绝");
    }

    /** 建议客户端等待的秒数：取记录剩余窗口向上取整，最小 1 秒。 */
    private int retryAfterSeconds(IdempotencyRecord record, long now) {
        long remaining = record.remainingMillis(now);
        if (remaining <= 0) {
            return fallbackRetryAfterSeconds();
        }
        return (int) Math.max(1L, (remaining + 999L) / 1000L);
    }

    private int fallbackRetryAfterSeconds() {
        return (int) Math.max(1L, (fallbackRetryAfter.toMillis() + 999L) / 1000L);
    }

    /** 当前时间，protected 便于测试用可控时钟覆盖。 */
    protected long nowMillis() {
        return System.currentTimeMillis();
    }

    /** 当前持有的记录数（主要供测试与运维观测）。 */
    public int currentSize() {
        return records.size();
    }

    /** 只读查看某条记录（主要供测试断言）。 */
    public IdempotencyRecord peek(String key) {
        return records.get(key);
    }

    /** 仅供测试清空全部记录。 */
    public void clear() {
        records.clear();
        inFlightKeys.clear();
    }

    /** 仅供测试注入条目（过期重放场景）。 */
    public void putForTesting(String key, IdempotencyRecord record) {
        records.put(key, record);
    }

    /** 快照（仅供测试/排障）。 */
    public Map<String, IdempotencyRecord> snapshot() {
        return Map.copyOf(records);
    }
}
