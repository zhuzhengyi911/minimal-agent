package com.zzypiper.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzypiper.permission.ModeEnum;
import com.zzypiper.tool.ToolException;
import com.zzypiper.tool.ToolSpec;

import java.nio.file.Files;
import java.nio.file.Path;

public class EditFileTool implements BuiltinTool {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public ToolSpec spec() {
        return new ToolSpec(
                "edit_file",
                "Replace the first occurrence of old_string with new_string in a file. Fails if old_string is not found.",
                "{\"type\":\"object\",\"properties\":{" +
                "\"path\":{\"type\":\"string\"}," +
                "\"old_string\":{\"type\":\"string\"}," +
                "\"new_string\":{\"type\":\"string\"}}" +
                ",\"required\":[\"path\",\"old_string\",\"new_string\"]}",
                ModeEnum.WORKSPACE_WRITE
        );
    }

    @Override
    public String execute(String input) throws ToolException {
        try {
            JsonNode node = MAPPER.readTree(input);
            Path path = Path.of(node.get("path").asText());
            String oldString = node.get("old_string").asText();
            String newString = node.get("new_string").asText();

            String content = Files.readString(path);

            int idx = content.indexOf(oldString);
            if (idx < 0) {
                throw new ToolException("old_string not found in " + path);
            }

            String updated = content.substring(0, idx)
                    + newString
                    + content.substring(idx + oldString.length());
            Files.writeString(path, updated);

            return "Edited " + path;

        } catch (ToolException e) {
            throw e;
        } catch (Exception e) {
            throw new ToolException("edit_file failed: " + e.getMessage());
        }
    }
}
