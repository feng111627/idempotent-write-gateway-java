package com.github.feng111627.idempotentwritegatewayjava.error;

/**
 * 业务自身失败（HTTP 422 为主），幂等层会把它记为 FAILED 并在 TTL 内原样重放。
 */
public class BusinessException extends ApiException {

    public BusinessException(String code, String message) {
        super(422, code, message);
    }

    public BusinessException(int httpStatus, String code, String message) {
        super(httpStatus, code, message);
    }
}
