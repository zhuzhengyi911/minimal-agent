package com.zzypiper.agent;

import com.zzypiper.api.ApiRequest;
import com.zzypiper.api.AssistantEvent;
import com.zzypiper.api.TokenUsage;
import com.zzypiper.api.TurnUsage;
import com.zzypiper.hook.HookResult;
import com.zzypiper.hook.HookRunner;
import com.zzypiper.api.ApiClient;
import com.zzypiper.permission.Outcome;
import com.zzypiper.permission.PermissionPolicy;
import com.zzypiper.session.ContentBlock;
import com.zzypiper.session.KindEnum;
import com.zzypiper.session.Message;
import com.zzypiper.session.Session;
import com.zzypiper.session.TurnSummary;
import com.zzypiper.tool.ToolException;
import com.zzypiper.tool.ToolRegistry;
import lombok.Getter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

@Getter
public class Agent {
    private final Session session;
    private final ApiClient apiClient;
    private final PermissionPolicy permissionPolicy;
    private final List<String> systemPrompt;
    private final ToolRegistry toolRegistry;
    private final int maxIterations;
    private final HookRunner hookRunner;
    private final UsageTracker usageTracker = new UsageTracker();

    public Agent(Session session,
                 ApiClient apiClient,
                 PermissionPolicy permissionPolicy,
                 List<String> systemPrompt,
                 ToolRegistry toolRegistry
    ) {
        this(session, apiClient, permissionPolicy, systemPrompt,
                toolRegistry, Integer.MAX_VALUE, new HookRunner(Arrays.asList(), Arrays.asList()));
    }

    public Agent(Session session,
                 ApiClient apiClient,
                 PermissionPolicy permissionPolicy,
                 List<String> systemPrompt,
                 ToolRegistry toolRegistry,
                 int maxIterations,
                 HookRunner hookRunner
    ) {
        this.session = session;
        this.apiClient = apiClient;
        this.permissionPolicy = permissionPolicy;
        this.systemPrompt = systemPrompt;
        this.toolRegistry = toolRegistry;
        this.maxIterations = maxIterations;
        this.hookRunner = hookRunner;
    }

    public TurnSummary runTurn(String userInput) {
        session.addMessage(Message.userText(userInput));

        List<Message> assistantMessages = new ArrayList<>();
        List<Message> toolResults = new ArrayList<>();
        int iterations = 0;

        List<com.zzypiper.tool.ToolDefinition> effectiveDefinitions =
                toolRegistry.getDefinitions(permissionPolicy.getMode());

        // 调用前估算 input token 数（含系统提示、消息历史、工具 schema）
        int estimatedTokens = UsageTracker.estimateInputTokens(
                systemPrompt, session.getMessages(), effectiveDefinitions);

        // 本 turn 内多次迭代的用量累计
        TokenUsage turnUsage = TokenUsage.ZERO;

        while (true) {
            iterations++;

            if (iterations > maxIterations) {
                throw new RuntimeException("conversation loop exceeded the maximum number of iterations: " + maxIterations);
            }

            List<AssistantEvent> events = apiClient.stream(
                    ApiRequest.of(systemPrompt, session.getMessages(), effectiveDefinitions)
            );

            // 从事件流中提取本次迭代的 token 用量
            TokenUsage iterUsage = events.stream()
                    .filter(e -> e instanceof AssistantEvent.Usage)
                    .map(e -> ((AssistantEvent.Usage) e).usage())
                    .findFirst()
                    .orElse(TokenUsage.ZERO);
            turnUsage = turnUsage.plus(iterUsage);

            Message assistantMessage = buildAssistantMessage(events);

            List<ContentBlock> pendingToolUse = assistantMessage.getBlocks().stream()
                    .filter(b -> b.getKind() == KindEnum.TOOL_USE)
                    .collect(Collectors.toList());

            session.addMessage(assistantMessage);
            assistantMessages.add(assistantMessage);

            if (pendingToolUse.isEmpty()) {
                break;
            }

            for (ContentBlock toolUseBlock : pendingToolUse) {
                Message resultMessage = processToolUse(toolUseBlock);
                session.addMessage(resultMessage);
                toolResults.add(resultMessage);
            }
        }

        // 记录本 turn 的用量
        usageTracker.record(new TurnUsage(
                usageTracker.turns().size(), iterations, estimatedTokens, turnUsage));

        return new TurnSummary(assistantMessages, toolResults, iterations);
    }

    private Message buildAssistantMessage(List<AssistantEvent> events) {
        StringBuilder currentText = new StringBuilder();
        List<ContentBlock> blocks = new ArrayList<>();
        boolean finished = false;

        for (AssistantEvent event : events) {
            if (event instanceof AssistantEvent.TextDelta delta) {
                currentText.append(delta.delta());
            } else if (event instanceof AssistantEvent.ToolUse toolUse) {
                flushTextBlock(currentText, blocks);
                blocks.add(ContentBlock.toolUse(toolUse.id(), toolUse.name(), toolUse.input()));
            } else if (event instanceof AssistantEvent.MessageStop) {
                finished = true;
            }
        }

        flushTextBlock(currentText, blocks);

        if (!finished) {
            throw new RuntimeException("assistant stream ended without a MessageStop event");
        }
        if (blocks.isEmpty()) {
            throw new RuntimeException("assistant stream produced no content");
        }

        return Message.assistant(blocks);
    }

    private void flushTextBlock(StringBuilder text, List<ContentBlock> blocks) {
        if (!text.isEmpty()) {
            blocks.add(ContentBlock.text(text.toString()));
            text.setLength(0);
        }
    }

    private Message processToolUse(ContentBlock toolUseBlock) {
        String toolUseId = toolUseBlock.getToolUseId();
        String toolName = toolUseBlock.getToolName();
        String input = toolUseBlock.getToolInput();

        Outcome permissionOutcome = permissionPolicy.authorize(toolName, input);
        if (permissionOutcome instanceof Outcome.Deny deny) {
            return Message.toolResult(toolUseId, toolName, deny.reason(), false);
        }

        HookResult preHookResult = hookRunner.runPreToolUse(toolName, input);
        if (preHookResult.isDenied()) {
            String denyMsg = preHookResult.getMessages().isEmpty()
                    ? "PreToolUse hook denied tool '" + toolName + "'"
                    : String.join("\n", preHookResult.getMessages());
            return Message.toolResult(toolUseId, toolName, denyMsg, true);
        }

        String output;
        boolean error;
        try {
            ToolRegistry.ToolHandler handler = toolRegistry.getHandler(toolName);
            if (handler == null) {
                throw new ToolException("unknown tool: " + toolName);
            }
            output = handler.handle(input);
            error = false;
        } catch (ToolException toolException) {
            output = toolException.getMessage();
            error = true;
        }
        output = mergeHookFeedBack(preHookResult.getMessages(), output, false);

        HookResult postHookResult = hookRunner.runPostToolUse(toolName, input, output);
        if (postHookResult.isDenied()) {
            error = true;
        }
        output = mergeHookFeedBack(postHookResult.getMessages(), output, postHookResult.isDenied());

        return Message.toolResult(toolUseId, toolName, output, error);
    }

    private String mergeHookFeedBack(List<String> messages, String output, boolean denied) {
        if (messages.isEmpty()) {
            return output;
        }

        List<String> sections = new ArrayList<>();
        if (!output.trim().isEmpty()) {
            sections.add(output);
        }
        String label = denied ? "Hook feedback (denied)" : "Hook feedback";
        sections.add(label + ":\n" + String.join("\n", messages));
        return String.join("\n\n", sections);
    }
}
