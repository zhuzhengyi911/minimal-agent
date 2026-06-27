package com.zzypiper.cli.command;

import com.zzypiper.api.ModelConfig;
import com.zzypiper.api.TokenUsage;
import com.zzypiper.api.UsageTracker;
import com.zzypiper.cli.CommandContext;
import com.zzypiper.cli.CommandResult;
import com.zzypiper.cli.SlashCommand;
import com.zzypiper.session.Session;

import java.util.List;

public class StatusCommand implements SlashCommand {

    @Override
    public String name() { return "status"; }

    @Override
    public String description() { return "Show model, permission mode, token usage and context size"; }

    @Override
    public CommandResult run(List<String> args, CommandContext ctx) {
        ModelConfig model = ctx.agent().getApiClient().getModelConfig();
        String mode = ctx.permissionPolicy().getMode().name().toLowerCase();
        Session session = ctx.session();
        UsageTracker tracker = ctx.usageTracker();

        // 估算当前上下文 token 数
        int estimated = UsageTracker.estimateInputTokens(
                ctx.agent().getSystemPrompt(),
                session.getMessages(),
                ctx.toolRegistry().getDefinitions(ctx.permissionPolicy().getMode())
        );
        double contextPct = model.contextWindow() > 0
                ? estimated * 100.0 / model.contextWindow() : 0;

        TokenUsage cum = tracker.cumulative();
        int turns = tracker.turns().size();

        System.out.println();
        System.out.printf("  Model:    %s%n", model.modelId());
        System.out.printf("  Mode:     %s%n", mode);
        System.out.printf("  Context:  ~%,d / %,d tokens (%.1f%%)%n",
                estimated, model.contextWindow(), contextPct);
        System.out.printf("  Session:  %d turns · %d messages · %d compactions%n",
                turns, session.size(), session.getCompactionCount());
        System.out.printf("  Usage:    ↑%,d  ↓%,d  cache-read %,d%n",
                cum.inputTokens(), cum.outputTokens(), cum.cacheReadInputTokens());
        System.out.println();
        return CommandResult.CONTINUE;
    }
}
