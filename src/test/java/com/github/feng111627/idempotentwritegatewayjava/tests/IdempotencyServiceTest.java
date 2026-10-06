package com.github.feng111627.idempotentwritegatewayjava.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.github.feng111627.idempotentwritegatewayjava.idempotency.IdempotencyProperties;
import com.github.feng111627.idempotentwritegatewayjava.idempotency.IdempotencyRejectedException;
import com.github.feng111627.idempotentwritegatewayjava.idempotency.IdempotencyService;
import com.github.feng111627.idempotentwritegatewayjava.idempotency.RejectionReason;

/**
 * 幂等服务单元测试：幂等键缺失/格式判定与请求指纹稳定性、区分度。
 */
class IdempotencyServiceTest {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyServiceTest.class);

    private final IdempotencyProperties properties = new IdempotencyProperties();
    private final IdempotencyService service = new IdempotencyService(properties);

    @Test
    @DisplayName("【键缺失】null / 空串 / 纯空白 一律按 MISSING_KEY 拒绝")
    void missingKeyRejected() {
        log.info("====== 用例: missingKeyRejected ======");
        for (String key : new String[]{null, "", "   "}) {
            IdempotencyRejectedException ex = assertThrows(IdempotencyRejectedException.class,
                    () -> service.validateKey(key));
            assertEquals(RejectionReason.MISSING_KEY, ex.getReason());
        }
        log.info("用例通过: null/空串/空白 均判定 MISSING_KEY");
    }

    @Test
    @DisplayName("【键格式】非法字符 / 非法起始 / 超长 按 INVALID_KEY_FORMAT 拒绝")
    void invalidFormatRejected() {
        log.info("====== 用例: invalidFormatRejected ======");
        for (String key : new String[]{
                "-start-with-dash", "has space", "中文键", "a/b", "a.b", "paren(x)"}) {
            IdempotencyRejectedException ex = assertThrows(IdempotencyRejectedException.class,
                    () -> service.validateKey(key), "键 [" + key + "] 应被拒绝");
            assertEquals(RejectionReason.INVALID_KEY_FORMAT, ex.getReason());
        }
        String tooLong = "a".repeat(properties.getMaxKeyLength() + 1);
        assertEquals(RejectionReason.INVALID_KEY_FORMAT,
                assertThrows(IdempotencyRejectedException.class,
                        () -> service.validateKey(tooLong)).getReason());
        log.info("用例通过: 非法字符/起始/超长 均判定 INVALID_KEY_FORMAT");
    }

    @Test
    @DisplayName("【键格式】合法键（字母数字开头，含 _ - : ）通过")
    void validKeysAccepted() {
        log.info("====== 用例: validKeysAccepted ======");
        for (String key : new String[]{"abc", "K-0001", "biz:20261006:001", "A_b-9:"}) {
            service.validateKey(key);
        }
        log.info("用例通过: 合法格式键全部通过校验");
    }

    @Test
    @DisplayName("【请求指纹】相同内容指纹一致，不同内容指纹不同，空体也稳定")
    void requestHashStableAndDistinct() {
        log.info("====== 用例: requestHashStableAndDistinct ======");
        byte[] body1 = "{\"productId\":\"p-1\",\"quantity\":2}".getBytes(StandardCharsets.UTF_8);
        byte[] body1Copy = "{\"productId\":\"p-1\",\"quantity\":2}".getBytes(StandardCharsets.UTF_8);
        byte[] body2 = "{\"productId\":\"p-1\",\"quantity\":3}".getBytes(StandardCharsets.UTF_8);

        assertEquals(service.hashBody(body1), service.hashBody(body1Copy));
        assertNotEquals(service.hashBody(body1), service.hashBody(body2));
        assertEquals(service.hashBody(null), service.hashBody(new byte[0]));
        assertEquals(64, service.hashBody(body1).length());
        log.info("用例通过: SHA-256 指纹同内容相同、异内容不同、长度为 64 位十六进制");
    }

    @Test
    @DisplayName("【配置】TTL 默认为 24 小时，可被覆盖")
    void defaultTtlIs24Hours() {
        log.info("====== 用例: defaultTtlIs24Hours ======");
        assertEquals(Duration.ofHours(24), properties.getTtl());
        properties.setTtl(Duration.ofSeconds(1));
        assertEquals(Duration.ofSeconds(1), properties.getTtl());
        log.info("用例通过: 默认 TTL=24h，且支持配置覆盖");
    }
}
