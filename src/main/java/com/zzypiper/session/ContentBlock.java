package com.zzypiper.session;

import lombok.Data;

@Data
public class ContentBlock {
    private final KindEnum kind;
    private final String text;
    private final String toolUseId;
    private final String toolName;
    private final String toolInput;
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

    public static ContentBlock toolResult(String toolUseId, String toolName, String toolOutput, boolean error) {
        return new ContentBlock(
                KindEnum.TOOL_USE, null,
                toolUseId, toolName, null,
                toolOutput, error
        );
    }
}
