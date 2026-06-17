package com.zzypiper.api.minimax;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zzypiper.api.ApiClient;
import com.zzypiper.api.ApiRequest;
import com.zzypiper.api.AssistantEvent;
import com.zzypiper.api.ModelConfig;
import com.zzypiper.api.TokenUsage;
import com.zzypiper.session.ContentBlock;
import com.zzypiper.session.Message;
import com.zzypiper.session.RoleEnum;
import com.zzypiper.tool.ToolDefinition;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;

public class MinimaxApiClient implements ApiClient {

    private static final String API_URL = "https://api.minimaxi.com/anthropic/v1/messages";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    // MiniMax-M3 定价参考（$/MTok），如官方更新请同步修改
    private static final ModelConfig MINIMAX_M3_CONFIG = new ModelConfig(
            "MiniMax-M3",
            200_000,   // context window
            4_096,     // max output tokens
            1.0,       // input  $/MTok（占位，请以官方为准）
            5.0,       // output $/MTok
            1.25,      // cache write $/MTok
            0.10       // cache read  $/MTok
    );

    private final String apiKey;
    private final String model;
    private final int maxTokens;
    private final HttpClient httpClient;

    public MinimaxApiClient(String apiKey, String model, int maxTokens) {
        this.apiKey = apiKey;
        this.model = model;
        this.maxTokens = maxTokens;
        this.httpClient = HttpClient.newHttpClient();
    }

    public MinimaxApiClient(String apiKey) {
        this(apiKey, "MiniMax-M3", 4096);
    }

    @Override
    public List<AssistantEvent> stream(ApiRequest request) {
        try {
            ObjectNode body = MAPPER.createObjectNode();
            body.put("model", model);
            body.put("max_tokens", maxTokens);

            if (request.getSystemPrompt() != null && !request.getSystemPrompt().isEmpty()) {
                body.put("system", String.join("\n", request.getSystemPrompt()));
            }

            if (request.getTools() != null && !request.getTools().isEmpty()) {
                ArrayNode toolsArray = MAPPER.createArrayNode();
                for (ToolDefinition tool : request.getTools()) {
                    ObjectNode toolNode = MAPPER.createObjectNode();
                    toolNode.put("name", tool.name());
                    toolNode.put("description", tool.description());
                    toolNode.set("input_schema", MAPPER.readTree(tool.inputSchemaJson()));
                    toolsArray.add(toolNode);
                }
                body.set("tools", toolsArray);
            }

            ArrayNode messagesArray = MAPPER.createArrayNode();
            for (Message message : request.getMessages()) {
                messagesArray.add(buildMessageNode(message));
            }
            body.set("messages", messagesArray);

            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(API_URL))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)))
                    .build();

            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                throw new RuntimeException("MiniMax API error " + response.statusCode() + ": " + response.body());
            }

            return parseResponse(response.body());
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to call MiniMax API", e);
        }
    }

    private ObjectNode buildMessageNode(Message message) throws Exception {
        ObjectNode msgNode = MAPPER.createObjectNode();
        ArrayNode contentArray = MAPPER.createArrayNode();

        if (message.getRole() == RoleEnum.TOOL) {
            msgNode.put("role", "user");
            for (ContentBlock block : message.getBlocks()) {
                ObjectNode contentNode = MAPPER.createObjectNode();
                contentNode.put("type", "tool_result");
                contentNode.put("tool_use_id", block.getToolUseId());
                contentNode.put("content", block.getToolOutput());
                if (block.isError()) {
                    contentNode.put("is_error", true);
                }
                contentArray.add(contentNode);
            }
        } else {
            msgNode.put("role", message.getRole() == RoleEnum.USER ? "user" : "assistant");
            for (ContentBlock block : message.getBlocks()) {
                ObjectNode contentNode = MAPPER.createObjectNode();
                switch (block.getKind()) {
                    case TEXT -> {
                        contentNode.put("type", "text");
                        contentNode.put("text", block.getText());
                    }
                    case TOOL_USE -> {
                        contentNode.put("type", "tool_use");
                        contentNode.put("id", block.getToolUseId());
                        contentNode.put("name", block.getToolName());
                        JsonNode inputNode = MAPPER.readTree(block.getToolInput());
                        contentNode.set("input", inputNode);
                    }
                    default -> {
                        continue;
                    }
                }
                contentArray.add(contentNode);
            }
        }

        msgNode.set("content", contentArray);
        return msgNode;
    }

    @Override
    public ModelConfig getModelConfig() {
        return MINIMAX_M3_CONFIG;
    }

    private List<AssistantEvent> parseResponse(String responseBody) throws Exception {
        List<AssistantEvent> events = new ArrayList<>();
        JsonNode root = MAPPER.readTree(responseBody);
        JsonNode contentArray = root.get("content");

        if (contentArray != null && contentArray.isArray()) {
            for (JsonNode block : contentArray) {
                String type = block.get("type").asText();
                switch (type) {
                    case "text" -> events.add(new AssistantEvent.TextDelta(block.get("text").asText()));
                    case "tool_use" -> {
                        String id = block.get("id").asText();
                        String name = block.get("name").asText();
                        String input = MAPPER.writeValueAsString(block.get("input"));
                        events.add(new AssistantEvent.ToolUse(id, name, input));
                    }
                }
            }
        }

        // 解析 token 用量，作为 Usage 事件附在末尾
        JsonNode usageNode = root.path("usage");
        TokenUsage usage = new TokenUsage(
                usageNode.path("input_tokens").asInt(),
                usageNode.path("output_tokens").asInt(),
                usageNode.path("cache_creation_input_tokens").asInt(),
                usageNode.path("cache_read_input_tokens").asInt()
        );
        events.add(new AssistantEvent.Usage(usage));
        events.add(new AssistantEvent.MessageStop());
        return events;
    }
}
