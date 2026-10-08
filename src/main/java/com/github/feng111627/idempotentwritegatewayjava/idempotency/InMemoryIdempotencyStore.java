package com.github.feng111627.idempotentwritegatewayjava.idempotency;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 基于 {@link ConcurrentHashMap} 的内存幂等存储。
 *
 * <p>所有状态机操作在同一个锁内完成，保证 {@link #claim} 原子：同一个幂等键的并发请求，
 * 最多只有一个得到执行权；其余分别得到 REPLAYING / PROCESSING / REJECTED 结果。
 *
 * <h3>分状态保留时长</h3>
 * PROCESSING / SUCCEEDED / FAILED 各有独立的保留时长（{@link RetentionPolicy}）：
 * 抢占时按 processingTtl 过期；流转到 SUCCEEDED / FAILED 时，新配置模式下会把过期时刻
 * 重新锚定为 now + 对应窗口（成功留久便于重放、失败留一个重试窗口）。老配置模式
 * （只配 {@code idempotency.ttl}）下三段时长相同且不重新锚定，行为与升级前一致。
 *
 * <h3>处理中窗口到期 ≠ 首请已结束（在途执行护栏）</h3>
 * PROCESSING 记录的保留窗口故意比较短，目的是不让键长期被占；但窗口到期时，
 * 首请的业务可能仍在正常执行。为此另维护一个“在途集合” {@link #inFlight}：
 * 键在业务执行期间无论记录是否到期都受保护——即使处理中窗口已过，同键的新请求
 * 也只会得到 PROCESSING + Retry-After，而不会真正再执行业务，杜绝业务被做两遍。
 * 业务结束（成功固化 / 失败标记 / 兜底放弃）后由过滤器调用
 * {@link #releaseInFlight(String)} 释放护栏。护栏释放后：
 * <ul>
 *     <li>首请成功：结果已（重新）固化为 SUCCEEDED，后续请求重放；</li>
 *     <li>首请失败：记录为 FAILED，按失败窗口允许同内容重试；</li>
 *     <li>结果固化也失败（存储兜底场景）：记录停在 PROCESSING 直到处理中窗口自然到期，
 *     到期后同键才能重新处理——维持 at-least-once 取舍且不凭空重放。</li>
 * </ul>
 *
 * <h3>容量上限与淘汰</h3>
 * 记录数受 {@code idempotency.max-records} 约束。需要插入新记录且容量已满时：
 * <ol>
 *     <li>先淘汰所有已过期、且不在业务执行中的记录；</li>
 *     <li>仍不够时，按过期时刻从早到晚淘汰“可以忘”的记录：SUCCEEDED / FAILED 终态记录，
 *     以及业务已结束（护栏已释放）但仍停在 PROCESSING 的兜底记录；</li>
 *     <li>还不够（剩余全是在途执行中、受护栏保护的记录）→ 返回 CAPACITY_FULL，
 *     fail-closed，绝不通过淘汰在途记录来制造重复业务。</li>
 * </ol>
 * 被淘汰的键再次提交时表现为全新提交（Granted），而不是报错或重放旧结果。
 *
 * <p>适用单实例/演示场景。生产环境应替换为 Redis（{@code SET key value NX EX ttl}
 * 或 Lua 脚本）或带唯一约束的数据库实现，接口保持不变。
 */
public class InMemoryIdempotencyStore implements IdempotencyStore {

    private static final Logger log = LoggerFactory.getLogger(InMemoryIdempotencyStore.class);

    /** 处理中窗口已过但首请仍在跑时，给客户端的固定轮询建议（秒）。 */
    static final int IN_FLIGHT_POLL_SECONDS = 2;

    private final RetentionPolicy policy;
    private final ConcurrentHashMap<String, IdempotencyRecord> records = new ConcurrentHashMap<>();

    /**
     * 业务正在真实执行的键及其请求指纹：独立于记录的保留窗口，
     * 防止“窗口到期 + 首请未结束”时重复执行；即使记录到期被移除，
     * 也能凭这里保存的指纹在业务结束时重新固化结果。
     */
    private final ConcurrentHashMap<String, String> inFlight = new ConcurrentHashMap<>();

    public InMemoryIdempotencyStore(RetentionPolicy policy) {
        this.policy = policy;
    }

    @Override
    public ClaimResult claim(String key, String requestHash, RetentionPolicy claimPolicy) {
        RetentionPolicy usePolicy = claimPolicy != null ? claimPolicy : this.policy;
        try {
            synchronized (this) {
                long now = nowMillis();
                IdempotencyRecord existing = records.get(key);

                if (existing != null && existing.isExpired(now)) {
                    if (inFlight.containsKey(key)) {
                        // 处理中窗口已过，但首请业务仍在真实执行：宁可让新请求等，
                        // 也不能让同一个键被两个请求各做一遍业务。
                        log.info("[幂等存储] key={} 处理中窗口已过但首请仍在执行，继续返回 PROCESSING 护栏", key);
                        return new ClaimResult.Processing(IN_FLIGHT_POLL_SECONDS);
                    }
                    log.info("[幂等存储] key={} 记录已过期，允许重新处理", key);
                    records.remove(key);
                    existing = null;
                }

                if (existing != null) {
                    if (!requestHash.equals(existing.getRequestHash())) {
                        log.warn("[幂等存储] key={} 请求内容与首次提交不一致，拒绝 REQUEST_MISMATCH", key);
                        return new ClaimResult.Rejected(RejectionReason.REQUEST_MISMATCH);
                    }
                    return switch (existing.getStatus()) {
                        case SUCCEEDED -> {
                            log.info("[幂等存储] key={} 命中成功记录，直接重放", key);
                            yield new ClaimResult.Replaying(existing);
                        }
                        case PROCESSING -> {
                            int retryAfter = retryAfterSeconds(existing, now);
                            if (inFlight.containsKey(key)) {
                                log.info("[幂等存储] key={} 仍在处理中，返回 PROCESSING，Retry-After={}s", key, retryAfter);
                            } else {
                                // 业务已结束但结果未固化（兜底）窗口：仍然 409，直到记录自然到期
                                log.info("[幂等存储] key={} 首请已结束但结果未固化，窗口内返回 PROCESSING，Retry-After={}s",
                                        key, retryAfter);
                            }
                            yield new ClaimResult.Processing(retryAfter);
                        }
                        case FAILED -> {
                            log.info("[幂等存储] key={} 上次执行失败，允许同内容重试", key);
                            records.put(key, IdempotencyRecord.processing(
                                    key, requestHash, now, usePolicy.getProcessingTtl().toMillis()));
                            inFlight.put(key, requestHash);
                            yield new ClaimResult.Retryable();
                        }
                    };
                }

                // 全新抢占（含过期后的重新提交）：先保证有容量
                if (!makeRoom(key, now)) {
                    log.error("[幂等存储] key={} 容量已满（上限={}）且无法安全淘汰，fail-closed 拒绝",
                            key, usePolicy.getMaxRecords());
                    return new ClaimResult.Rejected(RejectionReason.CAPACITY_FULL);
                }
                records.put(key, IdempotencyRecord.processing(
                        key, requestHash, now, usePolicy.getProcessingTtl().toMillis()));
                inFlight.put(key, requestHash);
                log.info("[幂等存储] key={} 抢占成功，状态=PROCESSING，processingTtl={}",
                        key, usePolicy.getProcessingTtl());
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
                long now = nowMillis();
                IdempotencyRecord existing = records.get(key);
                if (existing == null) {
                    if (policy.isLegacyMode()) {
                        // 老语义：记录不存在（通常因业务耗时超过统一 TTL）时无法安全固化，
                        // 抛异常让上层走兜底，保持升级前行为不变。
                        throw new IdempotencyStorageException(
                                "幂等记录不存在或已过期，无法固化成功结果: " + key);
                    }
                    // 新模式：业务实际跑过了处理中窗口，记录到期后已被移除/过期。
                    // 此时首请刚刚成功结束——把结果重新固化为 SUCCEEDED（按成功窗口锚定），
                    // 让后续请求重放而不是把业务再做一遍。
                    long newExpiry = safeAdd(now, policy.getSucceededTtl().toMillis());
                    records.put(key, IdempotencyRecord.succeeded(
                            key, inFlight.getOrDefault(key, ""), statusCode, contentType, body, now, newExpiry));
                    log.info("[幂等存储] key={} 首请跨过处理中窗口后才成功，已重新固化 SUCCEEDED", key);
                    return;
                }
                if (existing.getStatus() != IdempotencyStatus.PROCESSING) {
                    throw new IdempotencyStorageException(
                            "幂等记录状态不是 PROCESSING，禁止覆盖结果: " + key
                                    + "，当前状态=" + existing.getStatus());
                }
                IdempotencyRecord succeeded = policy.isLegacyMode()
                        ? existing.toSucceeded(statusCode, contentType, body)
                        : existing.toSucceeded(statusCode, contentType, body,
                                safeAdd(now, policy.getSucceededTtl().toMillis()), now);
                records.put(key, succeeded);
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
            long now = nowMillis();
            IdempotencyRecord existing = records.get(key);
            if (existing == null) {
                if (policy.isLegacyMode()) {
                    // 老语义：记录已过期被清理，键自然恢复可用，无需再标记。
                    log.warn("[幂等存储] key={} 标记失败时记录不存在（可能已过期），无需处理", key);
                    return;
                }
                // 新模式：同样可能跨过了处理中窗口。按失败窗口重新锚定一条 FAILED，
                // 保留“只能同内容重试”的约束，而不是让键裸奔成全新提交。
                long newExpiry = safeAdd(now, policy.getFailedTtl().toMillis());
                records.put(key, IdempotencyRecord.failed(
                        key, inFlight.getOrDefault(key, ""), now, newExpiry));
                log.info("[幂等存储] key={} 首请跨过处理中窗口后失败，已重新标记 FAILED（失败窗口内重试）", key);
                return;
            }
            if (existing.getStatus() != IdempotencyStatus.PROCESSING) {
                log.warn("[幂等存储] key={} 标记失败时状态={}（非 PROCESSING），保持现状",
                        key, existing.getStatus());
                return;
            }
            IdempotencyRecord failed = policy.isLegacyMode()
                    ? existing.toFailed()
                    : existing.toFailed(safeAdd(now, policy.getFailedTtl().toMillis()), now);
            records.put(key, failed);
            log.info("[幂等存储] key={} 已标记为 FAILED，失败窗口内允许同内容重试", key);
        }
    }

    @Override
    public void releaseInFlight(String key) {
        inFlight.remove(key);
    }

    @Override
    public KeyStateView query(String key) {
        synchronized (this) {
            long now = nowMillis();
            IdempotencyRecord r = records.get(key);
            if (r == null) {
                return new KeyStateView(key, KeyState.NOT_FOUND, null, null);
            }
            if (r.isExpired(now)) {
                if (inFlight.containsKey(key)) {
                    // 对外仍是“处理中”：首请还在真实跑，客户端此时不应改走新提交
                    return new KeyStateView(key, KeyState.PROCESSING,
                            IN_FLIGHT_POLL_SECONDS, r.getExpiresAtMillis());
                }
                return new KeyStateView(key, KeyState.EXPIRED, null, r.getExpiresAtMillis());
            }
            return switch (r.getStatus()) {
                case SUCCEEDED -> new KeyStateView(key, KeyState.SUCCEEDED,
                        null, r.getExpiresAtMillis());
                case FAILED -> new KeyStateView(key, KeyState.FAILED_RETRYABLE,
                        null, r.getExpiresAtMillis());
                case PROCESSING -> new KeyStateView(key, KeyState.PROCESSING,
                        retryAfterSeconds(r, now), r.getExpiresAtMillis());
            };
        }
    }

    /**
     * 为新记录腾出一个槽位。
     *
     * @return true 已有空位；false 容量满且只能靠淘汰受保护的处理中记录才能腾位
     */
    private boolean makeRoom(String claimingKey, long now) {
        if (records.size() < policy.getMaxRecords()) {
            return true;
        }
        int before = records.size();
        // 第一轮：清理所有“已过期且不在业务执行中”的记录（在途的处理中记录绝不清理）
        records.entrySet().removeIf(e -> {
            IdempotencyRecord r = e.getValue();
            return r.isExpired(now) && !inFlight.contains(e.getKey());
        });
        if (records.size() < policy.getMaxRecords()) {
            log.info("[幂等存储] 容量触顶（{}），过期淘汰后 {} 条，key={} 可插入",
                    before, records.size(), claimingKey);
            return true;
        }
        // 第二轮：淘汰“可以忘”的记录——终态记录，以及业务已结束（护栏已释放）但
        // 仍停在 PROCESSING 的兜底记录；唯独“在途执行中”的键绝不淘汰。
        // 按过期时刻从早到晚，一直淘汰到至少腾出一个空槽（size < max）。
        records.entrySet().stream()
                .filter(e -> !inFlight.containsKey(e.getKey()))
                .sorted(Map.Entry.comparingByValue((a, b) ->
                        Long.compare(a.getExpiresAtMillis(), b.getExpiresAtMillis())))
                .limit(records.size() - policy.getMaxRecords() + 1L)
                .forEach(e -> {
                    log.info("[幂等存储] 容量淘汰终态记录 key={} status={} expiresAt={}",
                            e.getKey(), e.getValue().getStatus(), e.getValue().getExpiresAtMillis());
                    records.remove(e.getKey());
                });
        return records.size() < policy.getMaxRecords();
    }

    private int retryAfterSeconds(IdempotencyRecord r, long now) {
        long remainingMillis = Math.max(0L, r.getExpiresAtMillis() - now);
        return Math.max(1, (int) Math.ceil(remainingMillis / 1000.0));
    }

    private static long safeAdd(long base, long delta) {
        long sum = base + delta;
        return sum < base ? Long.MAX_VALUE : sum;
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
        inFlight.clear();
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
