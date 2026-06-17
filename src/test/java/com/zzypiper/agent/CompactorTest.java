package com.zzypiper.agent;

import com.zzypiper.api.mock.MockApiClient;
import com.zzypiper.compaction.Compactor;
import com.zzypiper.compaction.CompactionUsage;
import com.zzypiper.session.Message;
import com.zzypiper.session.MessageType;
import com.zzypiper.session.Session;
import org.junit.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.Assert.*;

public class CompactorTest {

    // -------------------------------------------------------------------------
    // 辅助：快速填充 Session
    // -------------------------------------------------------------------------

    /** 向 session 中写入 n 轮对话（每轮一条 user + 一条 assistant）。 */
    private void fillSession(Session session, int turns) {
        for (int i = 0; i < turns; i++) {
            session.addMessage(Message.userText("user message " + i));
            session.addMessage(Message.assistant(
                    List.of(com.zzypiper.session.ContentBlock.text("assistant reply " + i))));
        }
    }

    // -------------------------------------------------------------------------
    // 测试 1：消息数量未达阈值，不压缩
    // -------------------------------------------------------------------------

    @Test
    public void testNoCompactionWhenBelowThreshold() {
        Session session = new Session();
        fillSession(session, 5);   // 10 条消息 < KEEP_RECENT_MESSAGES(20)
        int sizeBefore = session.size();

        MockApiClient mockApi = new MockApiClient();
        Compactor compactor = new Compactor(mockApi);

        Optional<CompactionUsage> result = compactor.compact(session, List.of("system prompt"));

        assertFalse("消息数未超阈值，不应触发压缩", result.isPresent());
        assertEquals("Session 大小不应改变", sizeBefore, session.size());
        assertEquals("压缩次数应为 0", 0, session.getCompactionCount());
        assertEquals("MockApiClient 不应被调用", 0, mockApi.getCallCount());
    }

    // -------------------------------------------------------------------------
    // 测试 2：超过阈值时触发压缩，结构正确，CompactionUsage 字段验证
    // -------------------------------------------------------------------------

    @Test
    public void testCompactionWhenAboveThreshold() {
        Session session = new Session();
        fillSession(session, 15);  // 30 条消息 > KEEP_RECENT_MESSAGES(20)

        MockApiClient mockApi = new MockApiClient()
                .thenText("Summary: user asked to do X, assistant completed step A and B.");

        Compactor compactor = new Compactor(mockApi);
        Optional<CompactionUsage> result = compactor.compact(session, List.of("you are a helpful assistant"));

        assertTrue("应触发压缩", result.isPresent());
        assertEquals("压缩次数应为 1", 1, session.getCompactionCount());
        assertEquals("摘要 API 应被调用一次", 1, mockApi.getCallCount());

        // CompactionUsage 字段校验
        CompactionUsage cu = result.get();
        assertEquals("compactionIndex 应为 1（第一次压缩）", 1, cu.compactionIndex());
        assertTrue("messagesRemoved 应 > 0", cu.messagesRemoved() > 0);
        assertTrue("messagesKept 应 > 0", cu.messagesKept() > 0);
        assertFalse("非降级路径 fellBack 应为 false", cu.fellBack());
        assertNotNull("startedAt 不应为 null", cu.startedAt());
        assertNotNull("finishedAt 不应为 null", cu.finishedAt());
        assertNotNull("duration 不应为 null", cu.duration());

        List<Message> messages = session.getMessages();

        // 第一条消息必须是 COMPACTION_SUMMARY 类型
        Message firstMsg = messages.get(0);
        assertEquals("第一条消息必须是摘要", MessageType.COMPACTION_SUMMARY, firstMsg.getType());

        // 摘要消息内容包含 XML 标签
        String summaryText = firstMsg.getBlocks().get(0).getText();
        assertTrue("摘要应包含 XML 标签", summaryText.contains("<previous_conversation_summary>"));
        assertTrue("摘要内容应包含 LLM 返回的文本",
                summaryText.contains("Summary: user asked to do X"));

        // 压缩后总消息数 <= KEEP_RECENT_MESSAGES + 1（摘要）
        assertTrue("压缩后消息数应不超过阈值 + 1",
                messages.size() <= Compactor.KEEP_RECENT_MESSAGES + 1);
    }

    // -------------------------------------------------------------------------
    // 测试 3：Rolling Summary —— 再次触发时旧摘要被合并进新摘要
    // -------------------------------------------------------------------------

    @Test
    public void testRollingCompaction() {
        Session session = new Session();

        // 模拟"第一次压缩后"的状态：session 首条是旧摘要 + 近期消息
        session.addMessage(Message.compactionSummary("Old summary: completed task A."));
        fillSession(session, 12);  // 再加 24 条，总计 25 条 > 20

        // 第二次压缩时，LLM 看到旧摘要 + 新消息，生成综合新摘要
        MockApiClient mockApi = new MockApiClient()
                .thenText("New comprehensive summary: completed A and B, currently working on C.");

        Compactor compactor = new Compactor(mockApi);
        Optional<CompactionUsage> result = compactor.compact(session, List.of());

        assertTrue("应触发第二次压缩", result.isPresent());
        assertEquals("压缩次数应为 1（本次）", 1, session.getCompactionCount());

        List<Message> messages = session.getMessages();

        // 仍然只有一条摘要，在第一位
        long summaryCount = messages.stream()
                .filter(Message::isCompactionSummary)
                .count();
        assertEquals("任意时刻只能有一条摘要消息", 1, summaryCount);
        assertTrue("摘要消息必须是第一条", messages.get(0).isCompactionSummary());

        // 新摘要内容来自第二次 LLM 调用
        String newSummary = messages.get(0).getBlocks().get(0).getText();
        assertTrue("新摘要应整合新内容", newSummary.contains("completed A and B"));
    }

    // -------------------------------------------------------------------------
    // 测试 4：摘要 API 失败时降级为截断，fellBack=true
    // -------------------------------------------------------------------------

    @Test
    public void testFallbackOnSummaryFailure() {
        Session session = new Session();
        fillSession(session, 15);  // 30 条消息

        // MockApiClient 没有配置响应 → stream() 会抛 RuntimeException
        MockApiClient mockApi = new MockApiClient();
        Compactor compactor = new Compactor(mockApi);

        // 不应该抛异常
        Optional<CompactionUsage> result = compactor.compact(session, List.of());

        assertTrue("即使摘要失败，压缩仍应执行（降级截断）", result.isPresent());

        CompactionUsage cu = result.get();
        assertTrue("降级路径 fellBack 应为 true", cu.fellBack());

        Message firstMsg = session.getMessages().get(0);
        assertEquals("降级后第一条仍是摘要类型", MessageType.COMPACTION_SUMMARY, firstMsg.getType());
        assertTrue("降级摘要应含截断提示",
                firstMsg.getBlocks().get(0).getText().contains("truncated"));
    }
}
