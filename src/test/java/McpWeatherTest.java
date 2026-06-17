import com.zzypiper.agent.Agent;
import com.zzypiper.api.minimax.MinimaxApiClient;
import com.zzypiper.cli.AgentBootstrap;
import com.zzypiper.permission.ModeEnum;
import com.zzypiper.permission.PermissionPolicy;
import com.zzypiper.session.ContentBlock;
import com.zzypiper.session.KindEnum;
import com.zzypiper.session.Session;
import com.zzypiper.session.TurnSummary;
import com.zzypiper.tool.ToolRegistry;
import org.junit.Test;

import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.Assert.*;

public class McpWeatherTest {

    @Test
    public void testQueryShanghaiWeather() throws Exception {
        String apiKey = System.getenv("MINIMAX_API_KEY");
        org.junit.Assume.assumeNotNull("需要设置环境变量 MINIMAX_API_KEY", apiKey);

        // 从 .agent/mcp.json 加载 weather MCP server
        AgentBootstrap.BuildResult built = AgentBootstrap.build(Path.of("."));
        ToolRegistry registry = built.registry();

        assertTrue("mcp__weather__get_weather 工具应已注册",
                registry.contains("mcp__weather__get_weather"));

        MinimaxApiClient client = new MinimaxApiClient(apiKey);
        Agent agent = new Agent(
                new Session(),
                client,
                new PermissionPolicy(ModeEnum.WORKSPACE_WRITE, registry),
                Arrays.asList("你是一个有用的助手。"),
                registry
        );

        try {
            TurnSummary summary = agent.runTurn("上海今天天气怎么样？");

            assertTrue("应至少调用一次天气工具", summary.getToolResults().size() >= 1);

            ContentBlock toolResult = summary.getToolResults().get(0).getBlocks().get(0);
            assertFalse("工具调用不应报错", toolResult.isError());
            assertFalse("工具返回内容不应为空", toolResult.getToolOutput().isBlank());
            System.out.println("工具返回原始内容：" + toolResult.getToolOutput());

            String finalReply = summary.getAssistantMessages()
                    .get(summary.getAssistantMessages().size() - 1)
                    .getBlocks().stream()
                    .filter(b -> b.getKind() == KindEnum.TEXT)
                    .map(ContentBlock::getText)
                    .reduce("", String::concat);

            assertFalse("最终回复不应为空", finalReply.isBlank());
            System.out.println("天气查询通过，LLM 最终回复：" + finalReply);
        } finally {
            built.shutdown();
        }
    }
}
