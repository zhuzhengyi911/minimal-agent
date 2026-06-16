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

public class WriteFileToolTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private final WriteFileTool tool = new WriteFileTool();
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    public void testWriteCreatesFile() throws Exception {
        Path file = tmp.getRoot().toPath().resolve("hello.txt");
        String input = mapper.writeValueAsString(Map.of(
                "path", file.toString(),
                "content", "hello world"
        ));

        String result = tool.execute(input);

        assertEquals("Written to " + file, result);
        assertEquals("hello world", Files.readString(file));
    }

    @Test
    public void testWriteCreatesParentDirectories() throws Exception {
        Path file = tmp.getRoot().toPath().resolve("a/b/c/hello.txt");
        String input = mapper.writeValueAsString(Map.of(
                "path", file.toString(),
                "content", "nested"
        ));

        tool.execute(input);

        assertTrue(Files.exists(file));
        assertEquals("nested", Files.readString(file));
    }

    @Test
    public void testWriteOverwritesExistingFile() throws Exception {
        Path file = tmp.newFile("existing.txt").toPath();
        Files.writeString(file, "old content");

        String input = mapper.writeValueAsString(Map.of(
                "path", file.toString(),
                "content", "new content"
        ));

        tool.execute(input);

        assertEquals("new content", Files.readString(file));
    }

    @Test
    public void testInvalidJsonThrowsToolException() {
        try {
            tool.execute("not json");
            fail("should throw ToolException");
        } catch (ToolException e) {
            assertTrue(e.getMessage().startsWith("write_file failed:"));
        }
    }
}
