package com.zzypiper.agent;

import com.zzypiper.agentmd.AgentMdLoader;
import com.zzypiper.hook.HookRunner;
import com.zzypiper.memory.MemoryLoader;
import com.zzypiper.skill.SkillLoader;

import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Agent 的可选行为配置，与核心基础设施依赖（ApiClient、ToolRegistry 等）分离。
 *
 * <p>使用 {@link #defaults()} 获取开箱即用的默认配置；
 * 需要定制时通过构造器传入具体值。
 *
 * <p>{@code executor} 供并发工具批次使用，整个 Agent 树（主 Agent + 所有子 Agent）共享同一实例。
 * CLI 场景使用虚拟线程池；Web 场景可注入有界线程池控制并发上限。
 */
public record AgentOptions(
        int             maxIterations,
        HookRunner      hookRunner,
        MemoryLoader    memoryLoader,
        SkillLoader     skillLoader,
        AgentMdLoader   agentMdLoader,
        ExecutorService executor
) {
    /** 默认配置：无迭代上限、空 Hook、无记忆加载器、无 skill 加载器、虚拟线程池。 */
    public static AgentOptions defaults() {
        return new AgentOptions(
                Integer.MAX_VALUE,
                new HookRunner(Arrays.asList(), Arrays.asList()),
                null,
                null,
                null,
                Executors.newCachedThreadPool()
        );
    }
}
