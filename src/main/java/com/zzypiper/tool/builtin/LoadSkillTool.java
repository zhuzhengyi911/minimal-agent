package com.zzypiper.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzypiper.permission.ModeEnum;
import com.zzypiper.skill.SkillLoader;
import com.zzypiper.tool.ToolException;
import com.zzypiper.tool.ToolSpec;

/**
 * 加载 skill 工具——LLM 判断需要某个 skill 时调用，完整内容将在下一 turn 注入 system prompt。
 *
 * <p>Skill 内容通过 tool_result 返回给 LLM，同时写入 {@link SkillLoader#loadedSkills}，
 * 之后每个 turn 的 {@code buildEffectivePrompt()} 都会将其注入 system prompt。
 */
public class LoadSkillTool implements BuiltinTool {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final SkillLoader skillLoader;

    public LoadSkillTool(SkillLoader skillLoader) {
        this.skillLoader = skillLoader;
    }

    @Override
    public ToolSpec spec() {
        return new ToolSpec(
                "load_skill",
                "Load the full content of a skill by name. Use this when a user request matches " +
                "a skill listed in the skill index. The skill content will be available in subsequent turns.",
                "{\"type\":\"object\",\"properties\":{" +
                "\"name\":{\"type\":\"string\",\"description\":" +
                "\"Skill name as shown in the index, e.g. code_review or frontend/react\"}}" +
                ",\"required\":[\"name\"]}",
                ModeEnum.READ_ONLY,
                true
        );
    }

    @Override
    public String execute(String input) throws ToolException {
        try {
            JsonNode node = MAPPER.readTree(input);
            String name = node.get("name").asText();
            skillLoader.loadSkill(name);
            return "Skill '" + name + "' loaded.";
        } catch (ToolException e) {
            throw e;
        } catch (Exception e) {
            throw new ToolException("load_skill failed: " + e.getMessage());
        }
    }
}
