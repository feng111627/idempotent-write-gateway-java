package com.github.feng111627.idempotentwritegatewayjava.idempotency;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 幂等相关配置（前缀 idempotency）。
 */
@ConfigurationProperties(prefix = "idempotency")
public class IdempotencyProperties {

    /** 幂等记录的默认存活时间，默认 24 小时。 */
    private Duration ttl = Duration.ofHours(24);

    /** 幂等键允许的最大长度，默认 128。 */
    private int maxKeyLength = 128;

    public Duration getTtl() {
        return ttl;
    }

    public void setTtl(Duration ttl) {
        this.ttl = ttl;
    }

    public int getMaxKeyLength() {
        return maxKeyLength;
    }

    public void setMaxKeyLength(int maxKeyLength) {
        this.maxKeyLength = maxKeyLength;
    }
}
