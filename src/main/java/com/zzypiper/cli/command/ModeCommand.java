package com.zzypiper.cli.command;

import com.zzypiper.cli.CommandContext;
import com.zzypiper.cli.CommandResult;
import com.zzypiper.cli.SlashCommand;
import com.zzypiper.permission.ModeEnum;

import java.util.List;

public class ModeCommand implements SlashCommand {

    @Override
    public String name() { return "mode"; }

    @Override
    public String description() { return "Show or change permission mode"; }

    @Override
    public String usage() { return "/mode [read_only|workspace_write|danger_full_access]"; }

    @Override
    public CommandResult run(List<String> args, CommandContext ctx) {
        if (args.isEmpty()) {
            ModeEnum current = ctx.permissionPolicy().getMode();
            System.out.println("Current mode: " + current.name().toLowerCase());
            System.out.println();
            System.out.println("  read_only           Read-only operations only"
                    + (current == ModeEnum.READ_ONLY ? "  ←" : ""));
            System.out.println("  workspace_write     Write to project files"
                    + (current == ModeEnum.WORKSPACE_WRITE ? "  ←" : ""));
            System.out.println("  danger_full_access  Full access including system commands"
                    + (current == ModeEnum.DANGER_FULL_ACCESS ? "  ←" : ""));
            System.out.println();
            return CommandResult.CONTINUE;
        }

        ModeEnum newMode = parseMode(args.get(0));
        if (newMode == null) {
            ctx.renderer().printError("Unknown mode: " + args.get(0)
                    + ". Use: read_only, workspace_write, danger_full_access");
            return CommandResult.CONTINUE;
        }

        ModeEnum old = ctx.permissionPolicy().getMode();
        ctx.permissionPolicy().setMode(newMode);
        System.out.println("Mode changed: " + old.name().toLowerCase()
                + " → " + newMode.name().toLowerCase());
        return CommandResult.CONTINUE;
    }

    private ModeEnum parseMode(String s) {
        return switch (s.toLowerCase()) {
            case "read_only", "readonly", "ro" -> ModeEnum.READ_ONLY;
            case "workspace_write", "write", "rw" -> ModeEnum.WORKSPACE_WRITE;
            case "danger_full_access", "danger", "full" -> ModeEnum.DANGER_FULL_ACCESS;
            default -> null;
        };
    }
}
