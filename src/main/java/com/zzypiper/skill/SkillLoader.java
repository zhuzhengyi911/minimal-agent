package com.zzypiper.skill;

import com.zzypiper.tool.ToolException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import java.util.stream.Stream;

/**
 * Skill 生命周期管理器。
 *
 * <p>启动时扫描 {@code .agent/skills/} 目录，加载所有 {@code .md} 文件的元数据（name + description）
 * 作为 summaries，始终注入 system prompt；完整内容按需加载到 {@link #loadedSkills}。
 *
 * <h2>目录结构</h2>
 * <pre>
 * .agent/skills/
 *   code_review.md          # 顶层 skill，name = code_review
 *   frontend/_index.md      # 子目录索引，name = frontend/_index
 *   frontend/react.md       # 子 skill，name = frontend/react
 * </pre>
 *
 * <h2>生命周期</h2>
 * <ul>
 *   <li>{@link #loadSkill(String)} — LLM 调用 load_skill 工具或 CLI 手动加载</li>
 *   <li>{@link #unloadSkill(String)} — CLI 手动卸载</li>
 *   <li>{@link #unloadAll()} — 压缩触发后全清，回到只有 summaries 的状态</li>
 * </ul>
 */
public class SkillLoader {

    private static final Logger LOG = Logger.getLogger(SkillLoader.class.getName());

    public static final String SKILLS_DIR = ".agent/skills";

    /**
     * 注入 system prompt 的使用指令——告知 LLM 何时及如何使用 skill 工具。
     */
    public static final String USAGE_INSTRUCTIONS = """
            ## Skill System
            You have access to skills via the `load_skill` tool. Skills provide specialized knowledge \
            or instructions for specific tasks.

            The skill index (shown below under "## Available Skills") lists all available skills \
            with their descriptions. When a user request matches a skill's purpose:
            1. Call `load_skill` with the skill name to load its full content.
            2. Apply the skill's instructions to fulfill the request.
            3. Sub-skills listed inside a loaded skill can be loaded the same way (progressive disclosure).

            Skills stay active for the rest of the session. They are automatically cleared on compaction.
            """;

    private final Path skillsDir;

    /** 所有 skill 的元数据（name + description），启动时扫描，静态不变。 */
    private final List<SkillSummary> summaries;

    /** name → 完整内容，运行时按需填充。使用 ConcurrentHashMap 支持并发工具调用。 */
    private final Map<String, String> loadedSkills = new ConcurrentHashMap<>();

    public SkillLoader(Path workDir) {
        this.skillsDir = workDir.resolve(SKILLS_DIR);
        this.summaries = scan();
    }

    // -------------------------------------------------------------------------
    // 查询
    // -------------------------------------------------------------------------

    /** 返回所有 skill 的元数据列表（不可变）。 */
    public List<SkillSummary> getSummaries() {
        return Collections.unmodifiableList(summaries);
    }

    /** 返回当前已加载的 skill 内容（name → content），不可变。 */
    public Map<String, String> getLoadedContents() {
        return Collections.unmodifiableMap(loadedSkills);
    }

    public Path skillsDir() {
        return skillsDir;
    }

    // -------------------------------------------------------------------------
    // 加载 / 卸载
    // -------------------------------------------------------------------------

    /**
     * 按 name 加载 skill 完整内容到内存。
     *
     * @param name skill 名称，如 {@code code_review} 或 {@code frontend/react}
     * @return skill 完整文件内容
     * @throws ToolException 若 skill 不存在
     */
    public String loadSkill(String name) throws ToolException {
        // 已加载则直接返回缓存（ConcurrentHashMap get 无锁）
        String cached = loadedSkills.get(name);
        if (cached != null) {
            return cached;
        }

        SkillSummary summary = findByName(name);
        if (summary == null) {
            throw new ToolException("load_skill: unknown skill: " + name);
        }

        try {
            String content = Files.readString(summary.path());
            // putIfAbsent：两个线程并发加载同一 skill 时，只有一个能写入，另一个读已有值
            String existing = loadedSkills.putIfAbsent(name, content);
            LOG.fine("Loaded skill: " + name);
            return existing != null ? existing : content;
        } catch (IOException e) {
            throw new ToolException("load_skill: failed to read skill '" + name + "': " + e.getMessage());
        }
    }

    /**
     * 从已加载集合中移除指定 skill（CLI 手动卸载）。
     *
     * @param name skill 名称
     * @throws ToolException 若该 skill 当前未加载
     */
    public void unloadSkill(String name) throws ToolException {
        if (loadedSkills.remove(name) == null) {
            throw new ToolException("unload_skill: skill not currently loaded: " + name);
        }
        LOG.fine("Unloaded skill: " + name);
    }

    /** 清空所有已加载 skill（压缩触发时调用）。 */
    public void unloadAll() {
        int count = loadedSkills.size();
        loadedSkills.clear();
        if (count > 0) {
            LOG.fine("Unloaded all skills (" + count + " cleared)");
        }
    }

    // -------------------------------------------------------------------------
    // 内部：扫描目录
    // -------------------------------------------------------------------------

    private List<SkillSummary> scan() {
        if (!Files.exists(skillsDir)) {
            return List.of();
        }

        List<SkillSummary> result = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(skillsDir)) {
            walk.filter(p -> p.toString().endsWith(".md"))
                .sorted()
                .forEach(p -> {
                    try {
                        SkillSummary summary = parseSummary(p);
                        result.add(summary);
                    } catch (Exception e) {
                        LOG.warning("Skipping skill file " + p + ": " + e.getMessage());
                    }
                });
        } catch (IOException e) {
            LOG.warning("Failed to scan skills directory: " + e.getMessage());
        }

        LOG.fine("Scanned " + result.size() + " skill(s) from " + skillsDir);
        return result;
    }

    /**
     * 解析 skill 文件的 frontmatter，提取 name 和 description。
     * 若 frontmatter 中无 name，则从相对路径推导（去掉 .md 后缀）。
     */
    private SkillSummary parseSummary(Path filePath) throws IOException {
        String raw = Files.readString(filePath);

        String name        = null;
        String description = null;

        if (raw.startsWith("---")) {
            int end = raw.indexOf("---", 3);
            if (end > 0) {
                String frontmatter = raw.substring(3, end);
                for (String line : frontmatter.split("\n")) {
                    if (line.startsWith("name:")) {
                        name = line.substring(5).strip();
                    } else if (line.startsWith("description:")) {
                        description = line.substring(12).strip();
                    }
                }
            }
        }

        // 无 frontmatter name → 从路径推导
        if (name == null || name.isBlank()) {
            String rel = skillsDir.relativize(filePath).toString();
            name = rel.endsWith(".md") ? rel.substring(0, rel.length() - 3) : rel;
            // 统一路径分隔符
            name = name.replace('\\', '/');
        }

        if (description == null || description.isBlank()) {
            description = "(no description)";
        }

        return new SkillSummary(name, description, filePath);
    }

    private SkillSummary findByName(String name) {
        return summaries.stream()
                .filter(s -> s.name().equals(name))
                .findFirst()
                .orElse(null);
    }
}
