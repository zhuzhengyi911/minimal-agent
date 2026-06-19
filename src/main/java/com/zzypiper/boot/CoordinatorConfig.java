package com.zzypiper.boot;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * {@code .agent/settings.json} 中 {@code coordinator} 字段的配置容器。
 *
 * <pre>{@code
 * {
 *   "coordinator": {
 *     "enabled": true
 *   }
 * }
 * }</pre>
 */
public record CoordinatorConfig(
        @JsonProperty("enabled") boolean enabled
) {
    /** 默认配置：Coordinator 模式开启。 */
    public static CoordinatorConfig defaultConfig() {
        return new CoordinatorConfig(true);
    }
}
