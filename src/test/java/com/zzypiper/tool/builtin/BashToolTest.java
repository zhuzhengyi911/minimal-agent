package com.zzypiper.tool.builtin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzypiper.tool.ToolException;
import org.junit.Test;

import static org.junit.Assert.*;

public class BashToolTest {

    private final BashTool tool = new BashTool();
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    public void testBasicCommand() throws Exception {
        String input = mapper.writeValueAsString(java.util.Map.of("command", "echo hello"));
        assertEquals("hello\n", tool.execute(input));
    }

    @Test
    public void testNonZeroExitCodeAppendsExitCode() throws Exception {
        String input = mapper.writeValueAsString(java.util.Map.of("command", "exit 1"));
        String result = tool.execute(input);
        assertTrue(result.contains("[exit code: 1]"));
    }

    @Test
    public void testStderrMergedIntoStdout() throws Exception {
        String input = mapper.writeValueAsString(java.util.Map.of("command", "echo errline >&2"));
        String result = tool.execute(input);
        assertTrue(result.contains("errline"));
    }

    @Test
    public void testTimeoutThrowsToolException() {
        try {
            tool.execute(mapper.createObjectNode()
                    .put("command", "sleep 10")
                    .put("timeout_ms", 200)
                    .toString());
            fail("should throw ToolException");
        } catch (ToolException e) {
            assertTrue(e.getMessage().contains("timed out"));
        }
    }
}
