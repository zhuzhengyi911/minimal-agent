package com.zzypiper.tool.builtin;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.file.Files;
import java.util.Map;

import static org.junit.Assert.*;

public class GlobToolTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private final GlobTool tool = new GlobTool();
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    public void testMatchByExtension() throws Exception {
        tmp.newFile("Foo.java");
        tmp.newFile("Bar.java");
        tmp.newFile("notes.txt");

        String result = tool.execute(mapper.writeValueAsString(Map.of(
                "pattern", "*.java",
                "path", tmp.getRoot().toString()
        )));

        assertTrue(result.contains("Foo.java"));
        assertTrue(result.contains("Bar.java"));
        assertFalse(result.contains("notes.txt"));
    }

    @Test
    public void testRecursivePattern() throws Exception {
        File subDir = tmp.newFolder("src", "main");
        Files.writeString(subDir.toPath().resolve("Main.java"), "");
        tmp.newFile("README.md");

        String result = tool.execute(mapper.writeValueAsString(Map.of(
                "pattern", "**/*.java",
                "path", tmp.getRoot().toString()
        )));

        assertTrue(result.contains("Main.java"));
        assertFalse(result.contains("README.md"));
    }

    @Test
    public void testResultsAreSorted() throws Exception {
        tmp.newFile("C.java");
        tmp.newFile("A.java");
        tmp.newFile("B.java");

        String result = tool.execute(mapper.writeValueAsString(Map.of(
                "pattern", "*.java",
                "path", tmp.getRoot().toString()
        )));

        int posA = result.indexOf("A.java");
        int posB = result.indexOf("B.java");
        int posC = result.indexOf("C.java");
        assertTrue(posA < posB && posB < posC);
    }

    @Test
    public void testNoMatchesReturnsMessage() throws Exception {
        tmp.newFile("notes.txt");

        String result = tool.execute(mapper.writeValueAsString(Map.of(
                "pattern", "*.java",
                "path", tmp.getRoot().toString()
        )));

        assertEquals("(no matches)", result);
    }
}
