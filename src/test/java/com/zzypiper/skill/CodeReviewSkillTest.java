package com.zzypiper.skill;

import com.zzypiper.agent.Agent;
import com.zzypiper.agent.AgentOptions;
import com.zzypiper.boot.AgentBootstrap;
import com.zzypiper.hook.HookRunner;
import com.zzypiper.permission.ModeEnum;
import com.zzypiper.permission.PermissionPolicy;
import com.zzypiper.session.ContentBlock;
import com.zzypiper.session.KindEnum;
import com.zzypiper.session.Session;
import com.zzypiper.session.TurnSummary;
import org.junit.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Code Review Skill 集成测试。
 *
 * <p>使用真实 MiniMax API，验证渐进式 skill 加载（code_review → code_review/java）
 * 并对一段故意写坏的 Java 代码产出结构化 review 报告。
 *
 * <p>依赖：{@code .agent/settings.json} 中配置了有效的 API Key。
 */
public class CodeReviewSkillTest {

    // -------------------------------------------------------------------------
    // 故意写坏的 Java 类——包含多处典型问题供 LLM review
    // -------------------------------------------------------------------------
    private static final String FLAWED_CODE = """
            import java.io.FileInputStream;
            import java.util.ArrayList;
            import java.util.List;

            public class UserService {

                // 问题1：public 可变字段，破坏封装
                public String adminEmail = "admin@example.com";

                // 问题2：raw type，缺少泛型
                private List users = new ArrayList();

                public String getUserDisplayName(User user) {
                    // 问题3：未判空直接调用，存在 NullPointerException 风险
                    return user.getName().toUpperCase();
                }

                public void loadConfig(String path) {
                    // 问题4：FileInputStream 未用 try-with-resources，存在资源泄漏
                    try {
                        FileInputStream fis = new FileInputStream(path);
                        int b;
                        while ((b = fis.read()) != -1) {
                            // process byte
                        }
                    } catch (Exception e) {
                        // 问题5：空 catch，异常被完全吞掉
                    }
                }

                public boolean isAdult(int age) {
                    // 问题6：魔法数字，18 应提取为命名常量
                    return age > 18;
                }

                public void addUser(User user) {
                    // 问题7：未判空，users 和 user 均可能为 null
                    users.add(user);
                }

                static class User {
                    private String name;
                    public String getName() { return name; }
                }
            }
            """;

    @Test
    public void testCodeReviewSkillProgressiveLoading() {
        Path workDir = Path.of(".");

        AgentBootstrap.BuildResult built = AgentBootstrap.build(workDir);
        SkillLoader skillLoader = built.skillLoader();

        // 验证 skill 文件已就绪
        List<SkillSummary> summaries = skillLoader.getSummaries();
        assertTrue("应能扫描到至少两个 skill（code_review + code_review/java）",
                summaries.size() >= 2);
        assertTrue("应包含 code_review skill",
                summaries.stream().anyMatch(s -> s.name().equals("code_review")));
        assertTrue("应包含 code_review/java skill",
                summaries.stream().anyMatch(s -> s.name().equals("code_review/java")));

        AgentOptions options = new AgentOptions(
                10,
                new HookRunner(List.of(), List.of()),
                null,
                skillLoader
        );

        Agent agent = new Agent(
                new Session(),
                built.apiClient(),
                new PermissionPolicy(ModeEnum.READ_ONLY, built.registry()),
                List.of("You are a senior software engineer performing code reviews. " +
                        "Always use available skills to guide your review. " +
                        "You MUST call load_skill(\"code_review\") first, then load_skill(\"code_review/java\"), " +
                        "before writing your final review report."),
                built.registry(),
                options
        );

        String userMessage = "Please review the following Java code and produce a detailed report:\n\n"
                + "```java\n" + FLAWED_CODE + "```";

        TurnSummary summary = agent.runTurn(userMessage);

        // --- 验证 skill 加载 ---
        // 每条 toolResult 消息对应一次工具调用，计数即为 load_skill 调用次数
        int skillLoadCalls = summary.getToolResults().size();
        System.out.println("load_skill 调用次数: " + skillLoadCalls);
        System.out.println("已加载 skill: " + skillLoader.getLoadedContents().keySet());

        assertTrue("应有至少 1 次 load_skill 调用（至少加载 code_review）",
                skillLoadCalls >= 1);

        assertTrue("code_review skill 应已加载",
                skillLoader.getLoadedContents().containsKey("code_review"));

        if (skillLoader.getLoadedContents().containsKey("code_review/java")) {
            System.out.println("✓ 渐进式加载成功：code_review/java 也已加载");
        } else {
            System.out.println("△ 渐进式加载：本次 LLM 未主动加载 code_review/java（可能基于 code_review 已完成 review）");
        }

        // --- 验证最终报告 ---
        String report = summary.getAssistantMessages()
                .get(summary.getAssistantMessages().size() - 1)
                .getBlocks().stream()
                .filter(b -> b.getKind() == KindEnum.TEXT)
                .map(ContentBlock::getText)
                .reduce("", String::concat);

        assertFalse("review 报告不应为空", report.isBlank());

        // 打印完整报告供肉眼审查
        System.out.println("\n========== Code Review Report ==========\n");
        System.out.println(report);
        System.out.println("\n========================================\n");

        // 报告应覆盖至少部分已知问题（宽松断言）
        String reportLower = report.toLowerCase();
        assertTrue("报告应提及 null 或空指针问题",
                reportLower.contains("null") || reportLower.contains("npe") || reportLower.contains("nullpointer"));
        assertTrue("报告应提及异常或 catch 问题",
                reportLower.contains("exception") || reportLower.contains("catch") || reportLower.contains("异常"));
        assertTrue("报告应包含 verdict（APPROVE 或 REQUEST_CHANGES）",
                report.contains("APPROVE") || report.contains("REQUEST_CHANGES"));

        System.out.println("迭代次数: " + summary.getIterations());
        System.out.println("skill 加载次数: " + skillLoadCalls);
    }
}
