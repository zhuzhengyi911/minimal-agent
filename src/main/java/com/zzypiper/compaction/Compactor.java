package com.zzypiper.compaction;

import com.zzypiper.api.ApiClient;
import com.zzypiper.api.ApiRequest;
import com.zzypiper.api.AssistantEvent;
import com.zzypiper.api.TokenUsage;
import com.zzypiper.session.ContentBlock;
import com.zzypiper.session.Message;
import com.zzypiper.session.RoleEnum;
import com.zzypiper.session.Session;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * 上下文压缩器——采用 Rolling Summary 策略，在对话消息量超出阈值时
 * 将旧消息（含已有摘要）重新提炼为单条结构化摘要，替换进 {@link Session}。
 *
 * <h2>Rolling Summary 策略</h2>
 * <ul>
 *   <li>任意时刻 Session 中最多只有一条 {@link com.zzypiper.session.MessageType#COMPACTION_SUMMARY} 消息</li>
 *   <li>每次压缩，{@code toSummarize} 包含旧摘要（若有）+ 旧摘要之后到分割点之间的所有消息，
 *       生成新的单一摘要——旧摘要天然被合并进来，无需特殊处理</li>
 *   <li>分割点对齐到 USER 消息边界，不切断 turn 中间</li>
 *   <li>若摘要 API 调用失败，退化为截断：用占位文本替代摘要，保留近期消息</li>
 * </ul>
 *
 * <h2>触发时机</h2>
 * 由 {@code Agent.runTurn} 在每个 turn 首次 API 调用前调用。
 */
public class Compactor {

    private static final Logger LOG = Logger.getLogger(Compactor.class.getName());

    /** 压缩后保留的近期消息数（对齐到 USER 边界后实际值可能略大）。 */
    public static final int KEEP_RECENT_MESSAGES = 20;

    private static final List<String> SUMMARY_SYSTEM_PROMPT = List.of("""
            You are summarizing a conversation between a user and an AI assistant.
            Produce a structured summary that preserves all details needed to continue the work:
            1. User's original goal or task
            2. Key decisions and conclusions reached
            3. Files created or modified (with exact paths and what changed)
            4. Work completed so far
            5. Pending tasks or open questions
            Be concise but technically precise. Preserve file paths, command outputs, and error messages.""");

    private final ApiClient apiClient;

    public Compactor(ApiClient apiClient) {
        this.apiClient = apiClient;
    }

    /**
     * 压缩 Session 中的消息。
     *
     * <p>若当前第一条消息已是摘要（Rolling Summary），它会自然落入 {@code toSummarize} 范围，
     * 与后续旧消息一起被重新提炼为新摘要，不会产生多条摘要堆叠。
     *
     * @return 执行了压缩则返回 {@link CompactionUsage}；消息数未达阈值则返回空
     */
    public Optional<CompactionUsage> compact(Session session, List<String> systemPrompt) {
        List<Message> messages = session.getMessages();

        int splitIndex = findSplitIndex(messages);
        if (splitIndex <= 0) {
            return Optional.empty();
        }

        List<Message> toSummarize = new ArrayList<>(messages.subList(0, splitIndex));
        List<Message> toKeep     = new ArrayList<>(messages.subList(splitIndex, messages.size()));

        LOG.info(String.format("Compacting (pass #%d): summarizing %d messages, keeping %d",
                session.getCompactionCount() + 1, toSummarize.size(), toKeep.size()));

        Instant startedAt = Instant.now();
        String summaryText;
        TokenUsage summaryUsage = TokenUsage.ZERO;
        boolean fellBack = false;

        try {
            SummaryResult result = generateSummary(toSummarize, systemPrompt);
            summaryText  = result.text();
            summaryUsage = result.usage();
        } catch (Exception e) {
            LOG.warning("Summary generation failed: " + e.getMessage()
                    + ". Falling back to truncation.");
            summaryText = "[Previous conversation truncated due to context length limit.]";
            fellBack = true;
        }
        Instant finishedAt = Instant.now();

        List<Message> compacted = new ArrayList<>();
        compacted.add(Message.compactionSummary(summaryText));
        compacted.addAll(toKeep);

        session.replaceMessages(compacted);

        LOG.info(String.format("Compaction done: %d → %d messages (total compactions: %d)",
                messages.size(), compacted.size(), session.getCompactionCount()));

        return Optional.of(new CompactionUsage(
                session.getCompactionCount(),
                toSummarize.size(),
                toKeep.size(),
                summaryUsage,
                startedAt,
                finishedAt,
                fellBack));
    }

    // -------------------------------------------------------------------------
    // 分割点计算
    // -------------------------------------------------------------------------

    /**
     * 找到消息列表的分割点：从尾部保留 {@value #KEEP_RECENT_MESSAGES} 条，
     * 并向后对齐到第一个 USER 消息，确保不切断 turn 中间。
     *
     * @return 分割下标（[0, splitIndex) 被摘要，[splitIndex, end) 被保留）；
     *         返回 {@code 0} 表示不需要压缩
     */
    private int findSplitIndex(List<Message> messages) {
        if (messages.size() <= KEEP_RECENT_MESSAGES) {
            return 0;
        }

        int candidate = messages.size() - KEEP_RECENT_MESSAGES;

        // 向后对齐到第一个 USER 消息（避免切在 tool_use / tool_result 中间）
        while (candidate < messages.size()
                && messages.get(candidate).getRole() != RoleEnum.USER) {
            candidate++;
        }

        // 若末尾全是 assistant/tool 消息，找不到 USER 边界，则不压缩
        return candidate < messages.size() ? candidate : 0;
    }

    // -------------------------------------------------------------------------
    // 摘要生成
    // -------------------------------------------------------------------------

    private SummaryResult generateSummary(List<Message> messages, List<String> originalSystemPrompt) {
        String transcript = formatTranscript(messages, originalSystemPrompt);

        List<Message> summaryRequest = List.of(
                Message.userText(transcript + "\n\nPlease provide a structured summary."));

        List<AssistantEvent> events = apiClient.stream(
                ApiRequest.of(SUMMARY_SYSTEM_PROMPT, summaryRequest));

        String text = events.stream()
                .filter(e -> e instanceof AssistantEvent.TextDelta)
                .map(e -> ((AssistantEvent.TextDelta) e).delta())
                .collect(Collectors.joining());

        TokenUsage usage = events.stream()
                .filter(e -> e instanceof AssistantEvent.Usage)
                .map(e -> ((AssistantEvent.Usage) e).usage())
                .findFirst()
                .orElse(TokenUsage.ZERO);

        if (text.isBlank()) {
            throw new RuntimeException("Summary API returned empty response");
        }
        return new SummaryResult(text, usage);
    }

    /** 摘要生成结果：文本 + 本次 API 调用的 token 用量。 */
    private record SummaryResult(String text, TokenUsage usage) {}

    /**
     * 将消息列表格式化为可供摘要 LLM 阅读的文本。
     * 对 COMPACTION_SUMMARY 类型的消息直接输出其文本（即旧摘要内容），
     * 让 LLM 在生成新摘要时能看到并整合旧摘要。
     */
    private String formatTranscript(List<Message> messages, List<String> systemPrompt) {
        StringBuilder sb = new StringBuilder();

        if (!systemPrompt.isEmpty()) {
            sb.append("=== System Prompt ===\n");
            systemPrompt.forEach(p -> sb.append(p).append("\n"));
            sb.append("\n");
        }

        sb.append("=== Conversation ===\n");
        for (Message m : messages) {
            if (m.isCompactionSummary()) {
                // 旧摘要：直接输出，LLM 会将其整合进新摘要
                sb.append(m.getBlocks().get(0).getText()).append("\n\n");
                continue;
            }

            sb.append("[").append(m.getRole()).append("]\n");

            if (m.getRole() == RoleEnum.TOOL) {
                for (ContentBlock block : m.getBlocks()) {
                    sb.append("<tool_result name=\"").append(block.getToolName()).append("\"");
                    if (block.isError()) sb.append(" error=\"true\"");
                    sb.append(">\n")
                      .append(block.getToolOutput() != null ? block.getToolOutput() : "")
                      .append("\n</tool_result>\n");
                }
            } else {
                for (ContentBlock block : m.getBlocks()) {
                    switch (block.getKind()) {
                        case TEXT ->
                                sb.append(block.getText() != null ? block.getText() : "").append("\n");
                        case TOOL_USE ->
                                sb.append("<tool_use name=\"").append(block.getToolName()).append("\">\n")
                                  .append(block.getToolInput() != null ? block.getToolInput() : "")
                                  .append("\n</tool_use>\n");
                        default -> { /* TOOL_RESULT 在 TOOL role 分支处理 */ }
                    }
                }
            }
            sb.append("\n");
        }
        return sb.toString();
    }
}
