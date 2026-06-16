package com.zzypiper.mcp.transport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zzypiper.mcp.McpException;
import com.zzypiper.mcp.McpServerConfig;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 基于子进程 stdio 的 MCP 传输层实现。
 *
 * <p>通信格式：换行符分隔的 JSON-RPC 2.0 消息，每条消息占一行。
 * stderr 由后台线程持续消费，防止子进程缓冲区满导致死锁。
 *
 * <p>{@link #request} 和 {@link #notify} 均加锁，保证同一时刻只有一个请求在途。
 */
public class StdioTransport implements McpTransport {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final McpServerConfig config;
    private Process process;
    private BufferedWriter stdin;
    private BufferedReader stdout;
    private Thread stderrDrainer;
    private final AtomicInteger nextId = new AtomicInteger(1);

    public StdioTransport(McpServerConfig config) {
        this.config = config;
    }

    @Override
    public void start() throws McpException {
        try {
            List<String> command = new ArrayList<>();
            command.add(config.command());
            command.addAll(config.args());

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.environment().putAll(config.env());

            process = pb.start();

            stdin  = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
            stdout = new BufferedReader(new InputStreamReader(process.getInputStream(),  StandardCharsets.UTF_8));

            // 持续消费 stderr，防止子进程 stderr 缓冲区满阻塞
            stderrDrainer = new Thread(() -> {
                try (BufferedReader err = new BufferedReader(
                        new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                    while (err.readLine() != null) { /* discard */ }
                } catch (Exception ignored) {}
            }, "mcp-stderr-" + config.name());
            stderrDrainer.setDaemon(true);
            stderrDrainer.start();

        } catch (Exception e) {
            throw new McpException("failed to start MCP server '" + config.name() + "': " + e.getMessage(), e);
        }
    }

    @Override
    public synchronized JsonNode request(String method, JsonNode params) throws McpException {
        int id = nextId.getAndIncrement();
        ObjectNode req = MAPPER.createObjectNode()
                .put("jsonrpc", "2.0")
                .put("id", id)
                .put("method", method);
        req.set("params", params != null ? params : MAPPER.createObjectNode());

        try {
            stdin.write(req.toString());
            stdin.newLine();
            stdin.flush();

            // 逐行读取，跳过通知（无 id 字段），直到找到匹配的响应
            String line;
            while ((line = stdout.readLine()) != null) {
                JsonNode msg = MAPPER.readTree(line);
                if (!msg.has("id") || msg.get("id").asInt() != id) {
                    continue; // 跳过通知或其他 server 消息
                }
                if (msg.has("error")) {
                    throw new McpException("MCP error from '" + config.name() + "': "
                            + msg.get("error").path("message").asText());
                }
                return msg.get("result");
            }
            throw new McpException("MCP server '" + config.name() + "' closed connection unexpectedly");

        } catch (McpException e) {
            throw e;
        } catch (Exception e) {
            throw new McpException("transport error: " + e.getMessage(), e);
        }
    }

    @Override
    public synchronized void notify(String method, JsonNode params) throws McpException {
        ObjectNode notification = MAPPER.createObjectNode()
                .put("jsonrpc", "2.0")
                .put("method", method);
        notification.set("params", params != null ? params : MAPPER.createObjectNode());

        try {
            stdin.write(notification.toString());
            stdin.newLine();
            stdin.flush();
        } catch (Exception e) {
            throw new McpException("failed to send notification: " + e.getMessage(), e);
        }
    }

    @Override
    public void stop() {
        try { stdin.close();  } catch (Exception ignored) {}
        try { stdout.close(); } catch (Exception ignored) {}
        if (process != null) process.destroyForcibly();
    }
}
