package com.github.feng111627.idempotentwritegatewayjava.tests;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.AnnotationConfigServletWebServerApplicationContext;
import com.github.feng111627.idempotentwritegatewayjava.Application;

/**
 * 非法保留时长/容量配置必须在启动阶段直接失败，并指出具体配置项。
 *
 * <p>时长“格式不对”（如 {@code abc}）由 Spring Boot 的 Duration 绑定拒绝；
 * “零/负数”由 {@code IdempotencyProperties} 的启动校验拒绝。
 */
class InvalidConfigurationStartupTest {

    private static final Logger log = LoggerFactory.getLogger(InvalidConfigurationStartupTest.class);

    @Test
    @DisplayName("【配置-非法】时长格式不对（processing-ttl=abc）：服务起不来并指明配置项")
    void malformedDurationPreventsStartup() {
        log.info("====== 用例: malformedDurationPreventsStartup ======");
        String message = expectStartupFailure("idempotency.processing-ttl=abc");
        assertTrue(message.contains("processing-ttl"),
                "启动失败信息应指出非法配置项 processing-ttl，实际: " + message);
        log.info("用例通过: 格式非法的时长导致启动失败，信息: {}", firstRelevantLine(message));
    }

    @Test
    @DisplayName("【配置-非法】处理窗口为 0（processing-ttl=0s）：启动校验拒绝并指明配置项")
    void zeroProcessingTtlPreventsStartup() {
        log.info("====== 用例: zeroProcessingTtlPreventsStartup ======");
        String message = expectStartupFailure("idempotency.processing-ttl=0s");
        assertTrue(message.contains("idempotency.processing-ttl"),
                "启动失败信息应包含配置项全名，实际: " + message);
        log.info("用例通过: processing-ttl=0s 启动失败: {}", firstRelevantLine(message));
    }

    @Test
    @DisplayName("【配置-非法】失败窗口为负数（failed-ttl=-1m）：启动校验拒绝")
    void negativeFailedTtlPreventsStartup() {
        log.info("====== 用例: negativeFailedTtlPreventsStartup ======");
        String message = expectStartupFailure("idempotency.failed-ttl=-1m");
        assertTrue(message.contains("idempotency.failed-ttl"),
                "启动失败信息应包含配置项全名，实际: " + message);
        log.info("用例通过: failed-ttl=-1m 启动失败: {}", firstRelevantLine(message));
    }

    @Test
    @DisplayName("【配置-非法】成功窗口为 0：启动校验拒绝")
    void zeroSucceededTtlPreventsStartup() {
        log.info("====== 用例: zeroSucceededTtlPreventsStartup ======");
        String message = expectStartupFailure("idempotency.succeeded-ttl=0s");
        assertTrue(message.contains("idempotency.succeeded-ttl"),
                "启动失败信息应包含配置项全名，实际: " + message);
        log.info("用例通过: succeeded-ttl=0s 启动失败: {}", firstRelevantLine(message));
    }

    @Test
    @DisplayName("【配置-非法】容量上限为 0（max-records=0）：启动校验拒绝")
    void zeroMaxRecordsPreventsStartup() {
        log.info("====== 用例: zeroMaxRecordsPreventsStartup ======");
        String message = expectStartupFailure("idempotency.max-records=0");
        assertTrue(message.contains("idempotency.max-records"),
                "启动失败信息应包含配置项全名，实际: " + message);
        log.info("用例通过: max-records=0 启动失败: {}", firstRelevantLine(message));
    }

    private String expectStartupFailure(String property) {
        try (AnnotationConfigServletWebServerApplicationContext ctx =
                     (AnnotationConfigServletWebServerApplicationContext)
                             new SpringApplicationBuilder(Application.class)
                                     .properties(property, "server.port=0")
                                     .run()) {
            fail("非法配置 [" + property + "] 必须导致启动失败，但上下文成功启动了");
            return "";
        } catch (Exception e) {
            return chainMessages(e);
        }
    }

    /** 汇总异常链上每一层的信息：属性名通常在绑定异常层，非法值描述在根因层。 */
    private static String chainMessages(Throwable e) {
        StringBuilder sb = new StringBuilder();
        Throwable cur = e;
        while (cur != null) {
            if (cur.getMessage() != null) {
                sb.append(cur.getMessage()).append(" | ");
            }
            cur = cur.getCause() == cur ? null : cur.getCause();
        }
        return sb.toString();
    }

    private static String firstRelevantLine(String message) {
        int newline = message.indexOf('\n');
        return newline > 0 ? message.substring(0, newline) : message;
    }
}
