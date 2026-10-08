package com.github.feng111627.idempotentwritegatewayjava.idempotency;

import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 幂等键状态查询入口。
 *
 * <p>{@code GET /api/idempotency/status}，通过 {@code Idempotency-Key} 请求头指定要查询的键。
 * 查询是只读的：不抢占、不续期、不改变任何记录的生命周期。
 *
 * <p>返回的 {@code state} 取值：
 * <ul>
 *     <li>{@code NOT_FOUND}：从未提交过、已过期或已被容量淘汰——应当作新提交；</li>
 *     <li>{@code PROCESSING}：首请仍在处理中，{@code retryAfterSeconds} 提示多久后可再来；</li>
 *     <li>{@code SUCCEEDED}：之前成功过，窗口内同键同内容提交会直接重放；</li>
 *     <li>{@code FAILED_RETRYABLE}：之前失败过、仍在重试窗口内，可用同键同内容重试；</li>
 *     <li>{@code PROCESSING_TIMEOUT}：处理窗口已过，但首请在本实例仍未结束，
 *     此时仍不允许并发执行（避免业务做两遍），请稍后再来。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/idempotency")
public class IdempotencyStatusController {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyStatusController.class);

    private final IdempotencyService idempotencyService;
    private final IdempotencyStore store;

    public IdempotencyStatusController(IdempotencyService idempotencyService,
                                       IdempotencyStore store) {
        this.idempotencyService = idempotencyService;
        this.store = store;
    }

    @GetMapping("/status")
    public Map<String, Object> status(
            @RequestHeader(name = IdempotencyService.HEADER_NAME, required = false) String rawKey) {
        // 与写接口完全一致的键校验（缺失/非法由 GlobalExceptionHandler 翻译为 400）
        idempotencyService.validateKey(rawKey);
        String key = rawKey.trim();

        KeyStatusView view = store.inspect(key);
        log.info("[幂等状态查询] key={} state={} retryAfterSeconds={}",
                key, view.state(), view.retryAfterSeconds());

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("key", key);
        payload.put("state", view.state().name());
        if (view.state() == KeyState.PROCESSING || view.state() == KeyState.PROCESSING_TIMEOUT) {
            payload.put("retryAfterSeconds", view.retryAfterSeconds());
        }
        if (view.expiresAtEpochMillis() > 0) {
            payload.put("expiresAtEpochMillis", view.expiresAtEpochMillis());
        }
        payload.put("hint", hint(view.state()));
        return payload;
    }

    private static String hint(KeyState state) {
        return switch (state) {
            case NOT_FOUND -> "该键没有有效记录（从未提交、已过期或已被淘汰），可以直接提交";
            case PROCESSING -> "首次请求仍在处理中，请按 retryAfterSeconds 提示稍后再来查询或提交";
            case SUCCEEDED -> "首次请求已成功，窗口内用相同的键和请求体提交会直接重放首次响应";
            case FAILED_RETRYABLE -> "之前的请求失败了，仍在重试窗口内，可用相同的键和请求体重试";
            case PROCESSING_TIMEOUT ->
                    "处理窗口已过但首次请求在服务端仍未结束，为避免重复执行业务暂时不能提交，请稍后再来";
        };
    }
}
