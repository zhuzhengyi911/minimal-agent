package com.zzypiper.cli.command;

import com.zzypiper.cli.CommandContext;
import com.zzypiper.cli.CommandResult;
import com.zzypiper.cli.SlashCommand;

import java.util.List;

public class ClearCommand implements SlashCommand {

    @Override
    public String name() { return "clear"; }

    @Override
    public String description() { return "Clear conversation history and reset token counters"; }

    @Override
    public CommandResult run(List<String> args, CommandContext ctx) {
        int removed = ctx.session().size();
        ctx.session().clear();
        ctx.usageTracker().reset();
        System.out.printf("Conversation cleared. (%d messages removed)%n", removed);
        return CommandResult.CONTINUE;
    }
}
