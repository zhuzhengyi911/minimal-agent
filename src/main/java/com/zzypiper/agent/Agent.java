package com.zzypiper.agent;

import com.zzypiper.api.ApiRequest;
import com.zzypiper.api.AssistantEvent;
import com.zzypiper.api.TokenUsage;
import com.zzypiper.api.TurnUsage;
import com.zzypiper.api.UsageTracker;
import com.zzypiper.compaction.Compactor;
import com.zzypiper.hook.HookResult;
import com.zzypiper.agentmd.AgentMdLoader;
import com.zzypiper.memory.MemoryLoader;
import com.zzypiper.skill.SkillLoader;
import com.zzypiper.skill.SkillSummary;
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

        // 压缩检查用初始 prompt（压缩前 skill 状态）
        List<String> initialPrompt = buildEffectivePrompt();

        // 用当前 session 的估算值实时判断是否需要压缩，比依赖上一轮 actual 更准确
        int estimatedTokens = UsageTracker.estimateInputTokens(initialPrompt, session.getMessages(), tools);
        if (UsageTracker.exceedsThreshold(estimatedTokens, apiClient.getModelConfig())) {
            compactor.compact(session, initialPrompt)
                     .ifPresent(cu -> {
                         usageTracker.recordCompaction(cu);
                         // 压缩后清空所有已加载 skill，回到只有 summaries 的状态
                         if (options.skillLoader() != null) {
                             options.skillLoader().unloadAll();
                         }
                     });
            // 压缩后消息减少，重新估算以准确记录本轮实际起点
            estimatedTokens = UsageTracker.estimateInputTokens(buildEffectivePrompt(), session.getMessages(), tools);
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

            // 每次迭代重新构建 prompt：load_skill 执行后 loadedSkills 已更新，
            // 新内容应在下一次 API 调用的 system prompt 里立即生效
            List<String> effectivePrompt = buildEffectivePrompt();
            List<AssistantEvent> events = apiClient.stream(ApiRequest.of(effectivePrompt, session.getMessages(), tools));
            turnUsage = turnUsage.plus(extractUsage(events));

            Message assistantMessage = buildAssistantMessage(events);
            session.addMessage(assistantMessage);
            assistantMessages.add(assistantMessage);

            List<ContentBlock> toolUses = pendingToolUses(assistantMessage);
            if (toolUses.isEmpty()) break;

            for (List<ContentBlock> batch : partitionIntoBatches(toolUses)) {
                List<Message> batchResults = batch.size() > 1
                        ? runParallel(batch)
                        : List.of(processToolUse(batch.get(0)));
                batchResults.forEach(r -> {
                    session.addMessage(r);
                    toolResults.add(r);
                });
            }
        }

        Instant finishedAt = Instant.now();
        usageTracker.record(new TurnUsage(
                usageTracker.turns().size(), iterations, estimatedTokens, turnUsage,
                startedAt, finishedAt));

        return new TurnSummary(assistantMessages, toolResults, iterations);
    }

    /**
     * 每次迭代动态构建实际使用的 system prompt。
     * = AGENT.md 内容（全局 + 项目，最前面）
     *   + baseSystemPrompt
     *   + skill 使用指令 + skill summaries + 已加载 skill 完整内容（如有 SkillLoader）
     *   + 记忆使用指令 + 最新记忆索引（如有 MemoryLoader）
     */
    private List<String> buildEffectivePrompt() {
        List<String> effective = new ArrayList<>();

        // 默认角色定义在最前面
        effective.addAll(systemPrompt);

        // AGENT.md：项目上下文，在 systemPrompt 之后，可补充或覆盖默认行为
        if (options.agentMdLoader() != null) {
            String agentMd = options.agentMdLoader().load();
            if (!agentMd.isBlank()) {
                effective.add(agentMd);
            }
        }

        // Skill 系统
        if (options.skillLoader() != null) {
            SkillLoader skillLoader = options.skillLoader();
            effective.add(SkillLoader.USAGE_INSTRUCTIONS);

            // skill summaries（始终注入）
            List<SkillSummary> summaries = skillLoader.getSummaries();
            if (!summaries.isEmpty()) {
                StringBuilder sb = new StringBuilder("## Available Skills\n\n");
                for (SkillSummary s : summaries) {
                    sb.append("- **").append(s.name()).append("**: ").append(s.description()).append("\n");
                }
                effective.add(sb.toString());
            }

            // 已加载 skill 完整内容
            skillLoader.getLoadedContents().forEach((name, content) ->
                    effective.add("## Skill: " + name + "\n\n" + content));
        }

        // 记忆系统
        if (options.memoryLoader() != null) {
            effective.add(MemoryLoader.USAGE_INSTRUCTIONS);
            String memoryIndex = options.memoryLoader().load();
            if (!memoryIndex.isBlank()) {
                effective.add("## Current Memory Index\n\n" + memoryIndex);
            }
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

    /**
     * 将 tool_use 列表按 isConcurrencySafe 分批：连续的只读工具合并为一个并行批次，
     * 写操作（isConcurrencySafe=false）独占一个串行批次。
     */
    private List<List<ContentBlock>> partitionIntoBatches(List<ContentBlock> toolUses) {
        List<List<ContentBlock>> batches = new ArrayList<>();
        for (ContentBlock toolUse : toolUses) {
            boolean safe = isConcurrencySafe(toolUse.getToolName());
            if (safe && !batches.isEmpty() && isConcurrentBatch(batches.get(batches.size() - 1))) {
                batches.get(batches.size() - 1).add(toolUse);
            } else {
                List<ContentBlock> batch = new ArrayList<>();
                batch.add(toolUse);
                batches.add(batch);
            }
        }
        return batches;
    }

    /** 判断一个批次是否为并发批次（批次内第一个工具是 concurrencySafe 的则整批都是）。 */
    private boolean isConcurrentBatch(List<ContentBlock> batch) {
        return !batch.isEmpty() && isConcurrencySafe(batch.get(0).getToolName());
    }

    /** 从 ToolRegistry 查找该工具的 isConcurrencySafe 标记，未知工具返回 false。 */
    private boolean isConcurrencySafe(String toolName) {
        com.zzypiper.tool.ToolSpec spec = toolRegistry.getSpec(toolName);
        return spec != null && spec.isConcurrencySafe();
    }

    /**
     * 并行执行一个工具批次，结果顺序与输入一致。
     * 每个工具在独立虚拟线程中运行；异常被捕获并包装为 error tool result，不中断其他工具。
     */
    private List<Message> runParallel(List<ContentBlock> batch) {
        Message[] results = new Message[batch.size()];
        List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < batch.size(); i++) {
            final int idx = i;
            final ContentBlock toolUse = batch.get(i);
            futures.add(options.executor().submit(() -> {
                try {
                    results[idx] = processToolUse(toolUse);
                } catch (Exception e) {
                    results[idx] = Message.toolResult(
                            toolUse.getToolUseId(), toolUse.getToolName(),
                            "Unexpected error in parallel execution: " + e.getMessage(), true);
                }
            }));
        }
        for (java.util.concurrent.Future<?> f : futures) {
            try {
                f.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Interrupted while waiting for parallel tool batch", e);
            } catch (java.util.concurrent.ExecutionException e) {
                throw new RuntimeException("Unexpected error in parallel tool batch", e.getCause());
            }
        }
        return Arrays.asList(results);
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
