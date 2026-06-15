package com.zzypiper.module;

import lombok.Data;

@Data
public class ContentBlock {
    // 类型
    private final KindEnum kind;
    // TEXT块
    private final String text;
    // TOOL_USE块
    private final String toolUseId;
    private final String toolName;
    private final String toolInput;
    // TOOL_RESULT块
    private final String toolOutput;
    private final boolean error;

    public static ContentBlock text(String text) {
        return new ContentBlock(
                KindEnum.TEXT, text,
                null, null, null,
                null, false
        );
    }

    public static ContentBlock toolUse(String toolUseId, String toolName, String toolInput) {
        return new ContentBlock(
                KindEnum.TOOL_USE, null,
                toolUseId, toolName, toolInput,
                null, false
        );
    }

    public static ContentBlock toolResult(String toolUseId, String toolName,String toolOutput, boolean error) {
        return new ContentBlock(
                KindEnum.TOOL_USE, null,
                toolUseId, toolName, null,
                toolOutput, error
        );
    }
}