package com.zzypiper.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzypiper.api.ApiClient;
import com.zzypiper.api.mock.MockApiClient;
import com.zzypiper.boot.AgentBootstrap;
import com.zzypiper.permission.ModeEnum;
import com.zzypiper.permission.PermissionPolicy;
import com.zzypiper.session.ContentBlock;
import com.zzypiper.session.KindEnum;
import com.zzypiper.session.Message;
import com.zzypiper.session.Session;
import com.zzypiper.session.TurnSummary;
import com.zzypiper.tool.ToolRegistry;
import com.zzypiper.tool.ToolSpec;
import org.junit.Test;

import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.Assert.*;


public class AgentTest {

    @Test
    public void testFullUserToolResultLoop() {
        MockApiClient mockApi = new MockApiClient()
                .thenToolUse("tool-1", "add", "2,2")
                .thenText("The answer is 4.");

        ToolRegistry registry = new ToolRegistry();
        registry.register(
                new ToolSpec("add", "Add numbers", "{\"type\":\"object\",\"properties\":{}}", ModeEnum.WORKSPACE_WRITE, false),
                input -> {
                    int sum = Arrays.stream(input.split(","))
                            .mapToInt(Integer::parseInt)
                            .sum();
                    return String.valueOf(sum);
                }
        );

        Agent agent = new Agent(
                new Session(),
                mockApi,
                new PermissionPolicy(ModeEnum.WORKSPACE_WRITE, registry),
                Arrays.asList("You are a helpful assistant."),
                registry
        );

        TurnSummary summary = agent.runTurn("what is 2 + 2?");

        assertEquals(2, summary.getIterations());
        assertEquals(2, summary.getAssistantMessages().size());
        assertEquals(1, summary.getToolResults().size());
        assertEquals(4, agent.getSession().size());

        Message toolResultMsg = summary.getToolResults().get(0);
        ContentBlock resultBlock = toolResultMsg.getBlocks().get(0);
        assertFalse(resultBlock.isError());
        assertEquals("4", resultBlock.getToolOutput());

        System.out.println("测试1通过：完整Turn Loop正常工作");
    }

    @Test
    public void testMinimaxFullLoop() throws Exception {
        ObjectMapper mapper = new ObjectMapper();

        ApiClient client = AgentBootstrap.loadApiClient(Path.of("."));

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
                        JsonNode node = mapper.readTree(input);
                        int sum = node.get("a").asInt() + node.get("b").asInt();
                        return String.valueOf(sum);
                    } catch (Exception e) {
                        throw new com.zzypiper.tool.ToolException("add failed: " + e.getMessage());
                    }
                }
        );

        Agent agent = new Agent(
                new Session(),
                client,
                new PermissionPolicy(ModeEnum.WORKSPACE_WRITE, registry),
                Arrays.asList("You are a helpful assistant. You MUST always call the add tool to compute sums; never answer arithmetic questions from memory."),
                registry
        );

        TurnSummary summary = agent.runTurn("Please use the add tool to compute: what is 2 + 2?");

        assertTrue("应至少迭代2次（工具调用 + 最终回复）", summary.getIterations() >= 2);
        assertEquals(1, summary.getToolResults().size());

        ContentBlock resultBlock = summary.getToolResults().get(0).getBlocks().get(0);
        assertFalse(resultBlock.isError());
        assertEquals("4", resultBlock.getToolOutput());

        String finalReply = summary.getAssistantMessages()
                .get(summary.getAssistantMessages().size() - 1)
                .getBlocks().stream()
                .filter(b -> b.getKind() == KindEnum.TEXT)
                .map(ContentBlock::getText)
                .reduce("", String::concat);
        System.out.println("测试2通过：MiniMax完整Loop，最终回复: " + finalReply);
    }
}
