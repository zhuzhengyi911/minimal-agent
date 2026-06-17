package com.zzypiper.boot;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * {@code .agent/settings.json} 的顶层配置容器。
 *
 * <p>由 {@link AgentBootstrap#loadSettings(java.nio.file.Path)} 从文件反序列化，
 * 也可在测试中直接构造。
 *
 * <pre>{@code
 * {
 *   "api": {
 *     "provider":  "minimax",
 *     "apiKey":    "sk-...",
 *     "model":     "MiniMax-M3",
 *     "maxTokens": 4096
 *   }
 * }
 * }</pre>
 */
public record AgentSettings(
        @JsonProperty("api") ApiConfig api
) {}
