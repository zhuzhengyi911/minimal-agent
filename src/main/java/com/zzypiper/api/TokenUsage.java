package com.zzypiper.api;

/**
 * 单次 API 调用的 token 用量，对应响应体中的 {@code usage} 字段。
 */
public record TokenUsage(
        int inputTokens,
        int outputTokens,
        int cacheCreationInputTokens,   // 写入 prompt cache 的 token
        int cacheReadInputTokens        // 命中 prompt cache 的 token
) {
    public static final TokenUsage ZERO = new TokenUsage(0, 0, 0, 0);

    /** 聚合两次调用的用量（用于 turn 内多次迭代求和）。 */
    public TokenUsage plus(TokenUsage other) {
        return new TokenUsage(
                this.inputTokens            + other.inputTokens,
                this.outputTokens           + other.outputTokens,
                this.cacheCreationInputTokens + other.cacheCreationInputTokens,
                this.cacheReadInputTokens   + other.cacheReadInputTokens
        );
    }

    /** 按模型单价计算本次调用的费用（美元）。 */
    public double cost(ModelConfig config) {
        return (inputTokens             * config.inputPricePerMToken()
              + outputTokens            * config.outputPricePerMToken()
              + cacheCreationInputTokens * config.cacheWritePricePerMToken()
              + cacheReadInputTokens    * config.cacheReadPricePerMToken())
               / 1_000_000.0;
    }
}
