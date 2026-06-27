package com.zzypiper.cli.command;

import com.zzypiper.boot.AgentSettings;
import com.zzypiper.cli.CommandContext;
import com.zzypiper.cli.CommandResult;
import com.zzypiper.cli.SlashCommand;

import java.util.List;

public class SettingsCommand implements SlashCommand {

    @Override
    public String name() { return "settings"; }

    @Override
    public String description() { return "Show current runtime settings (apiKey masked)"; }

    @Override
    public CommandResult run(List<String> args, CommandContext ctx) {
        AgentSettings s = ctx.settings();
        String mode = ctx.permissionPolicy().getMode().name().toLowerCase();
        boolean coordEnabled = ctx.buildResult().coordinatorConfig().enabled();

        System.out.println();
        System.out.printf("  api.provider:    %s%n",
                s != null && s.api() != null ? s.api().provider() : "minimax");
        System.out.printf("  api.model:       %s%n",
                s != null && s.api() != null ? s.api().model() : "(default)");
        System.out.printf("  api.maxTokens:   %d%n",
                s != null && s.api() != null ? s.api().maxTokens() : 4096);
        System.out.printf("  api.apiKey:      %s%n",
                maskKey(s != null && s.api() != null ? s.api().apiKey() : null));
        System.out.printf("  coordinator:     %s%n", coordEnabled ? "enabled" : "disabled");
        System.out.printf("  mode:            %s%n", mode);
        System.out.printf("  workDir:         %s%n",
                ctx.buildResult().memoryLoader().memoryDir().getParent().getParent());
        System.out.printf("  verbose:         %s%n", ctx.startupArgs().verbose());
        System.out.println();
        return CommandResult.CONTINUE;
    }

    private String maskKey(String key) {
        if (key == null || key.length() < 8) return "****";
        return key.substring(0, 3) + "****" + key.substring(key.length() - 4);
    }
}
