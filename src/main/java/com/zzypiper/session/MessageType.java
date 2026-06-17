package com.zzypiper.session;

public enum MessageType {
    /** 普通对话消息。 */
    NORMAL,

    /**
     * 上下文压缩摘要消息。
     * 始终作为 Session 的第一条消息，代表被压缩的历史对话。
     * Rolling Summary 策略保证任意时刻此类消息最多只有一条。
     */
    COMPACTION_SUMMARY
}
