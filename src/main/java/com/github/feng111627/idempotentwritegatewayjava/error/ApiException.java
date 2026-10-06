package com.github.feng111627.idempotentwritegatewayjava.error;

import com.github.feng111627.idempotentwritegatewayjava.model.ErrorResponse;

/**
 * 所有可预期业务/网关异常的基类，携带 HTTP 状态码与错误码。
 */
public class ApiException extends RuntimeException {

    private final int httpStatus;
    private final String code;

    public ApiException(int httpStatus, String code, String message) {
        super(message);
        this.httpStatus = httpStatus;
        this.code = code;
    }

    public ApiException(int httpStatus, String code, String message, Throwable cause) {
        super(message, cause);
        this.httpStatus = httpStatus;
        this.code = code;
    }

    public int getHttpStatus() { return httpStatus; }
    public String getCode() { return code; }

    public ErrorResponse toErrorResponse() {
        return new ErrorResponse(code, getMessage());
    }
}
