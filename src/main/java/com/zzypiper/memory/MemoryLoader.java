package com.zzypiper.memory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;

/**
 * 带 mtime 缓存的记忆索引加载器。
 *
 * <p>每次调用 {@link #load()} 时检查 MEMORY.md 的最后修改时间，
 * 仅在文件变更时才重读磁盘，其余情况直接返回缓存内容。
 *
 * <p>当 {@code write_memory} 工具写完文件后，应调用 {@link #invalidate()} 使缓存失效，
 * 确保下一个 turn 的 {@link #load()} 立刻读到最新内容。
 */
public class MemoryLoader {

    private static final Logger LOG = Logger.getLogger(MemoryLoader.class.getName());

    public static final String MEMORY_DIR  = ".agent/memory";
    public static final String INDEX_FILE  = "MEMORY.md";

    /**
     * 注入 system prompt 的使用指令——告知 AI 何时调用记忆工具。
     * Agent 在 MemoryLoader 非空时自动追加到每次请求的 system prompt。
     */
    public static final String USAGE_INSTRUCTIONS = """
            ## Memory System
            You have access to a persistent memory system via two tools:
            - `write_memory`: Save important information that should persist across sessions.
            - `read_memory`: Read the full content of a specific memory file when you need details.

            The memory index (shown below under "## Current Memory Index") lists what you currently remember.
            Each entry is one line; use `read_memory` to fetch the full content of any entry.

            **When to call `write_memory` (do so proactively, without waiting to be asked):**
            - User reveals their role, background, expertise, or preferences
            - User corrects your approach or confirms an unusual choice
            - A key project decision, goal, or constraint is established
            - An important deadline, stakeholder, or external reference is mentioned

            **Memory types:**
            - `user`: Who the user is, their background and preferences
            - `feedback`: Corrections or confirmations about how to work together
            - `project`: Goals, decisions, constraints, current status
            - `reference`: Pointers to external systems or resources
            """;

    private final Path indexPath;
    private final Path memoryDir;

    private volatile String cachedContent = null;
    private volatile long   cachedMtime   = -1L;

    public MemoryLoader(Path workDir) {
        this.memoryDir = workDir.resolve(MEMORY_DIR);
        this.indexPath = memoryDir.resolve(INDEX_FILE);
    }

    /**
     * 返回记忆索引内容（带 mtime 缓存）。
     * 若索引文件不存在，返回空字符串。
     */
    public String load() {
        if (!Files.exists(indexPath)) {
            return "";
        }
        try {
            long currentMtime = Files.getLastModifiedTime(indexPath).toMillis();
            if (cachedContent == null || currentMtime != cachedMtime) {
                cachedContent = Files.readString(indexPath);
                cachedMtime   = currentMtime;
                LOG.fine("Reloaded memory index from " + indexPath);
            }
            return cachedContent;
        } catch (IOException e) {
            LOG.warning("Failed to read memory index: " + e.getMessage());
            return cachedContent != null ? cachedContent : "";
        }
    }

    /** 使缓存失效，下次 {@link #load()} 将重读磁盘。 */
    public void invalidate() {
        cachedMtime = -1L;
    }

    public Path memoryDir() {
        return memoryDir;
    }
}
