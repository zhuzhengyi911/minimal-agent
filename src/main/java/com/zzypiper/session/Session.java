package com.zzypiper.session;

import com.zzypiper.compaction.Compactor;

import java.util.ArrayList;
import java.util.List;

public class Session {
    private final List<Message> messages = new ArrayList<>();
    private int compactionCount = 0;

    public void addMessage(Message message) {
        messages.add(message);
    }

    public List<Message> getMessages() {
        return messages;
    }

    public int size() {
        return messages.size();
    }

    /**
     * 用压缩后的消息列表替换全部消息，并记录压缩次数。
     * 仅供 {@link Compactor} 调用。
     */
    public void replaceMessages(List<Message> newMessages) {
        messages.clear();
        messages.addAll(newMessages);
        compactionCount++;
    }

    /** 返回本 Session 已执行的压缩次数。 */
    public int getCompactionCount() {
        return compactionCount;
    }
}
