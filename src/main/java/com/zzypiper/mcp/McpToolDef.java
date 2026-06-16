package com.zzypiper.mcp;

/**
 * MCP server 通过 tools/list 返回的单个工具描述。
 */
public record McpToolDef(
        String name,
        String description,
        String inputSchemaJson
) {}
