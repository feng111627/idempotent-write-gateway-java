package com.github.feng111627.idempotentwritegatewayjava.store;

import com.github.feng111627.idempotentwritegatewayjava.model.IdempotencyRecord;

/**
 * 存储层抢占结果：
 * Acquired=首次抢占成功；Completed=命中已完成记录可重放；
 * InProgress=首请仍在处理；Conflict=同键不同请求内容。
 */
public sealed interface AcquireResult
        permits AcquireResult.Acquired, AcquireResult.Completed, AcquireResult.InProgress, AcquireResult.Conflict {

    record Acquired(IdempotencyRecord record) implements AcquireResult {
    }

    record Completed(IdempotencyRecord record) implements AcquireResult {
    }

    record InProgress(long retryAfterSeconds) implements AcquireResult {
    }

    record Conflict(String storedFingerprint) implements AcquireResult {
    }
}
