package com.zzypiper.session;

import lombok.Data;

import java.util.Arrays;
import java.util.List;

@Data
public class Message {
    private final RoleEnum role;
    private final List<ContentBlock> blocks;

    public static Message userText(String text) {
        return new Message(RoleEnum.USER, Arrays.asList(ContentBlock.text(text)));
    }

    public static Message assistant(List<ContentBlock> blocks) {
        return new Message(RoleEnum.ASSISTANT, blocks);
    }

    public static Message toolResult(String toolUseId, String toolName, String output, boolean error) {
        return new Message(RoleEnum.TOOL, Arrays.asList(ContentBlock.toolResult(toolUseId, toolName, output, error)));
    }
}
