package com.zzypiper.session;

import lombok.Data;

import java.util.List;

@Data
public class TurnSummary {
    private final List<Message> assistantMessages;
    private final List<Message> toolResults;
    private final int iterations;
}
