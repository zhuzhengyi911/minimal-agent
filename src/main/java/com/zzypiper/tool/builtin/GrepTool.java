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
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public class GrepTool implements BuiltinTool {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_RESULTS = 100;

    @Override
    public ToolSpec spec() {
        return new ToolSpec(
                "grep",
                "Search file contents using a regex pattern. Returns matching lines with file path and line number.",
                "{\"type\":\"object\",\"properties\":{" +
                "\"pattern\":{\"type\":\"string\",\"description\":\"Regex pattern to search for\"}," +
                "\"path\":{\"type\":\"string\",\"description\":\"Directory to search (default: working directory)\"}," +
                "\"glob\":{\"type\":\"string\",\"description\":\"File pattern filter, e.g. *.java\"}," +
                "\"case_insensitive\":{\"type\":\"boolean\",\"description\":\"Case insensitive search (default false)\"}}" +
                ",\"required\":[\"pattern\"]}",
                ModeEnum.READ_ONLY
        );
    }

    @Override
    public String execute(String input) throws ToolException {
        try {
            JsonNode node = MAPPER.readTree(input);
            String patternStr = node.get("pattern").asText();
            Path base = node.has("path")
                    ? Path.of(node.get("path").asText())
                    : Path.of(System.getProperty("user.dir"));
            boolean caseInsensitive = node.has("case_insensitive") && node.get("case_insensitive").asBoolean();

            int flags = caseInsensitive ? Pattern.CASE_INSENSITIVE : 0;
            Pattern regex = Pattern.compile(patternStr, flags);

            PathMatcher fileMatcher = node.has("glob")
                    ? FileSystems.getDefault().getPathMatcher("glob:" + node.get("glob").asText())
                    : null;

            List<String> results = new ArrayList<>();

            try (Stream<Path> stream = Files.walk(base)) {
                stream.filter(Files::isRegularFile)
                      .filter(p -> fileMatcher == null || fileMatcher.matches(p.getFileName()))
                      .forEach(file -> {
                          if (results.size() >= MAX_RESULTS) return;
                          try {
                              List<String> lines = Files.readAllLines(file);
                              for (int i = 0; i < lines.size() && results.size() < MAX_RESULTS; i++) {
                                  Matcher m = regex.matcher(lines.get(i));
                                  if (m.find()) {
                                      results.add(file + ":" + (i + 1) + ":" + lines.get(i));
                                  }
                              }
                          } catch (Exception ignored) {
                              // 跳过无法读取的文件（二进制文件等）
                          }
                      });
            }

            if (results.isEmpty()) return "(no matches)";
            if (results.size() == MAX_RESULTS) results.add("... (results truncated at " + MAX_RESULTS + ")");
            return String.join("\n", results);

        } catch (Exception e) {
            throw new ToolException("grep failed: " + e.getMessage());
        }
    }
}
