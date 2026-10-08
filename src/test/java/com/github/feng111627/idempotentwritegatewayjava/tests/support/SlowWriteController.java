package com.github.feng111627.idempotentwritegatewayjava.tests.support;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.github.feng111627.idempotentwritegatewayjava.idempotency.IdempotencyKeyRequired;

/**
 * 仅供集成测试的“慢业务”写接口：业务在 {@code entered} 处阻塞，
 * 直到测试放开 {@code release}，用于制造“处理中窗口已过但首请仍在真实执行”的场景。
 */
@RestController
@RequestMapping("/test/slow")
public class SlowWriteController {

    private volatile CountDownLatch entered = new CountDownLatch(1);
    private volatile CountDownLatch release = new CountDownLatch(1);
    private volatile int enteredCount = 0;
    private volatile boolean businessRan = false;

    @PostMapping
    @IdempotencyKeyRequired
    public Map<String, Object> slowWrite() throws InterruptedException {
        businessRan = true;
        entered.countDown();
        enteredCount++;
        release.await();
        return Map.of("done", true);
    }

    public void awaitEntered() throws InterruptedException {
        entered.await();
    }

    /** 等待恰好 n 个请求都进入业务阻塞点。 */
    public void awaitEnteredCount(int n) throws InterruptedException {
        while (enteredCount < n) {
            Thread.sleep(5);
        }
    }

    public void releaseBusiness() {
        release.countDown();
    }

    public boolean isBusinessRan() {
        return businessRan;
    }

    public synchronized void reset() {
        businessRan = false;
        enteredCount = 0;
        entered = new CountDownLatch(1);
        release = new CountDownLatch(1);
    }
}
