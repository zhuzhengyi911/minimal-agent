package com.zzypiper.api;

import com.zzypiper.api.ModelConfig;
import com.zzypiper.api.TokenUsage;
import com.zzypiper.api.TurnUsage;
import com.zzypiper.compaction.CompactionUsage;
import com.zzypiper.session.ContentBlock;
import com.zzypiper.session.Message;
import com.zzypiper.tool.ToolDefinition;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * 跨 turn 累计追踪 token 用量，并判断是否需要触发上下文压缩。
 *
 * <h2>使用方式</h2>
 * <ol>
 *   <li>调用前：{@link #estimateInputTokens} 估算本次请求的输入 token 数</li>
 *   <li>调用后：拿到 API 响应里的 {@link TokenUsage}，构造 {@link TurnUsage} 并 {@link #record}</li>
 *   <li>下一次调用前：{@link #needsCompaction} 判断是否需要先压缩</li>
 * </ol>
 */
public class UsageTracker {

    /** 压缩阈值之外额外保留的安全余量（token）。 */
    private static final int SAFETY_BUFFER = 1_000;

    private final List<TurnUsage> turns = new ArrayList<>();
    private TokenUsage cumulative = TokenUsage.ZERO;

    private final List<CompactionUsage> compactions = new ArrayList<>();
    private TokenUsage compactionOverhead = TokenUsage.ZERO;

    // -------------------------------------------------------------------------
    // 写入
    // -------------------------------------------------------------------------

    public void record(TurnUsage turn) {
        turns.add(turn);
        cumulative = cumulative.plus(turn.actual());
    }

    /** 清空所有统计数据（用于 /clear 命令）。 */
    public void reset() {
        turns.clear();
        compactions.clear();
        cumulative = TokenUsage.ZERO;
        compactionOverhead = TokenUsage.ZERO;
    }

    public void recordCompaction(CompactionUsage cu) {
        compactions.add(cu);
        compactionOverhead = compactionOverhead.plus(cu.actual());
    }

    // -------------------------------------------------------------------------
    // 读取
    // -------------------------------------------------------------------------

    public TurnUsage latestTurn() {
        return turns.isEmpty() ? null : turns.get(turns.size() - 1);
    }

    public TokenUsage cumulative() {
        return cumulative;
    }

    public List<TurnUsage> turns() {
        return Collections.unmodifiableList(turns);
    }

    public List<CompactionUsage> compactions() {
        return Collections.unmodifiableList(compactions);
    }

    public TokenUsage compactionOverhead() {
        return compactionOverhead;
    }

    // -------------------------------------------------------------------------
    // 压缩判断
    // -------------------------------------------------------------------------

    /**
     * 判断给定的估算 token 数是否超过可用输入空间阈值。
     *
     * <p>可用输入空间 = contextWindow - maxOutputTokens - safetyBuffer<br>
     * 供调用方在每次 API 调用前用当前 session 的估算值做实时判断。
     */
    public static boolean exceedsThreshold(int estimatedTokens, ModelConfig config) {
        int available = config.contextWindow() - config.maxOutputTokens() - SAFETY_BUFFER;
        return estimatedTokens > available;
    }

    /**
     * 基于上一轮实际 {@code inputTokens} 判断是否需要压缩（滞后一轮的快照）。
     *
     * @deprecated 优先使用 {@link #exceedsThreshold(int, ModelConfig)} 配合当前估算值做实时判断；
     *             此方法保留用于审计和偏差分析。
     */
    @Deprecated
    public boolean needsCompaction(ModelConfig config) {
        if (turns.isEmpty()) return false;
        int available = config.contextWindow() - config.maxOutputTokens() - SAFETY_BUFFER;
        return latestTurn().actual().inputTokens() > available;
    }

    // -------------------------------------------------------------------------
    // 调用前估算
    // -------------------------------------------------------------------------

    /**
     * 粗略估算本次 API 调用的 input token 数，精度约 ±15%。
     *
     * <p>包含三部分：系统提示词 + 消息历史 + 工具 schema。
     * 算法：总字符数 / 4（适用于大多数拉丁/中文混合场景）。
     */
    public static int estimateInputTokens(
            List<String> systemPrompts,
            List<Message> messages,
            Collection<ToolDefinition> tools) {

        int chars = 0;
        for (String prompt : systemPrompts) {
            chars += prompt.length();
        }
        for (Message message : messages) {
            chars += messageChars(message);
        }
        for (ToolDefinition tool : tools) {
            chars += tool.name().length()
                    + tool.description().length()
                    + tool.inputSchemaJson().length();
        }
        return chars / 4;
    }

    private static int messageChars(Message message) {
        int total = 0;
        for (ContentBlock block : message.getBlocks()) {
            if (block.getText()       != null) total += block.getText().length();
            if (block.getToolInput()  != null) total += block.getToolInput().length();
            if (block.getToolOutput() != null) total += block.getToolOutput().length();
            if (block.getToolName()   != null) total += block.getToolName().length();
        }
        return total;
    }
}
