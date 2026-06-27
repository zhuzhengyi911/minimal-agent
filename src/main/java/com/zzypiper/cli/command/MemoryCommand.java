package com.zzypiper.cli.command;

import com.zzypiper.cli.CommandContext;
import com.zzypiper.cli.CommandResult;
import com.zzypiper.cli.SlashCommand;
import com.zzypiper.memory.MemoryLoader;

import java.util.List;

public class MemoryCommand implements SlashCommand {

    @Override
    public String name() { return "memory"; }

    @Override
    public String description() { return "Show memory index (or 'reload' to force refresh)"; }

    @Override
    public String usage() { return "/memory [reload]"; }

    @Override
    public CommandResult run(List<String> args, CommandContext ctx) {
        MemoryLoader loader = ctx.memoryLoader();
        if (loader == null) {
            ctx.renderer().printError("Memory loader not available.");
            return CommandResult.CONTINUE;
        }

        if (!args.isEmpty() && "reload".equalsIgnoreCase(args.get(0))) {
            loader.invalidate();
            System.out.println("Memory cache invalidated. Next load will re-read disk.");
            return CommandResult.CONTINUE;
        }

        String content = loader.load();
        if (content == null || content.isBlank()) {
            System.out.println("(no memory entries)");
        } else {
            System.out.println("── " + MemoryLoader.MEMORY_DIR + "/" + MemoryLoader.INDEX_FILE + " ──");
            System.out.println(content);
        }
        return CommandResult.CONTINUE;
    }
}
