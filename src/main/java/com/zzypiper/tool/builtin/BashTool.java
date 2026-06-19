package com.zzypiper.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzypiper.permission.ModeEnum;
import com.zzypiper.tool.ToolException;
import com.zzypiper.tool.ToolSpec;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public class BashTool implements BuiltinTool {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int DEFAULT_TIMEOUT_MS = 30_000;

    @Override
    public ToolSpec spec() {
        return new ToolSpec(
                "bash",
                "Execute a shell command in a new subprocess. stderr is merged into stdout.",
                "{\"type\":\"object\",\"properties\":{" +
                "\"command\":{\"type\":\"string\",\"description\":\"The shell command to run\"}," +
                "\"timeout_ms\":{\"type\":\"integer\",\"description\":\"Timeout in milliseconds (default 30000)\"}}" +
                ",\"required\":[\"command\"]}",
                ModeEnum.DANGER_FULL_ACCESS,
                false
        );
    }

    @Override
    public String execute(String input) throws ToolException {
        try {
            JsonNode node = MAPPER.readTree(input);
            String command = node.get("command").asText();
            int timeoutMs = node.has("timeout_ms") ? node.get("timeout_ms").asInt() : DEFAULT_TIMEOUT_MS;

            ProcessBuilder pb = new ProcessBuilder("bash", "-c", command);
            pb.redirectErrorStream(true);   // stderr 合并到 stdout
            Process process = pb.start();

            // 独立线程读输出：避免主线程 readAllBytes 阻塞，使 waitFor 的超时能正常触发
            CompletableFuture<String> outputFuture = CompletableFuture.supplyAsync(() -> {
                try {
                    return new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                } catch (IOException e) {
                    return "";
                }
            });

            boolean finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new ToolException("command timed out after " + timeoutMs + "ms");
            }

            String output = outputFuture.get();
            int exitCode = process.exitValue();
            if (exitCode != 0) {
                return output + "\n[exit code: " + exitCode + "]";
            }
            return output;

        } catch (ToolException e) {
            throw e;
        } catch (Exception e) {
            throw new ToolException("bash failed: " + e.getMessage());
        }
    }
}
