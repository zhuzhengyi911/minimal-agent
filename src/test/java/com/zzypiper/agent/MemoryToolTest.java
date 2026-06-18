package com.zzypiper.agent;

import com.zzypiper.api.mock.MockApiClient;
import com.zzypiper.hook.HookRunner;
import com.zzypiper.memory.MemoryLoader;
import com.zzypiper.permission.ModeEnum;
import com.zzypiper.permission.PermissionPolicy;
import com.zzypiper.session.ContentBlock;
import com.zzypiper.session.Session;
import com.zzypiper.session.TurnSummary;
import com.zzypiper.tool.ToolRegistry;
import com.zzypiper.tool.builtin.ReadMemoryTool;
import com.zzypiper.tool.builtin.WriteMemoryTool;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.*;

public class MemoryToolTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    // -------------------------------------------------------------------------
    // 辅助：搭建 Agent（含记忆工具）
    // -------------------------------------------------------------------------

    private Agent buildAgent(MockApiClient mockApi, MemoryLoader memoryLoader) {
        ToolRegistry registry = new ToolRegistry();
        WriteMemoryTool writeTool = new WriteMemoryTool(memoryLoader);
        ReadMemoryTool  readTool  = new ReadMemoryTool(memoryLoader);
        registry.register(writeTool.spec(), writeTool::execute);
        registry.register(readTool.spec(),  readTool::execute);

        AgentOptions options = new AgentOptions(
                Integer.MAX_VALUE,
                new HookRunner(List.of(), List.of()),
                memoryLoader,
                null,
                null
        );
        return new Agent(
                new Session(),
                mockApi,
                new PermissionPolicy(ModeEnum.WORKSPACE_WRITE, registry),
                List.of("You are a helpful assistant."),
                registry,
                options
        );
    }

    // -------------------------------------------------------------------------
    // 测试 1：write_memory 工具调用后文件落盘，索引更新，缓存失效
    // -------------------------------------------------------------------------

    @Test
    public void testWriteMemoryPersistsFilesAndUpdatesIndex() throws Exception {
        Path workDir = tempFolder.getRoot().toPath();
        MemoryLoader memoryLoader = new MemoryLoader(workDir);

        String writeInput = "{" +
                "\"name\":\"user_pref\"," +
                "\"type\":\"user\"," +
                "\"description\":\"User prefers snake_case naming\"," +
                "\"content\":\"Always use snake_case for all identifiers.\"" +
                "}";

        MockApiClient mockApi = new MockApiClient()
                .thenToolUse("tool-1", "write_memory", writeInput)
                .thenText("Got it, I'll remember that.");

        Agent agent = buildAgent(mockApi, memoryLoader);
        TurnSummary summary = agent.runTurn("Please remember I prefer snake_case.");

        // --- tool 执行无误 ---
        assertEquals("应有一次工具调用", 1, summary.getToolResults().size());
        ContentBlock result = summary.getToolResults().get(0).getBlocks().get(0);
        assertFalse("write_memory 不应报错", result.isError());
        assertTrue("返回值应包含文件名", result.getToolOutput().contains("user_pref.md"));

        // --- 记忆文件已落盘 ---
        Path memFile = workDir.resolve(".agent/memory/user_pref.md");
        assertTrue("记忆文件应已创建", Files.exists(memFile));
        String fileContent = Files.readString(memFile);
        assertTrue("文件应包含 frontmatter type", fileContent.contains("type: user"));
        assertTrue("文件应包含记忆正文", fileContent.contains("snake_case"));

        // --- MEMORY.md 索引已更新 ---
        Path indexFile = workDir.resolve(".agent/memory/MEMORY.md");
        assertTrue("MEMORY.md 应已创建", Files.exists(indexFile));
        String indexContent = Files.readString(indexFile);
        assertTrue("索引应包含该条目链接", indexContent.contains("user_pref.md"));
        assertTrue("索引应包含描述", indexContent.contains("snake_case"));

        // --- MemoryLoader 缓存已失效，load() 返回最新索引 ---
        String loaded = memoryLoader.load();
        assertFalse("load() 应返回非空内容", loaded.isBlank());
        assertTrue("load() 内容应包含新写入的条目", loaded.contains("user_pref.md"));
    }

    // -------------------------------------------------------------------------
    // 测试 2：read_memory 工具调用后返回指定记忆文件内容
    // -------------------------------------------------------------------------

    @Test
    public void testReadMemoryReturnsFileContent() throws Exception {
        Path workDir = tempFolder.getRoot().toPath();
        MemoryLoader memoryLoader = new MemoryLoader(workDir);

        // 预先写好记忆文件，模拟"上次 session 已写入"的场景
        Path memDir = workDir.resolve(".agent/memory");
        Files.createDirectories(memDir);
        Files.writeString(memDir.resolve("coding_style.md"),
                "---\nname: coding_style\ndescription: Coding style preferences\ntype: feedback\n---\n\n" +
                "User wants concise code without excessive comments.\n");
        Files.writeString(memDir.resolve(MemoryLoader.INDEX_FILE),
                "# Memory Index\n\n- [coding_style](coding_style.md) — Coding style preferences\n");

        MockApiClient mockApi = new MockApiClient()
                .thenToolUse("tool-1", "read_memory", "{\"filename\":\"coding_style.md\"}")
                .thenText("Understood, I'll keep the code concise.");

        Agent agent = buildAgent(mockApi, memoryLoader);
        TurnSummary summary = agent.runTurn("What do you remember about my coding style?");

        // --- tool 执行无误 ---
        assertEquals("应有一次工具调用", 1, summary.getToolResults().size());
        ContentBlock result = summary.getToolResults().get(0).getBlocks().get(0);
        assertFalse("read_memory 不应报错", result.isError());
        assertTrue("返回内容应包含记忆正文", result.getToolOutput().contains("concise"));
    }

    // -------------------------------------------------------------------------
    // 测试 3：重复 write_memory 同名条目时，索引中只保留一条（覆盖而非追加）
    // -------------------------------------------------------------------------

    @Test
    public void testWriteMemoryOverwritesDuplicateIndexEntry() throws Exception {
        Path workDir = tempFolder.getRoot().toPath();
        MemoryLoader memoryLoader = new MemoryLoader(workDir);

        String firstInput = "{\"name\":\"lang_pref\",\"type\":\"user\"," +
                "\"description\":\"User prefers Java\",\"content\":\"Prefers Java.\"}";
        String secondInput = "{\"name\":\"lang_pref\",\"type\":\"user\"," +
                "\"description\":\"User prefers Go\",\"content\":\"Actually prefers Go.\"}";

        MockApiClient mockApi = new MockApiClient()
                .thenToolUse("tool-1", "write_memory", firstInput)
                .thenText("Noted.")
                .thenToolUse("tool-2", "write_memory", secondInput)
                .thenText("Updated.");

        Agent agent = buildAgent(mockApi, memoryLoader);
        agent.runTurn("I prefer Java.");
        agent.runTurn("Actually I prefer Go.");

        Path indexFile = workDir.resolve(".agent/memory/MEMORY.md");
        String indexContent = Files.readString(indexFile);

        // 索引中 lang_pref.md 只出现一次
        long occurrences = indexContent.lines()
                .filter(line -> line.contains("lang_pref.md"))
                .count();
        assertEquals("同名条目应只保留一条，不应重复追加", 1, occurrences);

        // 内容已更新为最新描述
        assertTrue("索引应反映最新描述", indexContent.contains("User prefers Go"));
    }
}
