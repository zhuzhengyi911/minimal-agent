package com.zzypiper.api;

import com.zzypiper.api.ModelConfig;
import com.zzypiper.api.TokenUsage;
import com.zzypiper.api.TurnUsage;
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

    // -------------------------------------------------------------------------
    // 写入
    // -------------------------------------------------------------------------

    public void record(TurnUsage turn) {
        turns.add(turn);
        cumulative = cumulative.plus(turn.actual());
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

    // -------------------------------------------------------------------------
    // 压缩判断
    // -------------------------------------------------------------------------

    /**
     * 判断是否需要在下次 API 调用前压缩上下文。
     *
     * <p>可用输入空间 = contextWindow - maxOutputTokens - safetyBuffer<br>
     * 参考值为上一轮实际 {@code inputTokens}（来自 API 响应，最准确）。
     */
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
