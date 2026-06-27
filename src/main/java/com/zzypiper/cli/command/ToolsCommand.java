package com.zzypiper.cli.command;

import com.zzypiper.cli.CommandContext;
import com.zzypiper.cli.CommandResult;
import com.zzypiper.cli.SlashCommand;
import com.zzypiper.tool.ToolSpec;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class ToolsCommand implements SlashCommand {

    private static final String MCP_PREFIX = "mcp__";

    @Override
    public String name() { return "tools"; }

    @Override
    public String description() { return "List all registered tools grouped by source"; }

    @Override
    public CommandResult run(List<String> args, CommandContext ctx) {
        Map<String, ToolSpec> specs = ctx.toolRegistry().getSpecs();

        List<ToolSpec> builtin = new ArrayList<>();
        List<ToolSpec> mcp = new ArrayList<>();

        for (ToolSpec spec : specs.values()) {
            if (spec.name().startsWith(MCP_PREFIX)) {
                mcp.add(spec);
            } else {
                builtin.add(spec);
            }
        }

        int nameWidth = specs.values().stream()
                .mapToInt(s -> s.name().length()).max().orElse(20);
        String fmt = "  %-" + nameWidth + "s  %-50s  [%s]%n";

        if (!builtin.isEmpty()) {
            System.out.println("Built-in tools (" + builtin.size() + "):");
            for (ToolSpec s : builtin) {
                System.out.printf(fmt,
                        s.name(),
                        truncate(s.description(), 50),
                        s.requiredMode().name().toLowerCase());
            }
            System.out.println();
        }

        if (!mcp.isEmpty()) {
            System.out.println("MCP tools (" + mcp.size() + "):");
            for (ToolSpec s : mcp) {
                System.out.printf(fmt,
                        s.name(),
                        truncate(s.description(), 50),
                        s.requiredMode().name().toLowerCase());
            }
            System.out.println();
        }

        if (specs.isEmpty()) {
            System.out.println("(no tools registered)");
        }
        return CommandResult.CONTINUE;
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
