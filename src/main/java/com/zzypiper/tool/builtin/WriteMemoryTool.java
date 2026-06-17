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
import java.util.ArrayList;
import java.util.List;

/**
 * 写记忆工具——将结构化记忆持久化到 {@code .agent/memory/} 目录。
 *
 * <p>每次调用会：
 * <ol>
 *   <li>写（或覆盖）{@code .agent/memory/{name}.md}，包含 YAML frontmatter 和正文</li>
 *   <li>更新 {@code MEMORY.md} 索引：已有条目则替换，否则追加</li>
 *   <li>使 {@link MemoryLoader} 缓存失效，保证下一个 turn 立刻读到新内容</li>
 * </ol>
 */
public class WriteMemoryTool implements BuiltinTool {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final MemoryLoader memoryLoader;

    public WriteMemoryTool(MemoryLoader memoryLoader) {
        this.memoryLoader = memoryLoader;
    }

    @Override
    public ToolSpec spec() {
        return new ToolSpec(
                "write_memory",
                "Save or update a persistent memory entry. Call this proactively when you learn something " +
                "worth remembering across sessions: user preferences, project decisions, feedback given, " +
                "or references to external systems.",
                "{\"type\":\"object\",\"properties\":{" +
                "\"name\":{\"type\":\"string\",\"description\":\"File name without extension, snake_case, e.g. user_role\"}," +
                "\"type\":{\"type\":\"string\",\"enum\":[\"user\",\"feedback\",\"project\",\"reference\"]," +
                "\"description\":\"user=who the user is; feedback=corrections/confirmations; project=goals/decisions; reference=external pointers\"}," +
                "\"description\":{\"type\":\"string\",\"description\":\"One-line summary shown in the memory index (under 150 chars)\"}," +
                "\"content\":{\"type\":\"string\",\"description\":\"Full memory content in Markdown\"}}" +
                ",\"required\":[\"name\",\"type\",\"description\",\"content\"]}",
                ModeEnum.WORKSPACE_WRITE
        );
    }

    @Override
    public String execute(String input) throws ToolException {
        try {
            JsonNode node = MAPPER.readTree(input);
            String name        = node.get("name").asText();
            String type        = node.get("type").asText();
            String description = node.get("description").asText();
            String content     = node.get("content").asText();

            Path dir = memoryLoader.memoryDir();
            Files.createDirectories(dir);

            // 写记忆文件（带 YAML frontmatter）
            String filename = name + ".md";
            Path filePath = dir.resolve(filename);
            String fileContent = "---\nname: " + name + "\ndescription: " + description
                    + "\ntype: " + type + "\n---\n\n" + content + "\n";
            Files.writeString(filePath, fileContent);

            // 更新 MEMORY.md 索引
            updateIndex(dir, name, filename, description);

            // 使缓存失效，下一个 turn load() 会读到新索引
            memoryLoader.invalidate();

            return "Memory saved: " + filename;

        } catch (IOException e) {
            throw new ToolException("write_memory failed: " + e.getMessage());
        } catch (Exception e) {
            throw new ToolException("write_memory failed: " + e.getMessage());
        }
    }

    private void updateIndex(Path dir, String name, String filename, String description) throws IOException {
        Path indexPath = dir.resolve(MemoryLoader.INDEX_FILE);
        String newLine = "- [" + name + "](" + filename + ") — " + description;

        if (!Files.exists(indexPath)) {
            Files.writeString(indexPath, "# Memory Index\n\n" + newLine + "\n");
            return;
        }

        String existing = Files.readString(indexPath);
        String marker = "](" + filename + ")";

        // 检查是否已有此条目（按文件名匹配）
        String[] rawLines = existing.split("\n", -1);
        List<String> lines = new ArrayList<>(List.of(rawLines));

        int found = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).contains(marker)) {
                found = i;
                break;
            }
        }

        if (found >= 0) {
            lines.set(found, newLine);
            Files.writeString(indexPath, String.join("\n", lines));
        } else {
            String appended = existing.endsWith("\n")
                    ? existing + newLine + "\n"
                    : existing + "\n" + newLine + "\n";
            Files.writeString(indexPath, appended);
        }
    }
}
