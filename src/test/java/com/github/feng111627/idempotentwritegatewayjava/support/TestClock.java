package com.github.feng111627.idempotentwritegatewayjava.support;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 测试用可控时钟：手动推进时间，用于验证 TTL 过期。
 */
public class TestClock extends Clock {

    private static final long BASE_MILLIS = 1_700_000_000_000L;

    private final AtomicLong currentMillis = new AtomicLong(BASE_MILLIS);

    @Override
    public ZoneId getZone() {
        return ZoneId.of("UTC");
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }

    @Override
    public Instant instant() {
        return Instant.ofEpochMilli(currentMillis.get());
    }

    @Override
    public long millis() {
        return currentMillis.get();
    }

    public void advanceMillis(long millis) {
        currentMillis.addAndGet(millis);
    }

    public void advanceSeconds(long seconds) {
        advanceMillis(seconds * 1000L);
    }

    /** 重置回基准时间。 */
    public void reset() {
        currentMillis.set(BASE_MILLIS);
    }
}
