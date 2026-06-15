package com.zzypiper;

sealed interface AssistantEvent permits
        AssistantEvent.TextDelta,
        AssistantEvent.ToolUse,
        AssistantEvent.MessageStop {

    record TextDelta(String delta) implements AssistantEvent {
    }

    record ToolUse(String id, String name, String input) implements AssistantEvent {
    }

    record MessageStop() implements AssistantEvent {
    }
}
