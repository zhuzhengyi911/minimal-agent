package com.zzypiper.agentmd;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * AGENT.md 加载器——加载全局和项目级别的 AGENT.md 文件并注入 system prompt。
 *
 * <h2>加载层级</h2>
 * <ol>
 *   <li><b>全局层</b>：{@code ~/.agent/AGENT.md}——用户级别的偏好和通用规则</li>
 *   <li><b>项目层</b>：{@code {workDir}/AGENT.md}——项目说明、架构、编码规范</li>
 * </ol>
 *
 * <p>与 Claude Code 的 CLAUDE.md 行为一致：项目层在全局层之后注入，可覆盖或补充全局层内容。
 * 任一文件不存在时跳过，两个文件都不存在时 {@link #load()} 返回空字符串。
 *
 * <p>每个文件独立维护 mtime 缓存，外部修改实时生效。
 */
public class AgentMdLoader {

    private static final Logger LOG = Logger.getLogger(AgentMdLoader.class.getName());

    public static final String FILE_NAME = "AGENT.md";

    private static final Path GLOBAL_PATH =
            Path.of(System.getProperty("user.home"), ".agent", FILE_NAME);

    private final Path projectPath;

    private String globalCache   = null;
    private long   globalMtime   = -1L;
    private String projectCache  = null;
    private long   projectMtime  = -1L;

    public AgentMdLoader(Path workDir) {
        this.projectPath = workDir.resolve(FILE_NAME);
    }

    /**
     * 返回所有已存在的 AGENT.md 内容拼接（全局在前，项目在后）。
     * 两个文件都不存在时返回空字符串。
     */
    public String load() {
        List<String> parts = new ArrayList<>();

        String global = loadFile(GLOBAL_PATH, "global");
        if (!global.isBlank()) {
            parts.add(global);
        }

        String project = loadFile(projectPath, "project");
        if (!project.isBlank()) {
            parts.add(project);
        }

        return String.join("\n\n", parts);
    }

    // -------------------------------------------------------------------------
    // 内部：带 mtime 缓存的单文件读取
    // -------------------------------------------------------------------------

    private String loadFile(Path path, String label) {
        if (!Files.exists(path)) {
            return "";
        }
        try {
            long mtime = Files.getLastModifiedTime(path).toMillis();
            if ("global".equals(label)) {
                if (globalCache == null || mtime != globalMtime) {
                    globalCache = Files.readString(path);
                    globalMtime = mtime;
                    LOG.fine("Loaded global AGENT.md from " + path);
                }
                return globalCache;
            } else {
                if (projectCache == null || mtime != projectMtime) {
                    projectCache = Files.readString(path);
                    projectMtime = mtime;
                    LOG.fine("Loaded project AGENT.md from " + path);
                }
                return projectCache;
            }
        } catch (IOException e) {
            LOG.warning("Failed to read " + label + " AGENT.md (" + path + "): " + e.getMessage());
            return "";
        }
    }
}
