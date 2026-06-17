package com.zzypiper.api;

/**
 * 单个 turn 的用量汇总。
 *
 * <p>一个 turn 内可能包含多次 API 调用（工具调用循环），
 * {@code actual} 是这些调用的用量累计之和。
 */
public record TurnUsage(
        int        turnIndex,
        int        iterations,             // 本 turn 共调用了几次 API
        int        estimatedInputTokens,   // 调用前估算的 input token 数（字符数 / 4）
        TokenUsage actual                  // 调用后实际累计用量
) {
    public double cost(ModelConfig config) {
        return actual.cost(config);
    }
}
