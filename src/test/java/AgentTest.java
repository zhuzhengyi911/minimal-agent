import com.zzypiper.Agent;
import com.zzypiper.MockApiClient;
import com.zzypiper.PermissionPolicy;
import com.zzypiper.StaticToolExecutor;
import com.zzypiper.module.*;
import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;


public class AgentTest {

    @Test
    public void testFullUserToolResultLoop(){
        // 配置mock：第一次调用请求add工具，第二次调用返回最终结果
        MockApiClient mockApi = new MockApiClient()
                .thenToolUse("tool-1","add" ,"2,2")
                .thenText("The answer is 4.");

        // 注册add工具
        StaticToolExecutor executor = new StaticToolExecutor()
                .register("add",input -> {
                   int sum = Arrays.stream(input.split(","))
                           .mapToInt(Integer::parseInt)
                           .sum();
                   return String.valueOf(sum);
                });

        // 创建Agent（WorkspaceWrite权限，无Hook）
        Agent agent = new Agent(
                new Session(),
                mockApi,
                executor,
                new PermissionPolicy(ModeEnum.WORKSPACE_WRITE),
                Arrays.asList("You are a helpful assistant.")
        );

        // 执行
        TurnSummary summary = agent.runTurn("what is 2 + 2?");

        // 验证：迭代了2次
        assertEquals(2,summary.getIterations());

        // 验证：2个助手消息，一个是ToolUse，一个是最终文本回复
        assertEquals(2,summary.getAssistantMessages().size());

        // 验证：1个工具结果
        assertEquals(1,summary.getToolResults().size());

        // 验证：会话共4条消息
        assertEquals(4,agent.getSession().size());

        Message toolResultMsg = summary.getToolResults().get(0);
        ContentBlock resultBlock = toolResultMsg.getBlocks().get(0);
        assertFalse(resultBlock.isError());
        assertEquals("4",resultBlock.getToolOutput());

        System.out.println("测试1通过：完整Turn Loop正常工作");
    }

}
