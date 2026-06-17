package com.zzypiper.api;

/**
 * 模型配置——上下文窗口大小和 token 单价。
 *
 * <p>由 {@link ApiClient#getModelConfig()} 返回，供 {@link com.zzypiper.agent.UsageTracker}
 * 计算成本和判断是否触发上下文压缩。
 *
 * <p>单价单位：美元 / 百万 token（$/MTok）。
 */
public record ModelConfig(
        String modelId,
        int    contextWindow,
        int    maxOutputTokens,
        double inputPricePerMToken,
        double outputPricePerMToken,
        double cacheWritePricePerMToken,
        double cacheReadPricePerMToken
) {}
