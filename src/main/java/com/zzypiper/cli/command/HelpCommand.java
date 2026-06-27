package com.zzypiper.cli.command;

import com.zzypiper.cli.CommandContext;
import com.zzypiper.cli.CommandRegistry;
import com.zzypiper.cli.CommandResult;
import com.zzypiper.cli.SlashCommand;

import java.util.List;

public class HelpCommand implements SlashCommand {

    private final CommandRegistry registry;

    public HelpCommand(CommandRegistry registry) {
        this.registry = registry;
    }

    @Override
    public String name() { return "help"; }

    @Override
    public String description() { return "Show available commands (or usage for a specific command)"; }

    @Override
    public String usage() { return "/help [command]"; }

    @Override
    public CommandResult run(List<String> args, CommandContext ctx) {
        if (!args.isEmpty()) {
            // /help <cmd> — 显示单条命令的 usage
            String target = args.get(0).toLowerCase();
            SlashCommand cmd = registry.allCommands().stream()
                    .filter(c -> c.name().equals(target) || c.aliases().contains(target))
                    .findFirst().orElse(null);
            if (cmd == null) {
                ctx.renderer().printError("Unknown command: " + target);
            } else {
                System.out.println("  " + cmd.usage());
                System.out.println("  " + cmd.description());
            }
            return CommandResult.CONTINUE;
        }

        // /help — 列出全部命令
        System.out.println("Available commands:");
        System.out.println();
        int nameWidth = registry.allCommands().stream()
                .mapToInt(c -> c.name().length()).max().orElse(10);
        for (SlashCommand cmd : registry.allCommands()) {
            System.out.printf("  /%-" + nameWidth + "s  %s%n", cmd.name(), cmd.description());
        }
        System.out.println();
        System.out.println("  !<cmd>   Run a shell command directly (e.g. ! ls -la)");
        return CommandResult.CONTINUE;
    }
}
