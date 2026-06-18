package com.zzypiper.skill;

import java.nio.file.Path;

/**
 * Skill 的轻量元数据——始终注入 system prompt，不包含完整内容。
 *
 * @param name        skill 唯一标识，多层级用 "/" 分隔，如 {@code frontend/react}
 * @param description 一行描述，供 LLM 判断是否需要加载该 skill
 * @param path        skill 文件的磁盘路径，供 {@link SkillLoader} 按需读取完整内容
 */
public record SkillSummary(String name, String description, Path path) {}
