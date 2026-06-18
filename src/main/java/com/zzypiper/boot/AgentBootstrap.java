package com.zzypiper.boot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzypiper.api.ApiClient;
import com.zzypiper.api.minimax.MinimaxApiClient;
import com.zzypiper.mcp.McpManager;
import com.zzypiper.mcp.McpServerConfig;
import com.zzypiper.mcp.TransportType;
import com.zzypiper.agentmd.AgentMdLoader;
import com.zzypiper.memory.MemoryLoader;
import com.zzypiper.skill.SkillLoader;
import com.zzypiper.tool.ToolRegistry;
import com.zzypiper.tool.builtin.BashTool;
import com.zzypiper.tool.builtin.EditFileTool;
import com.zzypiper.tool.builtin.GlobTool;
import com.zzypiper.tool.builtin.GrepTool;
import com.zzypiper.tool.builtin.LoadSkillTool;
import com.zzypiper.tool.builtin.ReadFileTool;
import com.zzypiper.tool.builtin.ReadMemoryTool;
import com.zzypiper.tool.builtin.WriteFileTool;
import com.zzypiper.tool.builtin.WriteMemoryTool;

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
 * <p>职责：
 * <ul>
 *   <li>从 {@value #SETTINGS_PATH} 读取 API 配置，创建 {@link ApiClient}</li>
 *   <li>注册内置工具到 {@link ToolRegistry}</li>
 *   <li>读取 {@value #MCP_CONFIG_PATH}，启动 MCP server 并注册工具</li>
 * </ul>
 *
 * <h2>配置文件</h2>
 * <pre>
 * .agent/
 *   settings.json          # 实际配置，已 gitignore
 *   settings.example.json  # 模板，提交到仓库
 *   mcp.json               # MCP server 列表
 * </pre>
 *
 * <h2>API Key 加载顺序</h2>
 * <ol>
 *   <li>{@code .agent/settings.json} 中的 {@code api.apiKey} 字段</li>
 *   <li>环境变量 {@code MINIMAX_API_KEY}（当文件中的 apiKey 为空时）</li>
 *   <li>两者均缺 → 抛出 {@link IllegalStateException}</li>
 * </ol>
 */
public class AgentBootstrap {

    private static final Logger LOG = Logger.getLogger(AgentBootstrap.class.getName());
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** MCP 配置文件相对路径（相对于工作目录）。 */
    public static final String MCP_CONFIG_PATH = ".agent/mcp.json";

    /** Agent 设置文件相对路径（相对于工作目录）。 */
    public static final String SETTINGS_PATH = ".agent/settings.json";

    /**
     * 完整启动：读取配置、创建 ApiClient、注册工具、初始化 MCP。
     */
    public static BuildResult build(Path workDir) {
        MemoryLoader  memoryLoader  = new MemoryLoader(workDir);
        SkillLoader   skillLoader   = new SkillLoader(workDir);
        AgentMdLoader agentMdLoader = new AgentMdLoader(workDir);

        ToolRegistry registry = new ToolRegistry();
        registerBuiltinTools(registry);
        registerMemoryTools(registry, memoryLoader);
        registerSkillTools(registry, skillLoader);

        McpManager mcpManager = initMcp(registry, workDir);
        ApiClient apiClient = loadApiClient(workDir);

        return new BuildResult(registry, mcpManager, apiClient, memoryLoader, skillLoader, agentMdLoader);
    }

    /** 仅构建内置工具注册表（不加载 MCP 和 API 配置），供测试或简单场景使用。 */
    public static ToolRegistry buildRegistry() {
        ToolRegistry registry = new ToolRegistry();
        registerBuiltinTools(registry);
        return registry;
    }

    /**
     * 读取 {@value #SETTINGS_PATH} 并创建 {@link ApiClient}。
     * 若 settings.json 不存在或其中 apiKey 为空，则回退到读取环境变量。
     */
    public static ApiClient loadApiClient(Path workDir) {
        AgentSettings settings = loadSettings(workDir);
        return createApiClient(settings.api());
    }

    // -------------------------------------------------------------------------
    // 配置加载
    // -------------------------------------------------------------------------

    private static AgentSettings loadSettings(Path workDir) {
        Path settingsFile = workDir.resolve(SETTINGS_PATH);
        if (Files.exists(settingsFile)) {
            try {
                AgentSettings settings = MAPPER.readValue(settingsFile.toFile(), AgentSettings.class);
                LOG.fine("Loaded settings from " + settingsFile);
                return settings;
            } catch (Exception e) {
                LOG.warning("Failed to parse " + SETTINGS_PATH + ": " + e.getMessage() + ". Falling back to env vars.");
            }
        } else {
            LOG.fine("No settings file found at " + settingsFile + ", using env vars.");
        }

        // 回退：从环境变量构建最小配置
        return new AgentSettings(new ApiConfig("minimax", null, "MiniMax-M3", 4096));
    }

    private static ApiClient createApiClient(ApiConfig config) {
        // 密钥优先读文件，为空则回退到环境变量
        String apiKey = config.apiKey();
        if (apiKey == null || apiKey.isBlank()) {
            apiKey = System.getenv("MINIMAX_API_KEY");
        }
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                    "API key not configured. " +
                    "Set \"api.apiKey\" in " + SETTINGS_PATH + " or export MINIMAX_API_KEY=<key>.");
        }

        String model     = config.model()     != null ? config.model()     : "MiniMax-M3";
        int    maxTokens = config.maxTokens() > 0     ? config.maxTokens() : 4096;

        return new MinimaxApiClient(apiKey, model, maxTokens);
    }

    // -------------------------------------------------------------------------
    // 内置工具
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

    private static void registerMemoryTools(ToolRegistry registry, MemoryLoader memoryLoader) {
        WriteMemoryTool writeTool = new WriteMemoryTool(memoryLoader);
        ReadMemoryTool  readTool  = new ReadMemoryTool(memoryLoader);
        registry.register(writeTool.spec(), writeTool::execute);
        registry.register(readTool.spec(),  readTool::execute);
    }

    private static void registerSkillTools(ToolRegistry registry, SkillLoader skillLoader) {
        LoadSkillTool loadTool = new LoadSkillTool(skillLoader);
        registry.register(loadTool.spec(), loadTool::execute);
    }

    // -------------------------------------------------------------------------
    // MCP 初始化
    // -------------------------------------------------------------------------

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
        node.path("env").fields().forEachRemaining(e -> env.put(e.getKey(), e.getValue().asText()));

        String typeStr = node.path("type").asText("stdio").toUpperCase();
        TransportType type = TransportType.valueOf(typeStr);

        return new McpServerConfig(name, command, args, env, type);
    }

    // -------------------------------------------------------------------------
    // 返回值
    // -------------------------------------------------------------------------

    /**
     * {@link #build(Path)} 的返回值，持有完整初始化的工具注册表、MCP 管理器、
     * API 客户端、记忆加载器、skill 加载器和 AGENT.md 加载器。
     */
    public record BuildResult(ToolRegistry registry, McpManager mcpManager, ApiClient apiClient,
                              MemoryLoader memoryLoader, SkillLoader skillLoader,
                              AgentMdLoader agentMdLoader) {
        /** 返回默认 system prompt 列表，供 CLI 创建 Agent 时使用。 */
        public List<String> defaultSystemPrompt() {
            return List.of(Defaults.DEFAULT_SYSTEM_PROMPT);
        }

        /** 释放所有 MCP server 资源。如果没有 MCP，此方法为空操作。 */
        public void shutdown() {
            if (mcpManager != null) {
                mcpManager.shutdown();
            }
        }
    }
}
