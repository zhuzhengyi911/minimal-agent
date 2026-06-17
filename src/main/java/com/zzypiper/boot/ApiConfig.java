package com.zzypiper.boot;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * API 客户端配置——provider、密钥、模型名和最大输出 token 数。
 *
 * <p>由 {@link AgentSettings} 持有，从 {@code .agent/settings.json} 的 {@code "api"} 字段读取。
 * 若 {@code apiKey} 为空，{@link AgentBootstrap} 会回退到读取环境变量（如 {@code MINIMAX_API_KEY}）。
 */
public record ApiConfig(
        @JsonProperty("provider")  String provider,
        @JsonProperty("apiKey")    String apiKey,
        @JsonProperty("model")     String model,
        @JsonProperty("maxTokens") int    maxTokens
) {}
