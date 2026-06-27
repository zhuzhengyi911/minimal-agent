package com.zzypiper.cli;

import com.zzypiper.agent.Agent;
import com.zzypiper.api.UsageTracker;
import com.zzypiper.boot.AgentBootstrap;
import com.zzypiper.boot.AgentSettings;
import com.zzypiper.memory.MemoryLoader;
import com.zzypiper.permission.PermissionPolicy;
import com.zzypiper.session.Session;
import com.zzypiper.skill.SkillLoader;
import com.zzypiper.tool.ToolRegistry;

/**
 * 斜杠命令执行时能访问的所有组件。
 * 通过快捷方法避免深层调用链。
 */
public record CommandContext(
        Agent agent,
        AgentSettings settings,
        StartupArgs startupArgs,
        Renderer renderer,
        AgentBootstrap.BuildResult buildResult
) {
    public Session session() {
        return agent.getSession();
    }

    public UsageTracker usageTracker() {
        return agent.getUsageTracker();
    }

    public PermissionPolicy permissionPolicy() {
        return agent.getPermissionPolicy();
    }

    public ToolRegistry toolRegistry() {
        return agent.getToolRegistry();
    }

    public SkillLoader skillLoader() {
        return agent.getOptions().skillLoader();
    }

    public MemoryLoader memoryLoader() {
        return agent.getOptions().memoryLoader();
    }
}
