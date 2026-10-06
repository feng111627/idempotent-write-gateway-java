package com.github.feng111627.idempotentwritegatewayjava.demo;

/**
 * 演示写接口返回体。
 */
public record CreateOrderResponse(String orderId, String productId, int quantity, long amount) {
}
