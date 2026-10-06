package com.github.feng111627.idempotentwritegatewayjava.order;

import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.github.feng111627.idempotentwritegatewayjava.idempotency.IdempotencyKeyRequired;

/**
 * 订单写接口（演示幂等保护的接入点）。
 *
 * <p>只需在写方法上标注 {@link IdempotencyKeyRequired}，
 * 幂等过滤器即自动完成键校验、并发互斥、结果重放与生命周期管理。
 */
@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    /**
     * 创建订单。
     * 请求头需携带 {@code Idempotency-Key}，例如：
     * {@code curl -X POST .../api/orders -H 'Idempotency-Key: biz-20261006-0001' -d '{...}'}
     */
    @PostMapping
    @IdempotencyKeyRequired
    public ResponseEntity<Map<String, Object>> create(@RequestBody OrderRequest request) {
        return ResponseEntity.ok(orderService.createOrder(request));
    }
}
