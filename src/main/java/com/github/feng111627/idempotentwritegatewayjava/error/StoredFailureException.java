package com.github.feng111627.idempotentwritegatewayjava.error;

/**
 * 命中一条 FAILED 记录时原样重放的失败结果。
 */
public class StoredFailureException extends ApiException {
    public StoredFailureException(int httpStatus, String code, String message) {
        super(httpStatus, code, message);
    }
}
