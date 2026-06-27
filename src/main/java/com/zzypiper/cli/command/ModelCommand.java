package com.zzypiper.cli.command;

import com.zzypiper.api.ModelConfig;
import com.zzypiper.cli.CommandContext;
import com.zzypiper.cli.CommandResult;
import com.zzypiper.cli.SlashCommand;

import java.util.List;

public class ModelCommand implements SlashCommand {

    @Override
    public String name() { return "model"; }

    @Override
    public String description() { return "Show current model configuration"; }

    @Override
    public CommandResult run(List<String> args, CommandContext ctx) {
        ModelConfig model = ctx.agent().getApiClient().getModelConfig();
        System.out.println();
        System.out.printf("  Model:          %s%n", model.modelId());
        System.out.printf("  Context window: %,d tokens%n", model.contextWindow());
        System.out.printf("  Max output:     %,d tokens%n", model.maxOutputTokens());
        System.out.printf("  Input price:    $%.2f / M tokens%n", model.inputPricePerMToken());
        System.out.printf("  Output price:   $%.2f / M tokens%n", model.outputPricePerMToken());
        if (model.cacheWritePricePerMToken() > 0) {
            System.out.printf("  Cache write:    $%.2f / M tokens%n", model.cacheWritePricePerMToken());
            System.out.printf("  Cache read:     $%.2f / M tokens%n", model.cacheReadPricePerMToken());
        }
        System.out.println();

        if (!args.isEmpty()) {
            System.out.println("  Note: changing model requires restart (--model flag).");
        }
        return CommandResult.CONTINUE;
    }
}
