package com.github.feng111627.idempotentwritegatewayjava.support;

import org.junit.jupiter.api.extension.AfterTestExecutionCallback;
import org.junit.jupiter.api.extension.BeforeTestExecutionCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.TestWatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

/**
 * 用例级日志：开始时打印覆盖的场景，结束时打印通过/失败。
 * 通过 META-INF/services 自动挂到所有测试。
 */
public class ScenarioLoggingExtension implements BeforeTestExecutionCallback, TestWatcher {

    private static final Logger log = LoggerFactory.getLogger("SCENARIO");

    @Override
    public void beforeTestExecution(ExtensionContext context) {
        log.info("▶ 开始用例 [{}] {}", scenario(context), context.getDisplayName());
    }

    @Override
    public void testSuccessful(ExtensionContext context) {
        log.info("✅ 用例通过 [{}] {}", scenario(context), context.getDisplayName());
    }

    @Override
    public void testFailed(ExtensionContext context, Throwable cause) {
        log.error("❌ 用例失败 [{}] {} -> {}", scenario(context), context.getDisplayName(),
                cause == null ? "(无异常信息)" : cause.toString());
    }

    @Override
    public void testAborted(ExtensionContext context, Throwable cause) {
        log.warn("⚠️ 用例中止 [{}] {}", scenario(context), context.getDisplayName());
    }

    @Override
    public void testDisabled(ExtensionContext context, Optional<String> reason) {
        log.info("⏭️ 用例跳过 [{}] {} 原因: {}", scenario(context), context.getDisplayName(),
                reason.orElse("(无)"));
    }

    private String scenario(ExtensionContext context) {
        return context.getTestClass().map(Class::getSimpleName).orElse("?");
    }
}
