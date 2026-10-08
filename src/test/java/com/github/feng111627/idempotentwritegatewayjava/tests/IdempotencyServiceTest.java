package com.github.feng111627.idempotentwritegatewayjava.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import com.github.feng111627.idempotentwritegatewayjava.idempotency.RetentionWindows;

/**
 * 幂等服务单元测试：幂等键缺失/格式判定、请求指纹、保留窗口配置解析与非法配置拒绝。
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
    @DisplayName("【配置】默认三段保留窗口：处理 2m < 失败 30m < 成功 24h，状态切换重算")
    void defaultPerStateWindows() {
        log.info("====== 用例: defaultPerStateWindows ======");
        RetentionWindows windows = properties.getRetentionWindows();
        assertEquals(Duration.ofMinutes(2), windows.processingTtl());
        assertEquals(Duration.ofMinutes(30), windows.failedTtl());
        assertEquals(Duration.ofHours(24), windows.succeededTtl());
        assertTrue(windows.rebaseOnTransition(), "分状态默认配置应在状态切换时重算窗口");
        assertTrue(windows.processingTtl().compareTo(windows.failedTtl()) < 0);
        assertTrue(windows.failedTtl().compareTo(windows.succeededTtl()) < 0);
        log.info("用例通过: 默认窗口 处理=2m、失败=30m、成功=24h，且依次递增");
    }

    @Test
    @DisplayName("【配置-兼容】只配老的 idempotency.ttl：三段窗口取同值，且沿用固定截止（不重算）旧语义")
    void legacySingleTtlKeepsOldSemantics() {
        log.info("====== 用例: legacySingleTtlKeepsOldSemantics ======");
        IdempotencyProperties legacy = new IdempotencyProperties();
        legacy.setTtl(Duration.ofHours(24));

        RetentionWindows windows = legacy.getRetentionWindows();
        assertEquals(Duration.ofHours(24), windows.processingTtl());
        assertEquals(Duration.ofHours(24), windows.succeededTtl());
        assertEquals(Duration.ofHours(24), windows.failedTtl());
        assertFalse(windows.rebaseOnTransition(),
                "只配老 ttl 时必须保持旧语义：从抢占时刻起算的固定截止时间");
        log.info("用例通过: 老配置 idempotency.ttl=24h → 三段窗口均为 24h 且不重算，升级后含义不变");
    }

    @Test
    @DisplayName("【配置-兼容】老 ttl 与分段时长可混用：缺省的分段回退取 ttl，出现分段配置则切换为重算模式")
    void legacyTtlWithPartialOverride() {
        log.info("====== 用例: legacyTtlWithPartialOverride ======");
        IdempotencyProperties mixed = new IdempotencyProperties();
        mixed.setTtl(Duration.ofHours(24));
        mixed.setProcessingTtl(Duration.ofMinutes(1));

        RetentionWindows windows = mixed.getRetentionWindows();
        assertEquals(Duration.ofMinutes(1), windows.processingTtl());
        assertEquals(Duration.ofHours(24), windows.succeededTtl());
        assertEquals(Duration.ofHours(24), windows.failedTtl());
        assertTrue(windows.rebaseOnTransition());
        log.info("用例通过: 老 ttl 与分段配置混用时，未覆盖的分段回退为 ttl，且切换为重算模式");
    }

    @Test
    @DisplayName("【配置-非法】零/负时长必须拒绝，并指明具体配置项")
    void invalidTtlRejectedAtStartup() {
        log.info("====== 用例: invalidTtlRejectedAtStartup ======");
        assertInvalidWithProperty(Duration.ZERO, "idempotency.processing-ttl", p -> p.setProcessingTtl(Duration.ZERO));
        assertInvalidWithProperty(Duration.ofMinutes(-5), "idempotency.failed-ttl", p -> p.setFailedTtl(Duration.ofMinutes(-5)));
        assertInvalidWithProperty(Duration.ZERO, "idempotency.ttl", p -> p.setTtl(Duration.ZERO));
        log.info("用例通过: 零/负时长一律拒绝，异常信息指明配置项与非法值");
    }

    @Test
    @DisplayName("【配置-非法】容量/键长为非正整数必须拒绝")
    void invalidBoundsRejectedAtStartup() {
        log.info("====== 用例: invalidBoundsRejectedAtStartup ======");
        IdempotencyProperties badCapacity = new IdempotencyProperties();
        badCapacity.setMaxRecords(0);
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                badCapacity::getRetentionWindows);
        assertTrue(ex.getMessage().contains("idempotency.max-records"), ex.getMessage());

        IdempotencyProperties badLength = new IdempotencyProperties();
        badLength.setMaxKeyLength(-1);
        assertTrue(assertThrows(IllegalStateException.class, badLength::getRetentionWindows)
                .getMessage().contains("idempotency.max-key-length"));
        log.info("用例通过: max-records=0 / max-key-length=-1 均启动失败并指明配置项");
    }

    private void assertInvalidWithProperty(Duration value, String expectedProperty,
                                           java.util.function.Consumer<IdempotencyProperties> setter) {
        IdempotencyProperties props = new IdempotencyProperties();
        setter.accept(props);
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                props::getRetentionWindows, "非法时长 " + value + " 必须导致启动失败");
        assertTrue(ex.getMessage().contains(expectedProperty),
                "异常信息应包含配置项名 " + expectedProperty + "，实际: " + ex.getMessage());
    }
}
