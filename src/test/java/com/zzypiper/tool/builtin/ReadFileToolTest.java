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

public class ReadFileToolTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private final ReadFileTool tool = new ReadFileTool();
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    public void testReadEntireFile() throws Exception {
        Path file = tmp.newFile("test.txt").toPath();
        Files.writeString(file, "line1\nline2\nline3");

        String result = tool.execute(mapper.writeValueAsString(Map.of("path", file.toString())));

        assertEquals("line1\nline2\nline3", result);
    }

    @Test
    public void testReadWithOffset() throws Exception {
        Path file = tmp.newFile("test.txt").toPath();
        Files.writeString(file, "line1\nline2\nline3");

        String result = tool.execute(mapper.writeValueAsString(Map.of("path", file.toString(), "offset", 1)));

        assertEquals("line2\nline3", result);
    }

    @Test
    public void testReadWithLimit() throws Exception {
        Path file = tmp.newFile("test.txt").toPath();
        Files.writeString(file, "line1\nline2\nline3");

        String result = tool.execute(mapper.writeValueAsString(Map.of("path", file.toString(), "limit", 2)));

        assertEquals("line1\nline2", result);
    }

    @Test
    public void testReadWithOffsetAndLimit() throws Exception {
        Path file = tmp.newFile("test.txt").toPath();
        Files.writeString(file, "line1\nline2\nline3\nline4");

        String result = tool.execute(mapper.writeValueAsString(Map.of("path", file.toString(), "offset", 1, "limit", 2)));

        assertEquals("line2\nline3", result);
    }

    @Test
    public void testFileNotFoundThrowsToolException() {
        try {
            tool.execute(mapper.createObjectNode().put("path", "/nonexistent/path.txt").toString());
            fail("should throw ToolException");
        } catch (ToolException e) {
            assertTrue(e.getMessage().startsWith("read_file failed:"));
        }
    }
}
