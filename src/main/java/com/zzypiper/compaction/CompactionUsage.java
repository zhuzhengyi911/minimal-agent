package com.zzypiper.compaction;

import com.zzypiper.api.TokenUsage;

import java.time.Duration;
import java.time.Instant;

/**
 * 单次上下文压缩操作的用量记录。
 *
 * <p>属于系统开销（system overhead），独立于 {@link com.zzypiper.api.TurnUsage} 存储，
 * 避免压缩成本污染用户 turn 的统计数据。
 *
 * <p>若 {@link #fellBack} 为 {@code true}，表示摘要 API 调用失败、退化为截断，
 * 此时 {@link #actual} 为 {@link TokenUsage#ZERO}（无实际 API 消耗）。
 */
public record CompactionUsage(
        int        compactionIndex,   // 第几次压缩（与 session.compactionCount 对应，从 1 开始）
        int        messagesRemoved,   // 被摘要掉的消息数
        int        messagesKept,      // 保留的近期消息数
        TokenUsage actual,            // 摘要 API 调用的真实 token 用量
        Instant    startedAt,
        Instant    finishedAt,
        boolean    fellBack           // true = 摘要失败，退化为截断
) {
    public Duration duration() {
        return Duration.between(startedAt, finishedAt);
    }
}
