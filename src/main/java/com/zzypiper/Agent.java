package com.zzypiper;

import com.zzypiper.api.ApiRequest;
import com.zzypiper.api.AssistantEvent;
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
import com.zzypiper.tool.ToolDefinition;
import com.zzypiper.tool.ToolException;
import com.zzypiper.tool.ToolExecutor;
import com.zzypiper.tool.ToolRegistry;
import lombok.Getter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

@Getter
public class Agent {
    private final Session session;
    private final ApiClient apiClient;
    private final ToolExecutor toolExecutor;
    private final PermissionPolicy permissionPolicy;
    private final List<String> systemPrompt;
    private final List<ToolDefinition> toolDefinitions;  // legacy: null when toolRegistry is used
    private final ToolRegistry toolRegistry;              // nullable: preferred over toolDefinitions
    private final int maxIterations;
    private final HookRunner hookRunner;

    // -------------------------------------------------------------------------
    // 传统构造器（保持向后兼容）
    // -------------------------------------------------------------------------

    public Agent(Session session,
                 ApiClient apiClient,
                 ToolExecutor toolExecutor,
                 PermissionPolicy permissionPolicy,
                 List<String> systemPrompt
    ) {
        this(session, apiClient, toolExecutor, permissionPolicy, systemPrompt,
                Collections.emptyList(), Integer.MAX_VALUE, new HookRunner(Arrays.asList(), Arrays.asList()));
    }

    public Agent(Session session,
                 ApiClient apiClient,
                 ToolExecutor toolExecutor,
                 PermissionPolicy permissionPolicy,
                 List<String> systemPrompt,
                 List<ToolDefinition> toolDefinitions
    ) {
        this(session, apiClient, toolExecutor, permissionPolicy, systemPrompt,
                toolDefinitions, Integer.MAX_VALUE, new HookRunner(Arrays.asList(), Arrays.asList()));
    }

    public Agent(Session session,
                 ApiClient apiClient,
                 ToolExecutor toolExecutor,
                 PermissionPolicy permissionPolicy,
                 List<String> systemPrompt,
                 List<ToolDefinition> toolDefinitions,
                 int maxIterations,
                 HookRunner hookRunner
    ) {
        this.session = session;
        this.apiClient = apiClient;
        this.toolExecutor = toolExecutor;
        this.permissionPolicy = permissionPolicy;
        this.systemPrompt = systemPrompt;
        this.toolDefinitions = toolDefinitions;
        this.toolRegistry = null;
        this.maxIterations = maxIterations;
        this.hookRunner = hookRunner;
    }

    // -------------------------------------------------------------------------
    // 新构造器：使用 ToolRegistry，工具列表按权限模式动态计算
    // -------------------------------------------------------------------------

    /**
     * 推荐构造器：通过 {@link ToolRegistry} 管理工具，权限过滤在每次 turn 开始时动态执行。
     *
     * <p>{@code permissionPolicy} 应使用带注册表的
     * {@link com.zzypiper.permission.PermissionPolicy#PermissionPolicy(com.zzypiper.permission.ModeEnum, ToolRegistry)}
     * 构造器，以启用 spec-based 精确权限校验。
     */
    public Agent(Session session,
                 ApiClient apiClient,
                 ToolExecutor toolExecutor,
                 PermissionPolicy permissionPolicy,
                 List<String> systemPrompt,
                 ToolRegistry toolRegistry
    ) {
        this(session, apiClient, toolExecutor, permissionPolicy, systemPrompt,
                toolRegistry, Integer.MAX_VALUE, new HookRunner(Arrays.asList(), Arrays.asList()));
    }

    public Agent(Session session,
                 ApiClient apiClient,
                 ToolExecutor toolExecutor,
                 PermissionPolicy permissionPolicy,
                 List<String> systemPrompt,
                 ToolRegistry toolRegistry,
                 int maxIterations,
                 HookRunner hookRunner
    ) {
        this.session = session;
        this.apiClient = apiClient;
        this.toolExecutor = toolExecutor;
        this.permissionPolicy = permissionPolicy;
        this.systemPrompt = systemPrompt;
        this.toolDefinitions = null;
        this.toolRegistry = toolRegistry;
        this.maxIterations = maxIterations;
        this.hookRunner = hookRunner;
    }

    public TurnSummary runTurn(String userInput) {
        session.addMessage(Message.userText(userInput));

        List<Message> assistantMessages = new ArrayList<>();
        List<Message> toolResults = new ArrayList<>();
        int iterations = 0;

        // 动态计算本次 turn 可用的工具定义：
        // - 有 ToolRegistry 时按当前权限模式过滤；
        // - 无 ToolRegistry 时沿用构造器传入的静态列表（兼容旧用法）。
        List<ToolDefinition> effectiveDefinitions = toolRegistry != null
                ? toolRegistry.getDefinitions(permissionPolicy.getMode())
                : (toolDefinitions != null ? toolDefinitions : Collections.emptyList());

        while (true) {
            iterations++;

            if (iterations > maxIterations) {
                throw new RuntimeException("conversation loop exceeded the maximum number of iterations: " + maxIterations);
            }

            List<AssistantEvent> events = apiClient.stream(
                    ApiRequest.of(systemPrompt, session.getMessages(), effectiveDefinitions)
            );

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
            output = toolExecutor.execute(toolName, input);
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
