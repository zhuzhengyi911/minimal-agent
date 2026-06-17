package com.zzypiper.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzypiper.memory.MemoryLoader;
import com.zzypiper.permission.ModeEnum;
import com.zzypiper.tool.ToolException;
import com.zzypiper.tool.ToolSpec;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 读记忆工具——读取 {@code .agent/memory/} 目录下的具体记忆文件。
 *
 * <p>MEMORY.md 索引中每条记忆只有一行摘要，AI 需要完整内容时调用此工具。
 * 包含路径穿越防护，只允许访问记忆目录内的文件。
 */
public class ReadMemoryTool implements BuiltinTool {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final MemoryLoader memoryLoader;

    public ReadMemoryTool(MemoryLoader memoryLoader) {
        this.memoryLoader = memoryLoader;
    }

    @Override
    public ToolSpec spec() {
        return new ToolSpec(
                "read_memory",
                "Read the full content of a specific memory file. Use this when the memory index shows " +
                "a relevant entry and you need its complete details.",
                "{\"type\":\"object\",\"properties\":{" +
                "\"filename\":{\"type\":\"string\",\"description\":\"Memory file name as shown in the index, e.g. user_role.md\"}}" +
                ",\"required\":[\"filename\"]}",
                ModeEnum.READ_ONLY
        );
    }

    @Override
    public String execute(String input) throws ToolException {
        try {
            JsonNode node = MAPPER.readTree(input);
            String filename = node.get("filename").asText();

            // 路径穿越防护：只允许访问记忆目录内的文件
            Path resolved = memoryLoader.memoryDir().resolve(filename).normalize();
            if (!resolved.startsWith(memoryLoader.memoryDir().normalize())) {
                throw new ToolException("read_memory: invalid filename: " + filename);
            }

            if (!Files.exists(resolved)) {
                throw new ToolException("read_memory: file not found: " + filename);
            }

            return Files.readString(resolved);

        } catch (IOException e) {
            throw new ToolException("read_memory failed: " + e.getMessage());
        } catch (ToolException e) {
            throw e;
        } catch (Exception e) {
            throw new ToolException("read_memory failed: " + e.getMessage());
        }
    }
}
