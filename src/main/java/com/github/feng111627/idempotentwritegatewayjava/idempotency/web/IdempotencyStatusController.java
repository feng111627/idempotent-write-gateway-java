package com.github.feng111627.idempotentwritegatewayjava.idempotency.web;

import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.github.feng111627.idempotentwritegatewayjava.idempotency.IdempotencyService;
import com.github.feng111627.idempotentwritegatewayjava.idempotency.IdempotencyStore;
import com.github.feng111627.idempotentwritegatewayjava.idempotency.KeyState;
import com.github.feng111627.idempotentwritegatewayjava.idempotency.KeyStateView;

/**
 * 幂等键状态查询入口：{@code GET /api/idempotency/keys/status}。
 *
 * <p>通过 {@code Idempotency-Key} 请求头指定要查询的键，返回该键当前的对外状态：
 * NOT_FOUND / PROCESSING / SUCCEEDED / FAILED_RETRYABLE / EXPIRED。
 * 处理中时额外给出 {@code retryAfterSeconds}，调用方无需盲目重试。
 */
@RestController
@RequestMapping("/api/idempotency/keys")
public class IdempotencyStatusController {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyStatusController.class);

    private final IdempotencyStore store;
    private final IdempotencyService idempotencyService;

    public IdempotencyStatusController(IdempotencyStore store,
                                       IdempotencyService idempotencyService) {
        this.store = store;
        this.idempotencyService = idempotencyService;
    }

    @GetMapping(value = "/status", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> status(
            @RequestHeader(value = IdempotencyService.HEADER_NAME, required = false) String rawKey) {
        // 复用写接口的键校验规则，查询入口同样拒绝缺失/非法键
        idempotencyService.validateKey(rawKey);
        String key = rawKey.trim();

        KeyStateView view = store.query(key);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("key", view.key());
        body.put("state", view.state().name());
        if (view.retryAfterSeconds() != null) {
            body.put("retryAfterSeconds", view.retryAfterSeconds());
        }
        if (view.expiresAtEpochMillis() != null) {
            body.put("expiresAtEpochMillis", view.expiresAtEpochMillis());
        }
        log.info("[幂等查询] key={} state={} retryAfter={}s",
                key, view.state().name(), view.retryAfterSeconds());
        return ResponseEntity.ok(body);
    }
}
