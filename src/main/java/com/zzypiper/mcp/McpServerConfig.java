package com.zzypiper.mcp;

import java.util.List;
import java.util.Map;

/**
 * 单个 MCP server 的配置。
 *
 * <p>对应 .agent/mcp.json 中 servers 数组的一个元素：
 * <pre>
 * {
 *   "name": "filesystem",
 *   "type": "stdio",
 *   "command": "node",
 *   "args": ["/path/to/server.js"],
 *   "env": { "KEY": "value" }
 * }
 * </pre>
 */
public record McpServerConfig(
        String name,
        String command,
        List<String> args,
        Map<String, String> env,
        TransportType type
) {}
