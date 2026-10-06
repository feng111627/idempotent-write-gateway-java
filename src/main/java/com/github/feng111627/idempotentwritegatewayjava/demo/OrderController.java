package com.github.feng111627.idempotentwritegatewayjava.demo;

import com.github.feng111627.idempotentwritegatewayjava.annotation.Idempotent;
import com.github.feng111627.idempotentwritegatewayjava.error.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 演示用写接口：POST /api/orders，需要 Idempotency-Key。
 * businessExecutionCount 用于测试验证“同一幂等键只产生一次业务副作用”。
 */
@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private static final Logger log = LoggerFactory.getLogger(OrderController.class);

    private final AtomicInteger businessExecutionCount = new AtomicInteger();
    private final AtomicInteger concurrentInBusiness = new AtomicInteger();
    private final AtomicInteger maxConcurrentInBusiness = new AtomicInteger();

    /**
     * 测试闸门：非空时，业务进入后等待闸门打开再返回，用于制造真实并发窗口。
     */
    private volatile CountDownLatch businessGate;

    @PostMapping
    @Idempotent
    public CreateOrderResponse create(@RequestBody CreateOrderRequest request)
            throws InterruptedException {
        if (request == null || request.getProductId() == null || request.getProductId().isBlank()) {
            throw new BusinessException(400, "ORDER_PRODUCT_REQUIRED", "productId 不能为空");
        }
        if (request.getQuantity() == null || request.getQuantity() <= 0) {
            throw new BusinessException(400, "ORDER_QUANTITY_INVALID", "quantity 必须为正整数");
        }

        int executionNo = businessExecutionCount.incrementAndGet();
        int concurrent = concurrentInBusiness.incrementAndGet();
        maxConcurrentInBusiness.accumulateAndGet(concurrent, Math::max);

        CountDownLatch gate = businessGate;
        if (gate != null) {
            log.info("[下单业务] 进入并发闸门等待 executionNo={}", executionNo);
            gate.await();
        }

        String orderId = UUID.randomUUID().toString();
        long amount = (long) request.getQuantity() * 100L;
        log.info("[下单业务] 真正执行一次: executionNo={}, productId={}, quantity={}, orderId={}",
                executionNo, request.getProductId(), request.getQuantity(), orderId);
        concurrentInBusiness.decrementAndGet();
        return new CreateOrderResponse(orderId, request.getProductId(), request.getQuantity(), amount);
    }

    /** 测试辅助：查询业务真正执行的次数。 */
    public int getBusinessExecutionCount() {
        return businessExecutionCount.get();
    }

    /** 测试辅助：业务中观测到的最大并发数（同一幂等键下必须恒为 1）。 */
    public int getMaxConcurrentInBusiness() {
        return maxConcurrentInBusiness.get();
    }

    /** 测试辅助：安装业务闸门。 */
    public void installGate(CountDownLatch gate) {
        this.businessGate = gate;
    }
}
