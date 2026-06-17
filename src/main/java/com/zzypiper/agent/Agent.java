package com.zzypiper.agent;

import com.zzypiper.api.ApiRequest;
import com.zzypiper.api.AssistantEvent;
import com.zzypiper.api.TokenUsage;
import com.zzypiper.api.TurnUsage;
import com.zzypiper.api.UsageTracker;
import com.zzypiper.compaction.Compactor;
import com.zzypiper.hook.HookResult;
import com.zzypiper.memory.MemoryLoader;
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

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Getter
public class Agent {
    private final Session session;
    private final ApiClient apiClient;
    private final PermissionPolicy permissionPolicy;
    private final List<String> systemPrompt;
    private final ToolRegistry toolRegistry;
    private final AgentOptions options;
    private final UsageTracker usageTracker = new UsageTracker();
    private final Compactor compactor;

    public Agent(Session session,
                 ApiClient apiClient,
                 PermissionPolicy permissionPolicy,
                 List<String> systemPrompt,
                 ToolRegistry toolRegistry
    ) {
        this(session, apiClient, permissionPolicy, systemPrompt,
                toolRegistry, AgentOptions.defaults());
    }

    public Agent(Session session,
                 ApiClient apiClient,
                 PermissionPolicy permissionPolicy,
                 List<String> systemPrompt,
                 ToolRegistry toolRegistry,
                 AgentOptions options
    ) {
        this.session = session;
        this.apiClient = apiClient;
        this.permissionPolicy = permissionPolicy;
        this.systemPrompt = systemPrompt;
        this.toolRegistry = toolRegistry;
        this.options = options;
        this.compactor = new Compactor(apiClient);
    }

    public TurnSummary runTurn(String userInput) {
        Instant startedAt = Instant.now();
        session.addMessage(Message.userText(userInput));

        List<com.zzypiper.tool.ToolDefinition> tools = toolRegistry.getDefinitions(permissionPolicy.getMode());

        // 每个 turn 动态拼装 system prompt：base + 记忆使用指令 + 最新记忆索引
        List<String> effectivePrompt = buildEffectivePrompt();

        // 用当前 session 的估算值实时判断是否需要压缩，比依赖上一轮 actual 更准确
        int estimatedTokens = UsageTracker.estimateInputTokens(effectivePrompt, session.getMessages(), tools);
        if (UsageTracker.exceedsThreshold(estimatedTokens, apiClient.getModelConfig())) {
            compactor.compact(session, effectivePrompt)
                     .ifPresent(usageTracker::recordCompaction);
            // 压缩后消息减少，重新估算以准确记录本轮实际起点
            estimatedTokens = UsageTracker.estimateInputTokens(effectivePrompt, session.getMessages(), tools);
        }

        List<Message> assistantMessages = new ArrayList<>();
        List<Message> toolResults = new ArrayList<>();
        TokenUsage turnUsage = TokenUsage.ZERO;
        int iterations = 0;

        while (true) {
            iterations++;
            if (iterations > options.maxIterations()) {
                throw new RuntimeException("conversation loop exceeded the maximum number of iterations: " + options.maxIterations());
            }

            List<AssistantEvent> events = apiClient.stream(ApiRequest.of(effectivePrompt, session.getMessages(), tools));
            turnUsage = turnUsage.plus(extractUsage(events));

            Message assistantMessage = buildAssistantMessage(events);
            session.addMessage(assistantMessage);
            assistantMessages.add(assistantMessage);

            List<ContentBlock> toolUses = pendingToolUses(assistantMessage);
            if (toolUses.isEmpty()) break;

            for (ContentBlock toolUse : toolUses) {
                Message result = processToolUse(toolUse);
                session.addMessage(result);
                toolResults.add(result);
            }
        }

        Instant finishedAt = Instant.now();
        usageTracker.record(new TurnUsage(
                usageTracker.turns().size(), iterations, estimatedTokens, turnUsage,
                startedAt, finishedAt));

        return new TurnSummary(assistantMessages, toolResults, iterations);
    }

    /**
     * 每次 turn 动态构建实际使用的 system prompt。
     * = baseSystemPrompt + 记忆使用指令（如有 MemoryLoader）+ 最新记忆索引（如非空）
     */
    private List<String> buildEffectivePrompt() {
        if (options.memoryLoader() == null) {
            return systemPrompt;
        }
        List<String> effective = new ArrayList<>(systemPrompt);
        effective.add(MemoryLoader.USAGE_INSTRUCTIONS);
        String memoryIndex = options.memoryLoader().load();
        if (!memoryIndex.isBlank()) {
            effective.add("## Current Memory Index\n\n" + memoryIndex);
        }
        return effective;
    }

    private TokenUsage extractUsage(List<AssistantEvent> events) {
        return events.stream()
                .filter(e -> e instanceof AssistantEvent.Usage)
                .map(e -> ((AssistantEvent.Usage) e).usage())
                .findFirst()
                .orElse(TokenUsage.ZERO);
    }

    private List<ContentBlock> pendingToolUses(Message message) {
        return message.getBlocks().stream()
                .filter(b -> b.getKind() == KindEnum.TOOL_USE)
                .collect(Collectors.toList());
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

        HookResult preHookResult = options.hookRunner().runPreToolUse(toolName, input);
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

        HookResult postHookResult = options.hookRunner().runPostToolUse(toolName, input, output);
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
