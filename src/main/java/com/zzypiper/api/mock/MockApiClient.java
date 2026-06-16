package com.zzypiper.api.mock;

import com.zzypiper.api.ApiClient;
import com.zzypiper.api.ApiRequest;
import com.zzypiper.api.AssistantEvent;
import lombok.Getter;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;

@Getter
public class MockApiClient implements ApiClient {

    private final Deque<List<AssistantEvent>> responses = new ArrayDeque<>();
    private int callCount = 0;

    public MockApiClient thenReturn(List<AssistantEvent> events) {
        responses.offer(events);
        return this;
    }

    public MockApiClient thenText(String text) {
        return thenReturn(Arrays.asList(
                new AssistantEvent.TextDelta(text),
                new AssistantEvent.MessageStop())
        );
    }

    public MockApiClient thenToolUse(String toolUseId, String toolName, String input) {
        return thenReturn(Arrays.asList(
                new AssistantEvent.ToolUse(toolUseId, toolName, input),
                new AssistantEvent.MessageStop())
        );
    }

    @Override
    public List<AssistantEvent> stream(ApiRequest request) {
        callCount++;
        List<AssistantEvent> response = responses.poll();
        if (response == null) {
            throw new RuntimeException("MockApiClient: unexpected call #" + callCount + ", no more response");
        }
        return response;
    }
}
