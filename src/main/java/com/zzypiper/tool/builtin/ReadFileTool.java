package com.zzypiper.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzypiper.permission.ModeEnum;
import com.zzypiper.tool.ToolException;
import com.zzypiper.tool.ToolSpec;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class ReadFileTool implements BuiltinTool {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public ToolSpec spec() {
        return new ToolSpec(
                "read_file",
                "Read the contents of a file. Optionally specify offset (start line, 0-based) and limit (max lines).",
                "{\"type\":\"object\",\"properties\":{" +
                "\"path\":{\"type\":\"string\"}," +
                "\"offset\":{\"type\":\"integer\",\"description\":\"Start line, 0-based\"}," +
                "\"limit\":{\"type\":\"integer\",\"description\":\"Max lines to read\"}}" +
                ",\"required\":[\"path\"]}",
                ModeEnum.READ_ONLY,
                true
        );
    }

    @Override
    public String execute(String input) throws ToolException {
        try {
            JsonNode node = MAPPER.readTree(input);
            Path path = Path.of(node.get("path").asText());

            if (node.has("offset") || node.has("limit")) {
                List<String> lines = Files.readAllLines(path);
                int offset = node.has("offset") ? node.get("offset").asInt() : 0;
                int limit  = node.has("limit")  ? node.get("limit").asInt()  : lines.size();
                return String.join("\n", lines.subList(
                        Math.min(offset, lines.size()),
                        Math.min(offset + limit, lines.size())
                ));
            }

            return Files.readString(path);

        } catch (IOException e) {
            throw new ToolException("read_file failed: " + e.getMessage());
        } catch (Exception e) {
            throw new ToolException("read_file failed: " + e.getMessage());
        }
    }
}
