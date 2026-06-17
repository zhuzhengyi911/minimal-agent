package com.zzypiper.agent;

import com.zzypiper.hook.HookRunner;
import com.zzypiper.memory.MemoryLoader;

import java.util.Arrays;

/**
 * Agent 的可选行为配置，与核心基础设施依赖（ApiClient、ToolRegistry 等）分离。
 *
 * <p>使用 {@link #defaults()} 获取开箱即用的默认配置；
 * 需要定制时通过构造器传入具体值。
 */
public record AgentOptions(
        int          maxIterations,
        HookRunner   hookRunner,
        MemoryLoader memoryLoader
) {
    /** 默认配置：无迭代上限、空 Hook、无记忆加载器。 */
    public static AgentOptions defaults() {
        return new AgentOptions(
                Integer.MAX_VALUE,
                new HookRunner(Arrays.asList(), Arrays.asList()),
                null
        );
    }
}
