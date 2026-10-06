package com.github.feng111627.idempotentwritegatewayjava.web;

import com.github.feng111627.idempotentwritegatewayjava.error.ApiException;
import com.github.feng111627.idempotentwritegatewayjava.error.IdempotencyInProgressException;
import com.github.feng111627.idempotentwritegatewayjava.error.StoredFailureException;
import com.github.feng111627.idempotentwritegatewayjava.model.ErrorResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 把 ApiException 翻译成统一的错误响应：
 * - 处理中附带 Retry-After（秒）；
 * - 重放出来的失败附带 Idempotency-Replayed: true。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorResponse> handleApi(ApiException ex) {
        HttpHeaders headers = new HttpHeaders();
        if (ex instanceof IdempotencyInProgressException inProgress) {
            headers.add(HttpHeaders.RETRY_AFTER,
                    String.valueOf(Math.max(1L, inProgress.getRetryAfter().toSeconds())));
        }
        if (ex instanceof StoredFailureException) {
            headers.add("Idempotency-Replayed", "true");
        }
        log.warn("请求被拒绝或失败: status={}, code={}, message={}",
                ex.getHttpStatus(), ex.getCode(), ex.getMessage());
        return ResponseEntity.status(ex.getHttpStatus())
                .headers(headers)
                .body(ex.toErrorResponse());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception ex) {
        log.error("未预期的服务端异常", ex);
        return ResponseEntity.internalServerError()
                .body(new ErrorResponse("INTERNAL_ERROR", "服务内部错误"));
    }
}
