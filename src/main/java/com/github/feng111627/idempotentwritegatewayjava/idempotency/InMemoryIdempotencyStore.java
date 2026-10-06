package com.github.feng111627.idempotentwritegatewayjava.idempotency;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 基于 {@link ConcurrentHashMap} 的内存幂等存储。
 *
 * <p>{@link #claim} 通过 synchronized 保证原子：同一个幂等键的并发请求，
 * 最多只有一个得到执行权；其余分别得到 REPLAYING / PROCESSING / REJECTED 结果。
 *
 * <p>适用单实例/演示场景。生产环境应替换为 Redis（{@code SET key value NX EX ttl}
 * 或 Lua 脚本）或带唯一约束的数据库实现，接口保持不变。
 */
public class InMemoryIdempotencyStore implements IdempotencyStore {

    private static final Logger log = LoggerFactory.getLogger(InMemoryIdempotencyStore.class);

    /** 记录数超过该阈值时，在 claim 过程中顺带清理过期记录，避免无限增长。 */
    private static final int EVICT_TRIGGER_SIZE = 1024;

    private final ConcurrentHashMap<String, IdempotencyRecord> records = new ConcurrentHashMap<>();

    @Override
    public ClaimResult claim(String key, String requestHash, Duration ttl) {
        try {
            synchronized (this) {
                long now = nowMillis();
                if (records.size() >= EVICT_TRIGGER_SIZE) {
                    evictExpired(now);
                }
                IdempotencyRecord existing = records.get(key);
                if (existing != null) {
                    if (existing.isExpired(now)) {
                        log.info("[幂等存储] key={} 记录已过期，允许重新处理", key);
                        records.remove(key);
                        existing = null;
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
                                yield new ClaimResult.Processing();
                            }
                            case FAILED -> {
                                log.info("[幂等存储] key={} 上次执行失败，允许同内容重试", key);
                                records.put(key, IdempotencyRecord.processing(
                                        key, requestHash, now, ttl.toMillis()));
                                yield new ClaimResult.Retryable();
                            }
                        };
                    }
                }
                records.put(key, IdempotencyRecord.processing(
                        key, requestHash, now, ttl.toMillis()));
                log.info("[幂等存储] key={} 抢占成功，状态=PROCESSING，TTL={}", key, ttl);
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
                    // 典型场景：业务耗时超过 TTL，记录已过期。此时无法安全固化结果，
                    // 抛出异常让上层返回“不确定”反馈，禁止调用方盲目重试。
                    throw new IdempotencyStorageException(
                            "幂等记录不存在或已过期，无法固化成功结果: " + key);
                }
                if (existing.getStatus() != IdempotencyStatus.PROCESSING) {
                    throw new IdempotencyStorageException(
                            "幂等记录状态不是 PROCESSING，禁止覆盖结果: " + key
                                    + "，当前状态=" + existing.getStatus());
                }
                records.put(key, existing.toSucceeded(statusCode, contentType, body));
                log.info("[幂等存储] key={} 结果已固化，状态=SUCCEEDED，httpStatus={}", key, statusCode);
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
                // 记录已过期被清理：键自然恢复可用，无需再标记。
                log.warn("[幂等存储] key={} 标记失败时记录不存在（可能已过期），无需处理", key);
                return;
            }
            records.put(key, existing.toFailed());
            log.info("[幂等存储] key={} 已标记为 FAILED，TTL 内允许同内容重试", key);
        }
    }

    /** 清理所有已过期记录（仅在记录数达到阈值时顺带触发）。 */
    private void evictExpired(long now) {
        int before = records.size();
        records.entrySet().removeIf(e -> e.getValue().isExpired(now));
        log.debug("[幂等存储] 过期清理：{} -> {}", before, records.size());
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
