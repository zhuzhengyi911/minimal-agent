package com.zzypiper.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzypiper.api.ApiClient;
import com.zzypiper.boot.AgentBootstrap;
import com.zzypiper.boot.Defaults;
import com.zzypiper.hook.HookRunner;
import com.zzypiper.permission.ModeEnum;
import com.zzypiper.permission.PermissionPolicy;
import com.zzypiper.session.ContentBlock;
import com.zzypiper.session.KindEnum;
import com.zzypiper.session.Session;
import com.zzypiper.session.TurnSummary;
import com.zzypiper.tool.ToolException;
import com.zzypiper.tool.ToolRegistry;
import com.zzypiper.tool.ToolSpec;
import com.zzypiper.tool.builtin.SubAgentTool;
import org.junit.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Executors;

import static org.junit.Assert.*;

public class CoordinatorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 构建带有 add 工具的 registry。
     * add 工具在 Worker 中可用（SubAgentTool.buildWorkerRegistry 会继承它）。
     */
    private ToolRegistry buildRegistryWithAdd() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(
                new ToolSpec(
                        "add",
                        "Add two integers and return the sum.",
                        "{\"type\":\"object\",\"properties\":{\"a\":{\"type\":\"integer\"},\"b\":{\"type\":\"integer\"}},\"required\":[\"a\",\"b\"]}",
                        ModeEnum.WORKSPACE_WRITE,
                        false
                ),
                input -> {
                    try {
                        JsonNode node = MAPPER.readTree(input);
                        int sum = node.get("a").asInt() + node.get("b").asInt();
                        return String.valueOf(sum);
                    } catch (Exception e) {
                        throw new ToolException("add failed: " + e.getMessage());
                    }
                }
        );
        return registry;
    }

    /**
     * 在 registry 基础上注册 SubAgentTool，构建 Coordinator Agent。
     * Coordinator system prompt = DEFAULT + COORDINATOR，Worker 通过 SubAgentTool 孵化。
     */
    private Agent buildCoordinator(ApiClient client, ToolRegistry registry) {
        AgentOptions options = new AgentOptions(
                Integer.MAX_VALUE,
                new HookRunner(List.of(), List.of()),
                null, null, null,
                Executors.newCachedThreadPool()
        );

        SubAgentTool subAgentTool = new SubAgentTool(client, registry, options);
        registry.register(subAgentTool.spec(), subAgentTool::execute);

        return new Agent(
                new Session(),
                client,
                new PermissionPolicy(ModeEnum.WORKSPACE_WRITE, registry),
                List.of(Defaults.DEFAULT_SYSTEM_PROMPT, Defaults.COORDINATOR_SYSTEM_PROMPT),
                registry,
                options
        );
    }

    /**
     * 场景一：串行执行。
     * 任务 B 依赖任务 A 的结果，Coordinator 必须先等 A 完成再发起 B。
     * 预期：至少 3 次迭代（陈述计划+步骤一 / 步骤二 / 汇总），2 个 tool result。
     */
    @Test
    public void testSerialCoordination() throws Exception {
        ApiClient client = AgentBootstrap.loadApiClient(Path.of("."));
        ToolRegistry registry = buildRegistryWithAdd();
        Agent coordinator = buildCoordinator(client, registry);

        TurnSummary summary = coordinator.runTurn(
                "Use the agent tool to complete two tasks in order: " +
                "first compute 2+3, then compute (the result of the first task)+10. " +
                "You MUST use the agent tool for each step, one at a time."
        );

        assertTrue("应至少迭代3次（步骤一、步骤二各占一次迭代）", summary.getIterations() >= 3);
        assertEquals("应有2个 tool result", 2, summary.getToolResults().size());

        String finalReply = lastAssistantText(summary);
        assertTrue("最终回复应包含结果 15", finalReply.contains("15"));

        System.out.println("串行测试通过，迭代次数: " + summary.getIterations()
                + "，最终回复: " + finalReply);
    }

    /**
     * 场景二：并行执行。
     * 三个计算任务互相独立，Coordinator 应在一次响应里同时发出三个 agent 调用。
     * 预期：2 次迭代（并行批次 + 汇总），3 个 tool result。
     */
    @Test
    public void testParallelCoordination() throws Exception {
        ApiClient client = AgentBootstrap.loadApiClient(Path.of("."));
        ToolRegistry registry = buildRegistryWithAdd();
        Agent coordinator = buildCoordinator(client, registry);

        TurnSummary summary = coordinator.runTurn(
                "Use the agent tool to compute the following three tasks simultaneously: " +
                "2+3, 10+20, and 100+200. " +
                "You MUST use the agent tool and issue all three calls in a single response."
        );

        // 并行的核心证据：3 个 agent 调用在同一次迭代里发出并执行，所以只有 2 次迭代
        assertEquals("并行任务应2次迭代完成", 2, summary.getIterations());
        assertEquals("应有3个 tool result", 3, summary.getToolResults().size());

        String finalReply = lastAssistantText(summary);
        assertTrue("最终回复应包含 5",   finalReply.contains("5"));
        assertTrue("最终回复应包含 30",  finalReply.contains("30"));
        assertTrue("最终回复应包含 300", finalReply.contains("300"));

        System.out.println("并行测试通过，迭代次数: " + summary.getIterations()
                + "，最终回复: " + finalReply);
    }

    private String lastAssistantText(TurnSummary summary) {
        java.util.List<com.zzypiper.session.Message> msgs = summary.getAssistantMessages();
        return msgs.get(msgs.size() - 1)
                .getBlocks().stream()
                .filter(b -> b.getKind() == KindEnum.TEXT)
                .map(ContentBlock::getText)
                .reduce("", String::concat);
    }
}
