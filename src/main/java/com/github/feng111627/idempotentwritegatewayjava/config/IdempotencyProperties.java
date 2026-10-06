package com.github.feng111627.idempotentwritegatewayjava.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 幂等相关配置（idempotency.*）。
 */
@ConfigurationProperties(prefix = "idempotency")
public class IdempotencyProperties {

    private String headerName = "Idempotency-Key";
    private String keyPattern = "^[A-Za-z0-9_-]{8,128}$";
    private Duration successTtl = Duration.ofHours(24);
    private Duration failureTtl = Duration.ofMinutes(5);
    private Duration processingTtl = Duration.ofMinutes(10);
    private Duration cleanupInterval = Duration.ofMinutes(1);
    private boolean storageFailClosed = true;

    public String getHeaderName() { return headerName; }
    public void setHeaderName(String headerName) { this.headerName = headerName; }
    public String getKeyPattern() { return keyPattern; }
    public void setKeyPattern(String keyPattern) { this.keyPattern = keyPattern; }
    public Duration getSuccessTtl() { return successTtl; }
    public void setSuccessTtl(Duration successTtl) { this.successTtl = successTtl; }
    public Duration getFailureTtl() { return failureTtl; }
    public void setFailureTtl(Duration failureTtl) { this.failureTtl = failureTtl; }
    public Duration getProcessingTtl() { return processingTtl; }
    public void setProcessingTtl(Duration processingTtl) { this.processingTtl = processingTtl; }
    public Duration getCleanupInterval() { return cleanupInterval; }
    public void setCleanupInterval(Duration cleanupInterval) { this.cleanupInterval = cleanupInterval; }
    public boolean isStorageFailClosed() { return storageFailClosed; }
    public void setStorageFailClosed(boolean storageFailClosed) { this.storageFailClosed = storageFailClosed; }
}
