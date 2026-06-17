package com.zzypiper.api;

import java.util.List;

public interface ApiClient {

    List<AssistantEvent> stream(ApiRequest request);

    ModelConfig getModelConfig();
}
