package com.zzypiper.tool.builtin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.Assert.*;

public class GrepToolTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private final GrepTool tool = new GrepTool();
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    public void testBasicSearch() throws Exception {
        Path file = tmp.newFile("test.java").toPath();
        Files.writeString(file, "public class Foo {\n    int x = 1;\n    int y = 2;\n}");

        String result = tool.execute(mapper.writeValueAsString(Map.of(
                "pattern", "int",
                "path", tmp.getRoot().toString()
        )));

        assertTrue(result.contains("int x = 1"));
        assertTrue(result.contains("int y = 2"));
    }

    @Test
    public void testOutputIncludesLineNumber() throws Exception {
        Path file = tmp.newFile("test.txt").toPath();
        Files.writeString(file, "aaa\nbbb\nccc");

        String result = tool.execute(mapper.writeValueAsString(Map.of(
                "pattern", "bbb",
                "path", tmp.getRoot().toString()
        )));

        // 格式：filepath:2:bbb
        assertTrue(result.contains(":2:"));
        assertTrue(result.contains("bbb"));
    }

    @Test
    public void testCaseInsensitiveSearch() throws Exception {
        Path file = tmp.newFile("test.txt").toPath();
        Files.writeString(file, "Hello World\nhello world");

        ObjectNode input = mapper.createObjectNode()
                .put("pattern", "HELLO")
                .put("path", tmp.getRoot().toString())
                .put("case_insensitive", true);

        String result = tool.execute(mapper.writeValueAsString(input));

        assertTrue(result.contains("Hello World"));
        assertTrue(result.contains("hello world"));
    }

    @Test
    public void testGlobFilterOnlySearchesMatchingFiles() throws Exception {
        Path javaFile = tmp.newFile("Source.java").toPath();
        Path txtFile = tmp.newFile("notes.txt").toPath();
        Files.writeString(javaFile, "class Foo {}");
        Files.writeString(txtFile, "class notes");

        String result = tool.execute(mapper.writeValueAsString(Map.of(
                "pattern", "class",
                "path", tmp.getRoot().toString(),
                "glob", "*.java"
        )));

        assertTrue(result.contains("Source.java"));
        assertFalse(result.contains("notes.txt"));
    }

    @Test
    public void testNoMatchesReturnsMessage() throws Exception {
        tmp.newFile("test.txt");

        String result = tool.execute(mapper.writeValueAsString(Map.of(
                "pattern", "xyz_not_exist_123",
                "path", tmp.getRoot().toString()
        )));

        assertEquals("(no matches)", result);
    }
}
