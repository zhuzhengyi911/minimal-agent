package com.zzypiper;

import com.zzypiper.module.*;
import lombok.Getter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

@Getter
public class Agent {
    private final Session session;
    private final ApiClient apiClient;
    private final ToolExecutor toolExecutor;
    private final PermissionPolicy permissionPolicy;
    private final List<String> systemPrompt;
    private final int maxIterations;
    private final HookRunner hookRunner;

    public Agent(Session session,
                 ApiClient apiClient,
                 ToolExecutor toolExecutor,
                 PermissionPolicy permissionPolicy,
                 List<String> systemPrompt
    ) {
        this(session,
                apiClient,
                toolExecutor,
                permissionPolicy,
                systemPrompt,
                Integer.MAX_VALUE,
                new HookRunner(Arrays.asList(), Arrays.asList())
        );
    }

    public Agent(Session session,
                 ApiClient apiClient,
                 ToolExecutor toolExecutor,
                 PermissionPolicy permissionPolicy,
                 List<String> systemPrompt,
                 int maxIterations,
                 HookRunner hookRunner
    ) {
        this.session = session;
        this.apiClient = apiClient;
        this.toolExecutor = toolExecutor;
        this.permissionPolicy = permissionPolicy;
        this.systemPrompt = systemPrompt;
        this.maxIterations = maxIterations;
        this.hookRunner = hookRunner;
    }

    public TurnSummary runTurn(String userInput) {
        // 1.添加用户消息到会话
        session.addMessage(Message.userText(userInput));

        List<Message> assistantMessages = new ArrayList<>();
        List<Message> toolResults = new ArrayList<>();
        int iterations = 0;

        while (true) {
            iterations++;

            // 2.检查迭代上限
            if (iterations > maxIterations) {
                throw new RuntimeException("conversation loop exceeded the maximum number of iterations: " + maxIterations);
            }

            // 3.调用LLM API
            List<AssistantEvent> events = apiClient.stream(
                    systemPrompt, session.getMessages()
            );

            // 4.解析助手消息
            Message assistantMessage = buildAssistantMessage(events);

            List<ContentBlock> pendingToolUse = assistantMessage.getBlocks().stream()
                    .filter(b -> b.getKind() == KindEnum.TOOL_USE)
                    .collect(Collectors.toList());

            session.addMessage(assistantMessage);
            assistantMessages.add(assistantMessage);

            // 5.没有工具调用，循环结束
            if (pendingToolUse.isEmpty()) {
                break;
            }

            // 6.处理每个工具调用
            for (ContentBlock toolUseBlock : pendingToolUse) {
                Message resultMessage = processToolUse(toolUseBlock);
                session.addMessage(resultMessage);
                toolResults.add(resultMessage);
            }

            // 继续循环，把工具结果发给LLM，让它继续推理
        }
        return new TurnSummary(assistantMessages, toolResults, iterations);
    }

    /**
     * 从事件流里构建助手信息
     * @param events
     * @return
     */
    private Message buildAssistantMessage(List<AssistantEvent> events) {
        StringBuilder currentText = new StringBuilder();
        List<ContentBlock> blocks = new ArrayList<>();
        boolean finished = false;

        for(AssistantEvent event : events){
            if (event instanceof AssistantEvent.TextDelta delta){
                currentText.append(delta.delta());
            } else if (event instanceof AssistantEvent.ToolUse toolUse){
                flushTextBlock(currentText, blocks);
                blocks.add(ContentBlock.toolUse(toolUse.id(),toolUse.name(),toolUse.input()));
            } else if (event instanceof AssistantEvent.MessageStop){
                finished = true;
            }
        }

        flushTextBlock(currentText,blocks);

        if (!finished){
            throw new RuntimeException("assistant stream ended without a MessageStop event");
        }
        if (blocks.isEmpty()){
            throw new RuntimeException("assistant stream produced no content");
        }

        return Message.assistant(blocks);
    }

    // 把积累的文本内容flush成一个textBlock
    private void flushTextBlock(StringBuilder text, List<ContentBlock> blocks) {
        if (!text.isEmpty()){
            blocks.add(ContentBlock.text(text.toString()));
            text.setLength(0);
        }
    }

    private Message processToolUse(ContentBlock toolUseBlock) {
        String toolUseId = toolUseBlock.getToolUseId();
        String toolName = toolUseBlock.getToolName();
        String input = toolUseBlock.getToolInput();

        // 权限校验
        Outcome permissionOutcome = permissionPolicy.authorize(toolName, input);
        if (permissionOutcome instanceof Outcome.Deny deny) {
            return Message.toolResult(toolUseId, toolName, deny.reason(), false);
        }

        // preToolUse Hook
        HookResult preHookResult = hookRunner.runPreToolUse(toolName, input);
        if (preHookResult.isDenied()) {
            String denyMsg = preHookResult.getMessages().isEmpty()
                    ? "PreToolUse hook denied tool '" + toolName + "'"
                    : String.join("\n", preHookResult.getMessages());
            return Message.toolResult(toolUseId, toolName, denyMsg, true);
        }

        // 执行工具
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

        // postToolUse Hook
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
