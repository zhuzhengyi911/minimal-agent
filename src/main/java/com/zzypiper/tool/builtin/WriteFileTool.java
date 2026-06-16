package com.zzypiper.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzypiper.permission.ModeEnum;
import com.zzypiper.tool.ToolException;
import com.zzypiper.tool.ToolSpec;

import java.nio.file.Files;
import java.nio.file.Path;

public class WriteFileTool implements BuiltinTool {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public ToolSpec spec() {
        return new ToolSpec(
                "write_file",
                "Write content to a file, creating it (and any missing parent directories) if it does not exist.",
                "{\"type\":\"object\",\"properties\":{" +
                "\"path\":{\"type\":\"string\"}," +
                "\"content\":{\"type\":\"string\"}}" +
                ",\"required\":[\"path\",\"content\"]}",
                ModeEnum.WORKSPACE_WRITE
        );
    }

    @Override
    public String execute(String input) throws ToolException {
        try {
            JsonNode node = MAPPER.readTree(input);
            Path path = Path.of(node.get("path").asText());
            String content = node.get("content").asText();

            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(path, content);

            return "Written to " + path;

        } catch (Exception e) {
            throw new ToolException("write_file failed: " + e.getMessage());
        }
    }
}
