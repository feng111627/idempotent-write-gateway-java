package com.github.feng111627.idempotentwritegatewayjava.core;

/**
 * 幂等执行结果：
 * Executed=本次请求真正执行了业务，返回业务返回值；
 * Replayed=命中已成功记录，重放缓存的响应（不再执行业务）；
 * 命中失败记录则抛 StoredFailureException。
 */
public sealed interface ExecutionOutcome
        permits ExecutionOutcome.Executed, ExecutionOutcome.Replayed {

    record Executed(Object returnValue) implements ExecutionOutcome {
    }

    record Replayed(int httpStatus, String responseBody) implements ExecutionOutcome {
    }
}
