package com.github.feng111627.idempotentwritegatewayjava.idempotency;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerExecutionChain;
import org.springframework.web.servlet.mvc.method.RequestMappingInfoHandlerMapping;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 幂等写接口的入口过滤器。
 *
 * <p>仅对方法上标注了 {@link IdempotencyKeyRequired} 的接口生效，编排流程：
 * <ol>
 *     <li>校验 Idempotency-Key 是否存在、格式是否合法；</li>
 *     <li>读取原始请求体并计算 SHA-256 指纹；</li>
 *     <li>向存储原子抢占该幂等键；</li>
 *     <li>已成功 → 原样重放；处理中 → 409 + Retry-After；内容冲突 → 409 拒绝；
 *     存储故障 → 503 fail-closed，绝不执行业务；</li>
 *     <li>抢到执行权 → 执行业务：2xx 固化成功结果；其余状态视为业务失败，
 *     标记 FAILED 允许重试；无论结果如何，业务结束后都释放本实例 in-flight 护栏；</li>
 *     <li>业务已成功但结果固化失败：响应已经正确送达客户端，不报错、不重放假结果，
 *     记录停留在 PROCESSING，在处理窗口内重试一律得到 409，杜绝重复副作用。</li>
 * </ol>
 */
@Component
public class IdempotencyFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyFilter.class);

    private final IdempotencyService idempotencyService;
    private final IdempotencyStore store;
    private final IdempotencyProperties properties;
    private final RequestMappingInfoHandlerMapping handlerMapping;
    private final ObjectMapper objectMapper;

    public IdempotencyFilter(IdempotencyService idempotencyService,
                             IdempotencyStore store,
                             IdempotencyProperties properties,
                             RequestMappingInfoHandlerMapping handlerMapping,
                             ObjectMapper objectMapper) {
        this.idempotencyService = idempotencyService;
        this.store = store;
        this.properties = properties;
        this.handlerMapping = handlerMapping;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {
        if (!isIdempotentEndpoint(request)) {
            filterChain.doFilter(request, response);
            return;
        }

        String rawKey = request.getHeader(IdempotencyService.HEADER_NAME);
        // 校验失败会抛出带明确原因的异常，这里就地翻译成错误响应（Filter 中的异常不会走 @ControllerAdvice）
        try {
            idempotencyService.validateKey(rawKey);
        } catch (IdempotencyRejectedException e) {
            writeRejection(response, e.getReason(), e.getMessage());
            return;
        }
        String key = rawKey.trim();

        CachedBodyHttpServletRequest cachedRequest = new CachedBodyHttpServletRequest(request);
        String requestHash = idempotencyService.hashBody(cachedRequest.getCachedBody());
        log.info("[幂等] 收到幂等请求 key={} requestHash={}",
                key, IdempotencyService.shortHash(requestHash));

        ClaimResult claim;
        try {
            claim = store.claim(key, requestHash, properties.getRetentionWindows());
        } catch (IdempotencyStorageException e) {
            // fail-closed：存储不可用/容量已满且无法安全腾位时直接拒绝，绝不执行业务，
            // 避免“无记录兜底”的重复执行
            log.error("[幂等] key={} 抢占阶段存储不可用，fail-closed 拒绝请求: {}",
                    key, e.getMessage());
            writeRejection(response, RejectionReason.STORAGE_UNAVAILABLE,
                    "幂等存储暂时不可用，为避免重复提交，请求已被拒绝，请稍后重试");
            return;
        }

        switch (claim) {
            case ClaimResult.Replaying replaying -> {
                log.info("[幂等] key={} 重放首次成功结果，业务不会再次执行", key);
                replay(response, replaying.record());
            }
            case ClaimResult.Processing processing -> {
                log.info("[幂等] key={} 首次请求仍在处理中，返回 409 PROCESSING，Retry-After={}s",
                        key, processing.retryAfterSeconds());
                writeProcessing(response, processing.retryAfterSeconds());
            }
            case ClaimResult.Rejected rejected -> {
                writeRejection(response, rejected.reason(),
                        "同一个 Idempotency-Key 不能用于不同的请求内容，请为每次新提交生成新的幂等键");
            }
            case ClaimResult.Granted ignored -> executeOnce(key, cachedRequest, response, filterChain, false);
            case ClaimResult.Retryable ignored -> executeOnce(key, cachedRequest, response, filterChain, true);
        }
    }

    /** 拿到执行权后真正执行业务，并根据结果固化记录。 */
    private void executeOnce(String key,
                             CachedBodyHttpServletRequest cachedRequest,
                             HttpServletResponse response,
                             FilterChain filterChain,
                             boolean isRetry)
            throws ServletException, IOException {
        log.info("[幂等] key={} 获得执行权（{}），开始执行业务", key, isRetry ? "失败重试" : "首次提交");
        CachedBodyHttpServletResponse cachedResponse = new CachedBodyHttpServletResponse(response);
        try {
            filterChain.doFilter(cachedRequest, cachedResponse);
            cachedResponse.flush();

            int httpStatus = cachedResponse.getStatus();
            if (httpStatus >= 200 && httpStatus < 300) {
                persistSuccess(key, httpStatus, cachedResponse);
            } else {
                log.warn("[幂等] key={} 业务返回非 2xx（status={}），标记 FAILED，允许同内容重试", key, httpStatus);
                store.fail(key);
            }
        } catch (Throwable t) {
            markFailed(key, t);
            rethrow(t);
        } finally {
            // 无论成功、失败还是异常，业务都已经走完，解除“执行中”护栏；
            // 护栏的唯一职责是覆盖“业务还在跑”的时间窗，不能提前释放
            store.releaseInFlight(key);
        }
    }

    /** 仅允许 Servlet/IO 异常、运行时异常与 Error 原样向上抛。 */
    private static void rethrow(Throwable t) throws ServletException, IOException {
        if (t instanceof RuntimeException re) {
            throw re;
        }
        if (t instanceof Error err) {
            throw err;
        }
        if (t instanceof ServletException se) {
            throw se;
        }
        if (t instanceof IOException ioe) {
            throw ioe;
        }
        throw new IllegalStateException(t);
    }

    /** 业务成功后固化结果；固化失败时的兜底策略见类注释。 */
    private void persistSuccess(String key, int httpStatus, CachedBodyHttpServletResponse cachedResponse) {
        String contentType = cachedResponse.getContentType();
        String body = new String(cachedResponse.getCachedBody(), StandardCharsets.UTF_8);
        try {
            store.complete(key, httpStatus, contentType, body);
        } catch (IdempotencyStorageException e) {
            // 业务已经执行完且响应已送达客户端——绝不能对客户端报错，更不能返回伪造的重放。
            // 记录停留在 PROCESSING：处理窗口内同键重试会得到 409 PROCESSING，避免重复副作用；
            // 处理窗口过后键才重新可用。运维必须通过此日志/监控发现并介入。
            log.error("[幂等][兜底] key={} 业务已成功但结果固化失败！响应已正常返回客户端，"
                            + "该键在处理窗口({})内重试将返回 409，请勿盲目重试。原因: {}",
                    key, properties.getRetentionWindows().processingTtl(), e.getMessage());
        }
    }

    private void markFailed(String key, Throwable error) {
        log.warn("[幂等] key={} 业务执行抛出异常，标记 FAILED: {}", key, error.toString());
        try {
            store.fail(key);
        } catch (RuntimeException e) {
            // 标记失败本身失败不能掩盖原始业务异常，仅记录日志；记录保持 PROCESSING 直到窗口过期
            log.error("[幂等][兜底] key={} 标记 FAILED 也失败了，记录将保持 PROCESSING 直到窗口过期: {}",
                    key, e.getMessage());
        }
    }

    /** 把已固化的成功结果原样重放。 */
    private void replay(HttpServletResponse response, IdempotencyRecord record) throws IOException {
        response.setStatus(record.getHttpStatus());
        if (record.getContentType() != null) {
            response.setContentType(record.getContentType());
        }
        response.setHeader("Idempotency-Replayed", "true");
        byte[] body = record.getBody() == null
                ? new byte[0] : record.getBody().getBytes(StandardCharsets.UTF_8);
        response.setContentLength(body.length);
        response.getOutputStream().write(body);
        response.getOutputStream().flush();
    }

    private void writeProcessing(HttpServletResponse response, int retryAfterSeconds)
            throws IOException {
        response.setStatus(HttpStatus.CONFLICT.value());
        response.setHeader("Retry-After", Integer.toString(retryAfterSeconds));
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("code", "IDEMPOTENCY_PROCESSING");
        payload.put("retryAfterSeconds", retryAfterSeconds);
        payload.put("message", "相同幂等键的首次请求仍在处理中，建议 " + retryAfterSeconds
                + " 秒后使用相同的 Idempotency-Key 重试，或调用状态查询接口确认");
        objectMapper.writeValue(response.getOutputStream(), payload);
    }

    private void writeRejection(HttpServletResponse response, RejectionReason reason, String message)
            throws IOException {
        int status = switch (reason) {
            case MISSING_KEY, INVALID_KEY_FORMAT, REQUEST_MISMATCH -> HttpStatus.BAD_REQUEST.value();
            case STORAGE_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE.value();
        };
        String code = switch (reason) {
            case MISSING_KEY -> "IDEMPOTENCY_MISSING_KEY";
            case INVALID_KEY_FORMAT -> "IDEMPOTENCY_INVALID_KEY_FORMAT";
            case REQUEST_MISMATCH -> "IDEMPOTENCY_REQUEST_MISMATCH";
            case STORAGE_UNAVAILABLE -> "IDEMPOTENCY_STORAGE_UNAVAILABLE";
        };
        log.info("[幂等] 拒绝请求 reason={} status={}", reason, status);
        writeJson(response, status, code, reason, message);
    }

    private void writeJson(HttpServletResponse response, int status, String code,
                           RejectionReason reason, String message) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("code", code);
        if (reason != null) {
            payload.put("rejectionReason", reason.name());
        }
        payload.put("message", message);
        objectMapper.writeValue(response.getOutputStream(), payload);
    }

    /** 通过 HandlerMapping 找到目标处理器方法，判断是否标注了 {@link IdempotencyKeyRequired}。 */
    private boolean isIdempotentEndpoint(HttpServletRequest request) {
        try {
            HandlerExecutionChain executionChain = handlerMapping.getHandler(request);
            if (executionChain == null || !(executionChain.getHandler() instanceof HandlerMethod handlerMethod)) {
                return false;
            }
            return handlerMethod.hasMethodAnnotation(IdempotencyKeyRequired.class);
        } catch (Exception e) {
            // 路径无法匹配等情况交给后续流程处理（404/异常解析），这里不做幂等
            log.debug("[幂等] 未匹配到处理器方法，跳过幂等处理: {}", e.toString());
            return false;
        }
    }
}
