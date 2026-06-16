package com.zzypiper.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzypiper.mcp.McpManager;
import com.zzypiper.mcp.McpServerConfig;
import com.zzypiper.mcp.TransportType;
import com.zzypiper.tool.ToolRegistry;
import com.zzypiper.tool.builtin.BashTool;
import com.zzypiper.tool.builtin.EditFileTool;
import com.zzypiper.tool.builtin.GlobTool;
import com.zzypiper.tool.builtin.GrepTool;
import com.zzypiper.tool.builtin.ReadFileTool;
import com.zzypiper.tool.builtin.WriteFileTool;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Agent 启动的组装层——负责将各子系统初始化并连接在一起。
 *
 * <p>每个子过程独立为一个私有方法，启动顺序在 {@code buildRegistry()} 中集中管理。
 * 当前已实现：
 * <ul>
 *   <li>{@link #registerBuiltinTools(ToolRegistry)} — 注册系统内置工具</li>
 *   <li>{@link #initMcp(ToolRegistry, Path)} — 读取 .agent/mcp.json，启动 MCP server 并注册工具</li>
 * </ul>
 * 后续待加入：API 客户端初始化、权限策略配置、Session 恢复等。
 *
 * <h2>MCP 配置文件格式（.agent/mcp.json）</h2>
 * <pre>
 * {
 *   "servers": [
 *     {
 *       "name": "filesystem",
 *       "type": "stdio",
 *       "command": "node",
 *       "args": ["/path/to/server.js"],
 *       "env": { "KEY": "value" }
 *     }
 *   ]
 * }
 * </pre>
 */
public class AgentBootstrap {

    private static final Logger LOG = Logger.getLogger(AgentBootstrap.class.getName());
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 默认 MCP 配置文件相对路径（相对于工作目录）。 */
    public static final String MCP_CONFIG_PATH = ".agent/mcp.json";

    /**
     * 构建并返回一个完整初始化的 {@link ToolRegistry}。
     * 内置工具已注册；如果当前目录下存在 {@value #MCP_CONFIG_PATH}，MCP 工具也会被加载。
     *
     * <p>返回的 {@link McpManager} 用于生命周期管理（需要在退出时调用 {@code shutdown()}）。
     * 如果没有 MCP 配置，{@code result.mcpManager()} 为 null。
     */
    public static BuildResult build(Path workDir) {
        ToolRegistry registry = new ToolRegistry();
        registerBuiltinTools(registry);

        McpManager mcpManager = initMcp(registry, workDir);
        return new BuildResult(registry, mcpManager);
    }

    /** 仅构建内置工具注册表（不加载 MCP），供测试或简单场景使用。 */
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

    /**
     * 读取 {@value #MCP_CONFIG_PATH}，启动所有配置的 MCP server 并将其工具注册到 registry。
     *
     * @return McpManager 实例（用于后续 shutdown），若配置文件不存在则返回 null
     */
    private static McpManager initMcp(ToolRegistry registry, Path workDir) {
        Path configFile = workDir.resolve(MCP_CONFIG_PATH);
        if (!Files.exists(configFile)) {
            LOG.fine("No MCP config found at " + configFile + ", skipping MCP initialization.");
            return null;
        }

        McpManager manager = new McpManager(registry);
        try {
            JsonNode root = MAPPER.readTree(configFile.toFile());
            JsonNode servers = root.path("servers");
            if (!servers.isArray()) {
                LOG.warning("mcp.json: 'servers' field is missing or not an array, skipping.");
                return manager;
            }

            for (JsonNode server : servers) {
                try {
                    McpServerConfig config = parseServerConfig(server);
                    manager.add(config);
                } catch (Exception e) {
                    LOG.warning("Failed to start MCP server '" + server.path("name").asText("?") + "': " + e.getMessage());
                }
            }
        } catch (Exception e) {
            LOG.warning("Failed to parse MCP config at " + configFile + ": " + e.getMessage());
        }

        return manager;
    }

    private static McpServerConfig parseServerConfig(JsonNode node) {
        String name    = node.get("name").asText();
        String command = node.get("command").asText();

        List<String> args = new ArrayList<>();
        for (JsonNode arg : node.path("args")) {
            args.add(arg.asText());
        }

        Map<String, String> env = new HashMap<>();
        JsonNode envNode = node.path("env");
        envNode.fields().forEachRemaining(e -> env.put(e.getKey(), e.getValue().asText()));

        String typeStr = node.path("type").asText("stdio").toUpperCase();
        TransportType type = TransportType.valueOf(typeStr);

        return new McpServerConfig(name, command, args, env, type);
    }

    // -------------------------------------------------------------------------
    // 返回值
    // -------------------------------------------------------------------------

    /**
     * {@link #build(Path)} 的返回值，持有完整初始化的工具注册表和 MCP 生命周期管理器。
     */
    public record BuildResult(ToolRegistry registry, McpManager mcpManager) {
        /** 释放所有 MCP server 资源。如果没有 MCP，此方法为空操作。 */
        public void shutdown() {
            if (mcpManager != null) {
                mcpManager.shutdown();
            }
        }
    }
}
