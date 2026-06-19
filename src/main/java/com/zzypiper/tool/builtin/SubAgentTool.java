package com.zzypiper.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzypiper.agent.Agent;
import com.zzypiper.agent.AgentOptions;
import com.zzypiper.api.ApiClient;
import com.zzypiper.boot.Defaults;
import com.zzypiper.permission.ModeEnum;
import com.zzypiper.permission.PermissionPolicy;
import com.zzypiper.session.ContentBlock;
import com.zzypiper.session.KindEnum;
import com.zzypiper.session.Message;
import com.zzypiper.session.Session;
import com.zzypiper.session.TurnSummary;
import com.zzypiper.tool.ToolException;
import com.zzypiper.tool.ToolRegistry;
import com.zzypiper.tool.ToolSpec;

import java.util.List;
import java.util.logging.Logger;

/**
 * 子 Agent 孵化工具——Coordinator Mode 的核心。
 *
 * <p>每次调用都创建一个新的 {@link Agent} 实例（Worker），持有独立 {@link Session} 和
 * 从父 {@link ToolRegistry} 过滤出的子 registry（排除 "agent" 工具，防止无限递归）。
 *
 * <p>Worker 使用 {@link Defaults#WORKER_SYSTEM_PROMPT} 而非主 Agent 的 Coordinator prompt，
 * 角色定位为"专注执行单一任务的执行者"。
 *
 * <p>Worker 的异常作为 {@link ToolException} 上抛，由 Coordinator 决定后续处理策略。
 */
public class SubAgentTool implements BuiltinTool {

    private static final Logger LOG = Logger.getLogger(SubAgentTool.class.getName());
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 工具名称，用于在 ToolRegistry 中过滤自身（防止子 Agent 递归调用）。 */
    public static final String TOOL_NAME = "agent";

    private final ApiClient apiClient;
    private final ToolRegistry parentRegistry;
    private final AgentOptions parentOptions;

    /**
     * @param apiClient      LLM 客户端，子 Agent 直接复用
     * @param parentRegistry 父 Agent 的注册表，创建子 Agent 时过滤掉 "agent" 工具
     * @param parentOptions  父 Agent 的配置，子 Agent 共享 executor 和各 loader
     */
    public SubAgentTool(ApiClient apiClient, ToolRegistry parentRegistry, AgentOptions parentOptions) {
        this.apiClient = apiClient;
        this.parentRegistry = parentRegistry;
        this.parentOptions = parentOptions;
    }

    @Override
    public ToolSpec spec() {
        return new ToolSpec(
                TOOL_NAME,
                "Spawn a worker agent to perform a specific task. " +
                "The worker has access to all tools except `agent` itself. " +
                "Use `description` for a 3-5 word summary (shown in logs) and `prompt` for the full task.",
                "{\"type\":\"object\",\"properties\":{" +
                "\"description\":{\"type\":\"string\",\"description\":\"3-5 word task summary\"}," +
                "\"prompt\":{\"type\":\"string\",\"description\":\"Full task instructions for the worker\"}}" +
                ",\"required\":[\"description\",\"prompt\"]}",
                ModeEnum.WORKSPACE_WRITE,
                true  // IO 密集，多个子 Agent 可并行运行
        );
    }

    @Override
    public String execute(String input) throws ToolException {
        try {
            JsonNode node = MAPPER.readTree(input);
            String description = node.path("description").asText("worker task");
            String prompt      = node.get("prompt").asText();

            LOG.fine("[SubAgentTool] spawning worker: " + description);

            ToolRegistry workerRegistry = buildWorkerRegistry();
            Agent worker = new Agent(
                    new Session(),
                    apiClient,
                    new PermissionPolicy(ModeEnum.WORKSPACE_WRITE, workerRegistry),
                    List.of(Defaults.DEFAULT_SYSTEM_PROMPT, Defaults.WORKER_SYSTEM_PROMPT),
                    workerRegistry,
                    parentOptions
            );

            TurnSummary result = worker.runTurn(prompt);

            String output = extractResult(result);
            LOG.fine("[SubAgentTool] worker completed: " + description
                    + " (" + result.getIterations() + " iterations)");
            return output;

        } catch (ToolException e) {
            throw e;
        } catch (Exception e) {
            throw new ToolException("SubAgent failed: " + e.getMessage());
        }
    }

    /**
     * 从父 registry 复制所有工具，排除 "agent" 工具本身，防止子 Agent 递归孵化。
     */
    private ToolRegistry buildWorkerRegistry() {
        ToolRegistry workerRegistry = new ToolRegistry();
        parentRegistry.getSpecs().forEach((name, spec) -> {
            if (!TOOL_NAME.equals(name)) {
                workerRegistry.register(spec, parentRegistry.getHandler(name));
            }
        });
        return workerRegistry;
    }

    /**
     * 从 TurnSummary 中提取最后一条 assistant 消息的文本内容。
     * 若最后一条消息无文本块（仅 tool_use），向前回溯查找最近一条有文本的消息。
     */
    private String extractResult(TurnSummary summary) throws ToolException {
        List<Message> messages = summary.getAssistantMessages();
        if (messages.isEmpty()) {
            throw new ToolException("SubAgent produced no assistant messages");
        }

        // 从最后一条向前扫描，找第一条有文本块的 assistant 消息
        for (int i = messages.size() - 1; i >= 0; i--) {
            String text = messages.get(i).getBlocks().stream()
                    .filter(b -> b.getKind() == KindEnum.TEXT)
                    .map(ContentBlock::getText)
                    .reduce("", String::concat);
            if (!text.isBlank()) {
                return text;
            }
        }

        throw new ToolException("SubAgent completed but produced no text output");
    }
}
