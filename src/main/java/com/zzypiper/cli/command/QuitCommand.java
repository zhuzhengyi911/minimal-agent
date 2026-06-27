package com.zzypiper.cli.command;

import com.zzypiper.api.TokenUsage;
import com.zzypiper.cli.CommandContext;
import com.zzypiper.cli.CommandResult;
import com.zzypiper.cli.SlashCommand;

import java.util.List;

public class QuitCommand implements SlashCommand {

    @Override
    public String name() { return "quit"; }

    @Override
    public List<String> aliases() { return List.of("exit"); }

    @Override
    public String description() { return "Exit the REPL and print session token summary"; }

    @Override
    public CommandResult run(List<String> args, CommandContext ctx) {
        TokenUsage cum = ctx.usageTracker().cumulative();
        System.out.printf("Goodbye. Session total: ↑%d ↓%d%n",
                cum.inputTokens(), cum.outputTokens());
        return CommandResult.QUIT;
    }
}
