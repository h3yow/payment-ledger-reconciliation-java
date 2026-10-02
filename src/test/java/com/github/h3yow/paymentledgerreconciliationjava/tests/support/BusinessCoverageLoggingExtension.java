package com.github.h3yow.paymentledgerreconciliationjava.tests.support;

import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.AfterTestExecutionCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.BeforeTestExecutionCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.TestExecutionExceptionHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 测试业务覆盖日志扩展：在单测日志中按“测试类（业务域）→ 用例（@DisplayName）→ 执行结果”
 * 打印覆盖了哪些业务以及各业务的用例与测试情况，方便从 {@code mvn test} 输出直接核对覆盖面。
 *
 * <p>通过 ServiceLoader（{@code META-INF/services}）对全部 JUnit 5 测试自动注册，无需逐个类标注。
 */
public class BusinessCoverageLoggingExtension implements BeforeAllCallback, AfterAllCallback,
        BeforeTestExecutionCallback, AfterTestExecutionCallback, TestExecutionExceptionHandler {

    private static final Logger coverageLog = LoggerFactory.getLogger("BUSINESS_COVERAGE");

    private static final ExtensionContext.Namespace NAMESPACE =
            ExtensionContext.Namespace.create(BusinessCoverageLoggingExtension.class);

    @Override
    public void beforeAll(ExtensionContext context) {
        String business = context.getRequiredTestClass().getSimpleName();
        coverageLog.info("══════ 业务[{}] 测试开始 ══════", business);
    }

    @Override
    public void beforeTestExecution(ExtensionContext context) {
        context.getStore(NAMESPACE).put("startNanos", System.nanoTime());
        coverageLog.info("▶ [{}] 用例: {}",
                context.getRequiredTestClass().getSimpleName(), displayName(context));
    }

    @Override
    public void afterTestExecution(ExtensionContext context) {
        Long start = context.getStore(NAMESPACE).get("startNanos", Long.class);
        long elapsedMs = start == null ? -1 : (System.nanoTime() - start) / 1_000_000;
        boolean failed = context.getExecutionException().isPresent();
        coverageLog.info("{} [{}] {} （{} ms）", failed ? "✗ 失败" : "✓ 通过",
                context.getRequiredTestClass().getSimpleName(), displayName(context), elapsedMs);
    }

    @Override
    public void handleTestExecutionException(ExtensionContext context, Throwable throwable) throws Throwable {
        coverageLog.warn("✗ 异常 [{}] {} -> {}: {}",
                context.getRequiredTestClass().getSimpleName(), displayName(context),
                throwable.getClass().getSimpleName(), throwable.getMessage());
        throw throwable;
    }

    @Override
    public void afterAll(ExtensionContext context) {
        coverageLog.info("══════ 业务[{}] 测试结束 ══════", context.getRequiredTestClass().getSimpleName());
    }

    private String displayName(ExtensionContext context) {
        return context.getDisplayName();
    }
}
