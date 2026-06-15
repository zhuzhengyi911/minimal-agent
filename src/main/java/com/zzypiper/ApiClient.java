package com.zzypiper;

import com.zzypiper.module.Message;

import java.util.List;

public interface ApiClient {

    List<AssistantEvent> stream(List<String> systemPrompt, List<Message> messages);
}
