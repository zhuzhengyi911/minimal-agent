package com.zzypiper.mcp;

import com.zzypiper.permission.ModeEnum;
import com.zzypiper.tool.ToolRegistry;
import com.zzypiper.tool.ToolSpec;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * 管理多个 MCP server 的生命周期，并将其暴露的工具动态注册到 {@link ToolRegistry}。
 *
 * <h2>工具命名规则</h2>
 * <p>为避免与内置工具名称冲突，MCP 工具统一使用前缀：
 * <pre>mcp__{serverName}__{toolName}</pre>
 * 例如：filesystem server 的 read_file 工具 → {@code mcp__filesystem__read_file}
 *
 * <h2>生命周期</h2>
 * <ol>
 *   <li>{@link #add(McpServerConfig)} — 启动 server、拉取工具列表、注册进 ToolRegistry</li>
 *   <li>Agent 运行时自动发现新注册的工具（getDefinitions 每轮调用）</li>
 *   <li>{@link #shutdown()} — 停止所有 server 子进程</li>
 * </ol>
 *
 * <h2>权限默认值</h2>
 * <p>MCP 工具的能力未知，默认要求 {@link ModeEnum#WORKSPACE_WRITE}。
 * 如需更严格限制，可在 {@link McpServerConfig} 中扩展权限字段（当前未实现）。
 */
public class McpManager {

    private static final Logger LOG = Logger.getLogger(McpManager.class.getName());

    /** MCP 工具名称前缀，格式：{@code mcp__{serverName}__} */
    public static final String TOOL_PREFIX = "mcp__";
    private static final String SEP = "__";

    private final ToolRegistry registry;
    private final List<McpClient> clients = new ArrayList<>();

    public McpManager(ToolRegistry registry) {
        this.registry = registry;
    }

    /**
     * 启动一个 MCP server，拉取工具列表并注册到 {@link ToolRegistry}。
     *
     * <p>注册的工具名称格式：{@code mcp__{serverName}__{toolName}}
     *
     * @throws McpException 若 server 启动或 initialize 握手失败
     */
    public void add(McpServerConfig config) throws McpException {
        McpClient client = new McpClient(config);
        client.start();

        List<McpToolDef> tools = client.listTools();
        for (McpToolDef tool : tools) {
            String qualifiedName = TOOL_PREFIX + config.name() + SEP + tool.name();
            ToolSpec spec = new ToolSpec(
                    qualifiedName,
                    "[MCP:" + config.name() + "] " + tool.description(),
                    tool.inputSchemaJson(),
                    ModeEnum.WORKSPACE_WRITE,
                    false  // MCP 工具能力未知，保守默认串行
            );
            registry.register(spec, input -> client.callTool(tool.name(), input));
            LOG.info("Registered MCP tool: " + qualifiedName);
        }

        clients.add(client);
        LOG.info("MCP server '" + config.name() + "' started, " + tools.size() + " tool(s) registered.");
    }

    /**
     * 停止所有已启动的 MCP server 子进程。
     * 不抛异常——逐一停止，忽略单个失败。
     */
    public void shutdown() {
        for (McpClient client : clients) {
            try {
                client.stop();
            } catch (Exception e) {
                LOG.warning("Error stopping MCP client '" + client.name() + "': " + e.getMessage());
            }
        }
        clients.clear();
    }

    /** 返回当前已注册的 MCP server 数量。 */
    public int serverCount() {
        return clients.size();
    }
}
