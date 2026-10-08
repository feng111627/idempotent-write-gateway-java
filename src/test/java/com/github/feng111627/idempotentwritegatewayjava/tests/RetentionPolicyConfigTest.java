package com.github.feng111627.idempotentwritegatewayjava.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import com.github.feng111627.idempotentwritegatewayjava.idempotency.IdempotencyConfig;
import com.github.feng111627.idempotentwritegatewayjava.idempotency.IdempotencyProperties;
import com.github.feng111627.idempotentwritegatewayjava.idempotency.RetentionPolicy;

/**
 * 保留策略配置测试：默认值、老配置兼容、非法配置必须让应用起不来并说清原因。
 */
class RetentionPolicyConfigTest {

    private static final Logger log = LoggerFactory.getLogger(RetentionPolicyConfigTest.class);

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(IdempotencyConfig.class);

    @Test
    @DisplayName("【配置】默认三段时长：处理中 2m / 成功 24h / 失败 30m，容量 10000")
    void defaultsAreReasonable() {
        log.info("====== 用例: defaultsAreReasonable ======");
        RetentionPolicy policy = IdempotencyConfig.defaultPolicy();
        assertEquals(Duration.ofMinutes(2), policy.getProcessingTtl());
        assertEquals(Duration.ofHours(24), policy.getSucceededTtl());
        assertEquals(Duration.ofMinutes(30), policy.getFailedTtl());
        assertEquals(10_000, policy.getMaxRecords());
        assertFalse(policy.isLegacyMode());
        log.info("用例通过: 默认 processing=2m, succeeded=24h, failed=30m, maxRecords=10000");
    }

    @Test
    @DisplayName("【老配置兼容】只配 idempotency.ttl 时三段时长都等于它，且为 legacy 模式不重新锚定")
    void legacySingleTtlKeepsOldMeaning() {
        log.info("====== 用例: legacySingleTtlKeepsOldMeaning ======");
        runner.withPropertyValues("idempotency.ttl=90s").run(context -> {
            RetentionPolicy policy = context.getBean(RetentionPolicy.class);
            assertEquals(Duration.ofSeconds(90), policy.getProcessingTtl());
            assertEquals(Duration.ofSeconds(90), policy.getSucceededTtl());
            assertEquals(Duration.ofSeconds(90), policy.getFailedTtl());
            assertTrue(policy.isLegacyMode(), "只用老配置必须识别为 legacy 模式");
            log.info("用例通过: 老配置 idempotency.ttl=90s → 三段=90s，legacyMode=true（含义不变）");
        });
    }

    @Test
    @DisplayName("【配置】新分状态配置可单独覆盖各段时长")
    void newStyleTiersAreBound() {
        log.info("====== 用例: newStyleTiersAreBound ======");
        runner.withPropertyValues(
                "idempotency.processing-ttl=5s",
                "idempotency.succeeded-ttl=2h",
                "idempotency.failed-ttl=15m",
                "idempotency.max-records=128").run(context -> {
            RetentionPolicy policy = context.getBean(RetentionPolicy.class);
            assertEquals(Duration.ofSeconds(5), policy.getProcessingTtl());
            assertEquals(Duration.ofHours(2), policy.getSucceededTtl());
            assertEquals(Duration.ofMinutes(15), policy.getFailedTtl());
            assertEquals(128, policy.getMaxRecords());
            assertFalse(policy.isLegacyMode());
            log.info("用例通过: 分状态时长与容量均被正确绑定");
        });
    }

    @Test
    @DisplayName("【非法配置】零 / 负时长让应用启动失败，并指明具体是哪一项")
    void zeroOrNegativeDurationFailsStartup() {
        log.info("====== 用例: zeroOrNegativeDurationFailsStartup ======");
        for (String bad : new String[]{
                "idempotency.processing-ttl=0s",
                "idempotency.succeeded-ttl=-1s",
                "idempotency.failed-ttl=0"}) {
            runner.withPropertyValues(bad).run(context -> {
                assertTrue(context.getStartupFailure() != null
                                || context.getStartupFailure() != null);
                Throwable ex = context.getStartupFailure();
                assertNotNullException(ex);
                String msg = String.valueOf(ex.getMessage());
                assertTrue(msg.contains("processing-ttl") || msg.contains("succeeded-ttl")
                                || msg.contains("failed-ttl"),
                        "错误信息必须指出非法配置项，实际: " + msg);
            });
        }
        log.info("用例通过: 0/负数时长均启动失败，且错误信息指明具体配置项");
    }

    @Test
    @DisplayName("【非法配置】时长格式不对让应用启动失败")
    void malformedDurationFailsStartup() {
        log.info("====== 用例: malformedDurationFailsStartup ======");
        runner.withPropertyValues("idempotency.processing-ttl=abc").run(context -> {
            Throwable ex = context.getStartupFailure();
            assertNotNullException(ex);
            boolean bindFailure = false;
            for (Throwable c = ex; c != null; c = c.getCause()) {
                if (c instanceof BindException
                        || (c.getMessage() != null
                            && c.getMessage().contains("Could not bind properties"))) {
                    bindFailure = true;
                    break;
                }
            }
            assertTrue(bindFailure, "格式错误应来自配置绑定失败，应用必须起不来，实际: " + ex);
            log.info("用例通过: 非法时长格式导致绑定失败、应用启动失败");
        });
    }

    @Test
    @DisplayName("【非法配置】容量上限非正数让应用启动失败并指明 max-records")
    void nonPositiveCapacityFailsStartup() {
        log.info("====== 用例: nonPositiveCapacityFailsStartup ======");
        runner.withPropertyValues("idempotency.max-records=0").run(context -> {
            Throwable ex = context.getStartupFailure();
            assertNotNullException(ex);
            assertTrue(String.valueOf(ex.getMessage()).contains("max-records"),
                    "错误信息必须指出 max-records，实际: " + ex.getMessage());
            log.info("用例通过: max-records=0 启动失败并指明配置项");
        });
    }

    @Test
    @DisplayName("【非法配置】老配置与新分状态配置同时出现：启动失败，提示二选一")
    void mixingLegacyAndNewFailsStartup() {
        log.info("====== 用例: mixingLegacyAndNewFailsStartup ======");
        runner.withPropertyValues("idempotency.ttl=1h", "idempotency.succeeded-ttl=2h")
                .run(context -> {
                    Throwable ex = context.getStartupFailure();
                    assertNotNullException(ex);
                    String msg = String.valueOf(ex.getMessage());
                    assertTrue(msg.contains("idempotency.ttl") && msg.contains("不能同时使用"),
                            "必须提示两种配置不能混用，实际: " + msg);
                    log.info("用例通过: 新旧配置混用时启动失败并提示二选一");
                });
    }

    @Test
    @DisplayName("【非法配置】直接用构建器也能拦住非法值（不依赖 Spring 绑定）")
    void builderRejectsInvalidDirectly() {
        log.info("====== 用例: builderRejectsInvalidDirectly ======");
        IdempotencyProperties p = new IdempotencyProperties();
        p.setProcessingTtl(Duration.ZERO);
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> build(p));
        assertTrue(ex.getMessage().contains("processing-ttl"));
        log.info("用例通过: 构建器对零时长直接抛 IllegalStateException: {}", ex.getMessage());
    }

    private static RetentionPolicy build(IdempotencyProperties p) {
        return com.github.feng111627.idempotentwritegatewayjava.idempotency.IdempotencyConfig
                .buildPolicy(p);
    }

    private static void assertNotNullException(Throwable ex) {
        assertTrue(ex != null, "应用必须启动失败");
    }
}
