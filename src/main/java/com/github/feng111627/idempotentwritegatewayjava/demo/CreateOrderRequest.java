package com.github.feng111627.idempotentwritegatewayjava.demo;

/**
 * 演示写接口请求体：下单。
 */
public class CreateOrderRequest {

    private String productId;
    private Integer quantity;

    public String getProductId() { return productId; }
    public void setProductId(String productId) { this.productId = productId; }
    public Integer getQuantity() { return quantity; }
    public void setQuantity(Integer quantity) { this.quantity = quantity; }
}
