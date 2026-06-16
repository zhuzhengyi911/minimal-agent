package com.zzypiper.tool.builtin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzypiper.tool.ToolException;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.Assert.*;

public class EditFileToolTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private final EditFileTool tool = new EditFileTool();
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    public void testReplaceFirstOccurrence() throws Exception {
        Path file = tmp.newFile("test.txt").toPath();
        Files.writeString(file, "foo bar foo");

        tool.execute(mapper.writeValueAsString(Map.of(
                "path", file.toString(),
                "old_string", "foo",
                "new_string", "baz"
        )));

        // 只替换第一处，第二处保持不变
        assertEquals("baz bar foo", Files.readString(file));
    }

    @Test
    public void testReturnMessage() throws Exception {
        Path file = tmp.newFile("test.txt").toPath();
        Files.writeString(file, "hello world");

        String result = tool.execute(mapper.writeValueAsString(Map.of(
                "path", file.toString(),
                "old_string", "hello",
                "new_string", "hi"
        )));

        assertEquals("Edited " + file, result);
    }

    @Test
    public void testOldStringNotFoundThrowsToolException() throws Exception {
        Path file = tmp.newFile("test.txt").toPath();
        Files.writeString(file, "hello world");

        try {
            tool.execute(mapper.writeValueAsString(Map.of(
                    "path", file.toString(),
                    "old_string", "xyz",
                    "new_string", "abc"
            )));
            fail("should throw ToolException");
        } catch (ToolException e) {
            assertTrue(e.getMessage().contains("not found"));
        }
    }

    @Test
    public void testFileNotFoundThrowsToolException() {
        try {
            tool.execute(mapper.createObjectNode()
                    .put("path", "/nonexistent/file.txt")
                    .put("old_string", "a")
                    .put("new_string", "b")
                    .toString());
            fail("should throw ToolException");
        } catch (ToolException e) {
            assertTrue(e.getMessage().startsWith("edit_file failed:"));
        }
    }
}
