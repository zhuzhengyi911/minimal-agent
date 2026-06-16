package com.zzypiper.api;

import com.zzypiper.session.Message;
import com.zzypiper.tool.ToolDefinition;
import lombok.Data;

import java.util.Collections;
import java.util.List;

@Data
public class ApiRequest {
    private final List<String> systemPrompt;
    private final List<Message> messages;
    private final List<ToolDefinition> tools;

    public static ApiRequest of(List<String> systemPrompt, List<Message> messages, List<ToolDefinition> tools) {
        return new ApiRequest(systemPrompt, messages, tools);
    }

    public static ApiRequest of(List<String> systemPrompt, List<Message> messages) {
        return new ApiRequest(systemPrompt, messages, Collections.emptyList());
    }
}
