package com.github.feng111627.idempotentwritegatewayjava.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.feng111627.idempotentwritegatewayjava.config.IdempotencyProperties;
import com.github.feng111627.idempotentwritegatewayjava.error.ApiException;
import com.github.feng111627.idempotentwritegatewayjava.error.BusinessException;
import com.github.feng111627.idempotentwritegatewayjava.error.IdempotencyConflictException;
import com.github.feng111627.idempotentwritegatewayjava.error.IdempotencyInProgressException;
import com.github.feng111627.idempotentwritegatewayjava.error.StorageUnavailableException;
import com.github.feng111627.idempotentwritegatewayjava.error.StoredFailureException;
import com.github.feng111627.idempotentwritegatewayjava.model.IdempotencyRecord;
import com.github.feng111627.idempotentwritegatewayjava.model.IdempotencyState;
import com.github.feng111627.idempotentwritegatewayjava.store.AcquireResult;
import com.github.feng111627.idempotentwritegatewayjava.store.IdempotencyStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.util.function.Supplier;

/**
 * 幂等编排：抢占 -> 执行业务 -> 落终态 / 重放已有结果。
 *
 * 存储异常兜底原则（避免“报错但业务其实做完”）：
 * 1) acquire 阶段存储失败：业务尚未执行，fail-closed 直接抛 503，绝不执行业务；
 * 2) 业务成功后 complete 落库失败：业务副作用已经产生，不能再报错引发客户端重试，
 *    记录 ERROR 日志并把本次真实结果返回给调用方（本次未享受到重放保护，但结果正确）；
 * 3) 业务失败后 complete 落库失败：原样抛业务异常，不污染返回（本次失败无副作用的话
 *    客户端重试是安全的）。
 */
@Service
public class IdempotencyService {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyService.class);

    private final IdempotencyStore store;
    private final IdempotencyProperties properties;
    private final Clock clock;
    private final ObjectMapper objectMapper;

    public IdempotencyService(IdempotencyStore store, IdempotencyProperties properties,
                              Clock clock, ObjectMapper idempotencyObjectMapper) {
        this.store = store;
        this.properties = properties;
        this.clock = clock;
        this.objectMapper = idempotencyObjectMapper;
    }

    /**
     * 执行一次带幂等保护的写操作。
     *
     * @param idempotencyKey     已通过格式校验的幂等键
     * @param requestFingerprint 请求指纹
     * @param business           真正的业务逻辑（只有首请会被调用）
     */
    public ExecutionOutcome execute(String idempotencyKey, String requestFingerprint,
                                    Supplier<Object> business) {
        long now = clock.millis();
        AcquireResult result;
        try {
            result = store.acquire(idempotencyKey, requestFingerprint, now,
                    properties.getProcessingTtl().toMillis());
        } catch (RuntimeException e) {
            // 兜底 1：抢占前存储不可用 -> 拒绝请求，业务不会执行
            throw new StorageUnavailableException("acquire", e);
        }

        if (result instanceof AcquireResult.Acquired acquired) {
            return runBusinessOnce(idempotencyKey, acquired.record(), business);
        }
        if (result instanceof AcquireResult.Completed completed) {
            return replay(idempotencyKey, completed.record());
        }
        if (result instanceof AcquireResult.InProgress inProgress) {
            throw new IdempotencyInProgressException(idempotencyKey,
                    Duration.ofSeconds(inProgress.retryAfterSeconds()));
        }
        if (result instanceof AcquireResult.Conflict) {
            throw new IdempotencyConflictException(idempotencyKey);
        }
        throw new IllegalStateException("未知抢占结果: " + result);
    }

    private ExecutionOutcome runBusinessOnce(String idempotencyKey, IdempotencyRecord processing,
                                             Supplier<Object> business) {
        Object value;
        try {
            value = business.get();
        } catch (Throwable t) {
            persistFailure(idempotencyKey, processing, t);
            throw (t instanceof ApiException api) ? api : toApiException(t);
        }

        // 业务成功：序列化并落 SUCCESS 终态
        Object body = unwrapResponseEntity(value);
        String json;
        try {
            json = serialize(body);
        } catch (RuntimeException e) {
            log.error("幂等结果序列化失败 key={}，无法落库，本次结果直接返回（不重试业务）",
                    idempotencyKey, e);
            return new ExecutionOutcome.Executed(value);
        }
        long now = clock.millis();
        IdempotencyRecord successRecord = processing.completed(IdempotencyState.SUCCESS,
                statusOf(value), json, null, null, now, properties.getSuccessTtl().toMillis());
        try {
            store.complete(successRecord, now);
        } catch (RuntimeException e) {
            // 兜底 2：业务已成功但终态写不进去，返回真实结果并打 ERROR，不抛异常引发重试
            log.error("幂等成功记录落库失败 key={}：业务已执行完成，直接返回本次结果；"
                    + "随后的重试在租约期内会收到 409，请稍后再试", idempotencyKey, e);
            return new ExecutionOutcome.Executed(value);
        }
        log.info("幂等首请执行成功 key={}", idempotencyKey);
        return new ExecutionOutcome.Executed(value);
    }

    private void persistFailure(String idempotencyKey, IdempotencyRecord processing, Throwable t) {
        ApiException api = (t instanceof ApiException a) ? a : toApiException(t);
        long now = clock.millis();
        IdempotencyRecord failedRecord = processing.completed(IdempotencyState.FAILED,
                api.getHttpStatus(), null, api.getCode(), api.getMessage(),
                now, properties.getFailureTtl().toMillis());
        try {
            store.complete(failedRecord, now);
            log.warn("幂等首请业务失败，已记录 FAILED key={}, code={}, ttl={}s",
                    idempotencyKey, api.getCode(), properties.getFailureTtl().toSeconds());
        } catch (RuntimeException e) {
            // 兜底 3：失败记录写不进去，只额外打日志，仍然把业务异常抛给调用方
            log.error("幂等失败记录落库失败 key={}：业务异常照常返回，存储异常如下", idempotencyKey, e);
        }
    }

    private ExecutionOutcome replay(String idempotencyKey, IdempotencyRecord record) {
        return switch (record.state()) {
            case SUCCESS -> {
                log.info("幂等重放成功结果 key={}", idempotencyKey);
                int status = record.httpStatus() == null ? 200 : record.httpStatus();
                yield new ExecutionOutcome.Replayed(status, record.responseBody());
            }
            case FAILED -> {
                log.warn("幂等重放失败结果 key={}, code={}（失败 TTL 内不重复执行业务）",
                        idempotencyKey, record.errorCode());
                int status = record.httpStatus() == null ? 500 : record.httpStatus();
                yield throwReplay(new StoredFailureException(status, record.errorCode(),
                        record.errorMessage()));
            }
            case PROCESSING ->
                    throw new IdempotencyInProgressException(idempotencyKey, properties.getProcessingTtl());
        };
    }

    private static <T> T throwReplay(RuntimeException e) {
        throw e;
    }

    private static Object unwrapResponseEntity(Object value) {
        return value instanceof ResponseEntity<?> responseEntity ? responseEntity.getBody() : value;
    }

    private static int statusOf(Object value) {
        return value instanceof ResponseEntity<?> responseEntity
                ? responseEntity.getStatusCode().value()
                : 200;
    }

    private String serialize(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("响应序列化失败", e);
        }
    }

    private ApiException toApiException(Throwable t) {
        if (t instanceof ApiException api) {
            return api;
        }
        return new ApiException(500, "BUSINESS_ERROR",
                "业务执行异常: " + t.getClass().getSimpleName(), t);
    }
}
