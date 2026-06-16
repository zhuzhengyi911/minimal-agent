package com.zzypiper.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzypiper.permission.ModeEnum;
import com.zzypiper.tool.ToolException;
import com.zzypiper.tool.ToolSpec;

import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class GlobTool implements BuiltinTool {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public ToolSpec spec() {
        return new ToolSpec(
                "glob",
                "Find files matching a glob pattern. Returns matching paths sorted alphabetically.",
                "{\"type\":\"object\",\"properties\":{" +
                "\"pattern\":{\"type\":\"string\",\"description\":\"Glob pattern, e.g. **/*.java\"}," +
                "\"path\":{\"type\":\"string\",\"description\":\"Base directory to search (default: working directory)\"}}" +
                ",\"required\":[\"pattern\"]}",
                ModeEnum.READ_ONLY
        );
    }

    @Override
    public String execute(String input) throws ToolException {
        try {
            JsonNode node = MAPPER.readTree(input);
            String pattern = node.get("pattern").asText();
            Path base = node.has("path")
                    ? Path.of(node.get("path").asText())
                    : Path.of(System.getProperty("user.dir"));

            PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);

            List<String> matches;
            try (Stream<Path> stream = Files.walk(base)) {
                matches = stream
                        .filter(p -> {
                            Path relative = base.relativize(p);
                            return matcher.matches(relative);
                        })
                        .map(Path::toString)
                        .sorted()
                        .collect(Collectors.toList());
            }

            return matches.isEmpty() ? "(no matches)" : String.join("\n", matches);

        } catch (Exception e) {
            throw new ToolException("glob failed: " + e.getMessage());
        }
    }
}
