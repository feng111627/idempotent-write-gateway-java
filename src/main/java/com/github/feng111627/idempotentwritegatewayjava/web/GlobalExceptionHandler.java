package com.github.feng111627.idempotentwritegatewayjava.web;

import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import com.github.feng111627.idempotentwritegatewayjava.idempotency.IdempotencyRejectedException;
import com.github.feng111627.idempotentwritegatewayjava.idempotency.RejectionReason;

/**
 * 全局异常处理：把各类异常翻译为统一结构的错误响应。
 *
 * <p>注意：幂等过滤器在 Filter 层就地处理了幂等拒绝/存储不可用；
 * 这里的 {@link IdempotencyRejectedException} 处理作为兜底（如其他位置直接调用时）。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** 幂等键校验失败（过滤器通常已提前处理，此处兜底）。 */
    @ExceptionHandler(IdempotencyRejectedException.class)
    public ResponseEntity<Map<String, Object>> handleIdempotencyRejected(
            IdempotencyRejectedException ex) {
        RejectionReason reason = ex.getReason();
        HttpStatus status = reason == RejectionReason.STORAGE_UNAVAILABLE
                ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.BAD_REQUEST;
        log.info("[全局异常] 幂等拒绝 reason={} status={}", reason, status.value());
        return ResponseEntity.status(status).body(errorBody(
                "IDEMPOTENCY_" + reason.name(), reason.name(), ex.getMessage()));
    }

    /** 业务参数非法。 */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgument(IllegalArgumentException ex) {
        log.info("[全局异常] 业务参数非法: {}", ex.getMessage());
        return ResponseEntity.badRequest().body(errorBody(
                "INVALID_REQUEST", null, ex.getMessage()));
    }

    /** 请求体无法解析（JSON 格式错误等）。 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleNotReadable(HttpMessageNotReadableException ex) {
        log.info("[全局异常] 请求体无法解析: {}", ex.toString());
        return ResponseEntity.badRequest().body(errorBody(
                "MALFORMED_REQUEST_BODY", null, "请求体不是合法的 JSON"));
    }

    /** 未预期的业务异常：视为业务失败（幂等层会标记 FAILED 允许重试）。 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleUnexpected(Exception ex) {
        log.error("[全局异常] 未预期异常: ", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorBody(
                "INTERNAL_ERROR", null, "服务内部错误"));
    }

    private Map<String, Object> errorBody(String code, String rejectionReason, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        if (rejectionReason != null) {
            body.put("rejectionReason", rejectionReason);
        }
        body.put("message", message);
        return body;
    }
}
