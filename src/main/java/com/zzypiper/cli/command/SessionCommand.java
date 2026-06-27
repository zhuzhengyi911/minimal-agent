package com.zzypiper.cli.command;

import com.zzypiper.api.TokenUsage;
import com.zzypiper.cli.CommandContext;
import com.zzypiper.cli.CommandResult;
import com.zzypiper.cli.SlashCommand;
import com.zzypiper.session.Message;
import com.zzypiper.session.RoleEnum;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

public class SessionCommand implements SlashCommand {

    private static final DateTimeFormatter FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    @Override
    public String name() { return "session"; }

    @Override
    public String description() { return "Show session statistics (turns, messages, cost, duration)"; }

    @Override
    public CommandResult run(List<String> args, CommandContext ctx) {
        List<Message> messages = ctx.session().getMessages();
        long userCount = messages.stream().filter(m -> m.getRole() == RoleEnum.USER).count();
        long assistantCount = messages.stream().filter(m -> m.getRole() == RoleEnum.ASSISTANT).count();
        long toolCount = messages.stream().filter(m -> m.getRole() == RoleEnum.TOOL).count();

        TokenUsage cum = ctx.usageTracker().cumulative();
        int turns = ctx.usageTracker().turns().size();
        Instant started = ctx.session().getStartedAt();
        Duration duration = Duration.between(started, Instant.now());

        long h = duration.toHours();
        long m = duration.toMinutesPart();
        long s = duration.toSecondsPart();

        System.out.println();
        System.out.printf("  Turns:       %d%n", turns);
        System.out.printf("  Messages:    %d  (user: %d, assistant: %d, tool: %d)%n",
                messages.size(), userCount, assistantCount, toolCount);
        System.out.printf("  Compactions: %d%n", ctx.session().getCompactionCount());
        System.out.printf("  Total tokens: ↑%,d  ↓%,d%n",
                cum.inputTokens(), cum.outputTokens());
        System.out.printf("  Started:     %s%n", FMT.format(started));
        System.out.printf("  Duration:    %02d:%02d:%02d%n", h, m, s);
        System.out.println();
        return CommandResult.CONTINUE;
    }
}
