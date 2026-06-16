package com.zzypiper.mcp.transport;

import com.fasterxml.jackson.databind.JsonNode;
import com.zzypiper.mcp.McpException;

/**
 * MCP 传输层抽象。
 *
 * <ul>
 *   <li>{@link #start()} — 建立连接（启动子进程或建立 HTTP 连接）</li>
 *   <li>{@link #request(String, JsonNode)} — 发 JSON-RPC 请求，阻塞等待响应，返回 result 节点</li>
 *   <li>{@link #notify(String, JsonNode)} — 发 JSON-RPC 通知，不等待响应</li>
 *   <li>{@link #stop()} — 关闭连接</li>
 * </ul>
 */
public interface McpTransport {

    void start() throws McpException;

    /** 发送请求并阻塞等待响应，返回 result 节点；若 server 返回 error 则抛 {@link McpException}。 */
    JsonNode request(String method, JsonNode params) throws McpException;

    /** 发送通知，不等待响应。 */
    void notify(String method, JsonNode params) throws McpException;

    void stop();
}
