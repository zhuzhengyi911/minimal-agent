package com.zzypiper.session;

import lombok.Data;

import java.util.Arrays;
import java.util.List;

@Data
public class Message {
    private final RoleEnum   role;
    private final List<ContentBlock> blocks;
    private final MessageType type;

    public static Message userText(String text) {
        return new Message(RoleEnum.USER,
                Arrays.asList(ContentBlock.text(text)),
                MessageType.NORMAL);
    }

    public static Message assistant(List<ContentBlock> blocks) {
        return new Message(RoleEnum.ASSISTANT, blocks, MessageType.NORMAL);
    }

    public static Message toolResult(String toolUseId, String toolName, String output, boolean error) {
        return new Message(RoleEnum.TOOL,
                Arrays.asList(ContentBlock.toolResult(toolUseId, toolName, output, error)),
                MessageType.NORMAL);
    }

    /**
     * 创建上下文压缩摘要消息。
     * API 序列化时与普通 user 消息相同（role=user），系统内部通过 {@link MessageType#COMPACTION_SUMMARY} 区分。
     */
    public static Message compactionSummary(String summaryText) {
        return new Message(RoleEnum.USER,
                Arrays.asList(ContentBlock.text(
                        "<previous_conversation_summary>\n" + summaryText
                                + "\n</previous_conversation_summary>")),
                MessageType.COMPACTION_SUMMARY);
    }

    public boolean isCompactionSummary() {
        return type == MessageType.COMPACTION_SUMMARY;
    }
}
