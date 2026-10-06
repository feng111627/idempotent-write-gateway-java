package com.github.feng111627.idempotentwritegatewayjava.model;

/**
 * 统一错误返回体，code 用于区分被拒绝的具体原因。
 */
public record ErrorResponse(String code, String message) {
}
