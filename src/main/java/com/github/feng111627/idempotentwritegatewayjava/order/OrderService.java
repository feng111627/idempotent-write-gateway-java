package com.github.feng111627.idempotentwritegatewayjava.order;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 示例业务服务：创建订单。
 *
 * <p>{@link #invocationCount} 记录“真正执行”次数，用于在测试中证明：
 * 同一幂等键无论并发多少次，业务副作用只产生一次。
 */
@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    private final AtomicInteger invocationCount = new AtomicInteger(0);

    /**
     * 创建订单。
     *
     * @throws IllegalArgumentException 参数非法时抛出（由全局异常处理器转为 400）
     */
    public Map<String, Object> createOrder(OrderRequest request) {
        int times = invocationCount.incrementAndGet();
        log.info("[业务][创建订单] 真正执行业务第 {} 次，productId={}, quantity={}",
                times, request.productId(), request.quantity());

        if (request.productId() == null || request.productId().isBlank()) {
            throw new IllegalArgumentException("productId 不能为空");
        }
        if (request.quantity() == null || request.quantity() <= 0) {
            throw new IllegalArgumentException("quantity 必须为正整数");
        }

        Map<String, Object> order = new LinkedHashMap<>();
        order.put("orderId", "ord_" + UUID.randomUUID());
        order.put("productId", request.productId());
        order.put("quantity", request.quantity());
        order.put("status", "CREATED");
        order.put("createdAt", Instant.now().toString());
        log.info("[业务][创建订单] 业务执行成功，orderId={}", order.get("orderId"));
        return order;
    }

    /** 当前进程累计真正执行业务的次数（供测试与日志观测）。 */
    public int getInvocationCount() {
        return invocationCount.get();
    }

    /** 重置执行计数（测试隔离用）。 */
    public void resetInvocationCount() {
        invocationCount.set(0);
    }
}
