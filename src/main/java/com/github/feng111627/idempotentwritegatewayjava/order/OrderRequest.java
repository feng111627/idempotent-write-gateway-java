package com.github.feng111627.idempotentwritegatewayjava.order;

/**
 * 创建订单请求体（示例业务）。
 *
 * @param productId 商品 ID，必填
 * @param quantity  购买数量，必须为正
 */
public record OrderRequest(String productId, Integer quantity) {
}
