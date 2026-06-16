package com.zzypiper.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zzypiper.mcp.transport.McpTransport;
import com.zzypiper.mcp.transport.StdioTransport;

import java.util.ArrayList;
import java.util.List;

/**
 * 单个 MCP server 的客户端——封装 JSON-RPC 协议细节。
 *
 * <p>生命周期：{@link #start()} → 多次 {@link #listTools()} / {@link #callTool(String, String)} → {@link #stop()}
 */
public class McpClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String PROTOCOL_VERSION = "2024-11-05";

    private final McpServerConfig config;
    private final McpTransport transport;

    public McpClient(McpServerConfig config) {
        this.config = config;
        this.transport = createTransport(config);
    }

    private static McpTransport createTransport(McpServerConfig config) {
        return switch (config.type()) {
            case STDIO -> new StdioTransport(config);
            case SSE   -> throw new McpException("SSE transport not yet implemented");
        };
    }

    /** 启动传输层并完成 MCP 握手（initialize + notifications/initialized）。 */
    public void start() throws McpException {
        transport.start();

        ObjectNode initParams = MAPPER.createObjectNode()
                .put("protocolVersion", PROTOCOL_VERSION);
        initParams.set("capabilities", MAPPER.createObjectNode());
        initParams.set("clientInfo", MAPPER.createObjectNode()
                .put("name", "minimal-agent")
                .put("version", "0.1.0"));

        transport.request("initialize", initParams);
        transport.notify("notifications/initialized", null);
    }

    /** 获取 server 暴露的工具列表。 */
    public List<McpToolDef> listTools() throws McpException {
        JsonNode result = transport.request("tools/list", MAPPER.createObjectNode());
        List<McpToolDef> tools = new ArrayList<>();
        for (JsonNode tool : result.path("tools")) {
            tools.add(new McpToolDef(
                    tool.get("name").asText(),
                    tool.path("description").asText(""),
                    tool.path("inputSchema").toString()
            ));
        }
        return tools;
    }

    /**
     * 调用工具，返回文本结果。
     *
     * @param toolName  工具原始名称（不含 server 前缀）
     * @param inputJson 工具参数的 JSON 字符串
     */
    public String callTool(String toolName, String inputJson) throws McpException {
        try {
            ObjectNode params = MAPPER.createObjectNode()
                    .put("name", toolName);
            params.set("arguments", MAPPER.readTree(inputJson));

            JsonNode result = transport.request("tools/call", params);

            // 拼接所有 text 类型的 content
            StringBuilder sb = new StringBuilder();
            for (JsonNode content : result.path("content")) {
                if ("text".equals(content.path("type").asText())) {
                    sb.append(content.path("text").asText());
                }
            }
            return sb.toString();

        } catch (McpException e) {
            throw e;
        } catch (Exception e) {
            throw new McpException("callTool failed: " + e.getMessage(), e);
        }
    }

    public void stop() {
        transport.stop();
    }

    public String name() {
        return config.name();
    }
}
