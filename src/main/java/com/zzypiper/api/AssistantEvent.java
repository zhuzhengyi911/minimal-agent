package com.zzypiper.api;

public sealed interface AssistantEvent permits
        AssistantEvent.TextDelta,
        AssistantEvent.ToolUse,
        AssistantEvent.Usage,
        AssistantEvent.MessageStop {

    record TextDelta(String delta) implements AssistantEvent {}

    record ToolUse(String id, String name, String input) implements AssistantEvent {}

    /** API 响应中的 token 用量，始终作为最后一个事件（MessageStop 之前）发出。 */
    record Usage(TokenUsage usage) implements AssistantEvent {}

    record MessageStop() implements AssistantEvent {}
}
