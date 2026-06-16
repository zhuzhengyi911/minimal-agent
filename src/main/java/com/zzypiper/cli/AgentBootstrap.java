package com.zzypiper.cli;

import com.zzypiper.tool.builtin.BashTool;
import com.zzypiper.tool.builtin.EditFileTool;
import com.zzypiper.tool.builtin.GlobTool;
import com.zzypiper.tool.builtin.GrepTool;
import com.zzypiper.tool.builtin.ReadFileTool;
import com.zzypiper.tool.builtin.WriteFileTool;
import com.zzypiper.tool.ToolRegistry;

import java.util.List;

/**
 * Agent 启动的组装层——负责将各子系统初始化并连接在一起。
 *
 * <p>每个子过程独立为一个私有方法，启动顺序在 {@code create()} 中集中管理。
 * 当前已实现：
 * <ul>
 *   <li>{@link #registerBuiltinTools(ToolRegistry)} — 注册系统内置工具</li>
 * </ul>
 * 后续待加入：API 客户端初始化、权限策略配置、Session 恢复、MCP 工具加载等。
 */
public class AgentBootstrap {

    public static ToolRegistry buildRegistry() {
        ToolRegistry registry = new ToolRegistry();
        registerBuiltinTools(registry);
        return registry;
    }

    // -------------------------------------------------------------------------
    // 子过程
    // -------------------------------------------------------------------------

    private static void registerBuiltinTools(ToolRegistry registry) {
        List.of(
                new BashTool(),
                new ReadFileTool(),
                new WriteFileTool(),
                new EditFileTool(),
                new GlobTool(),
                new GrepTool()
        ).forEach(tool -> registry.register(tool.spec(), tool::execute));
    }
}
