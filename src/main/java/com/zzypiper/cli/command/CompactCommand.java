package com.zzypiper.cli.command;

import com.zzypiper.cli.CommandContext;
import com.zzypiper.cli.CommandResult;
import com.zzypiper.cli.SlashCommand;
import com.zzypiper.compaction.CompactionUsage;
import com.zzypiper.compaction.Compactor;

import java.util.List;
import java.util.Optional;

public class CompactCommand implements SlashCommand {

    @Override
    public String name() { return "compact"; }

    @Override
    public String description() { return "Force context compaction (summarize conversation history)"; }

    @Override
    public CommandResult run(List<String> args, CommandContext ctx) {
        int before = ctx.session().size();
        System.out.println("Compacting " + before + " messages…");

        Compactor compactor = new Compactor(ctx.agent().getApiClient());
        Optional<CompactionUsage> result = compactor.compact(
                ctx.session(),
                ctx.agent().getSystemPrompt()
        );

        if (result.isEmpty()) {
            System.out.println("Nothing to compact (session too small).");
        } else {
            CompactionUsage cu = result.get();
            ctx.usageTracker().recordCompaction(cu);
            System.out.printf("Done. %d messages → %d messages. (%s)%n",
                    before,
                    ctx.session().size(),
                    cu.fellBack() ? "fallback summary" : "summarized"
            );
        }
        return CommandResult.CONTINUE;
    }
}
