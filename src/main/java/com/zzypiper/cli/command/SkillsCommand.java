package com.zzypiper.cli.command;

import com.zzypiper.cli.CommandContext;
import com.zzypiper.cli.CommandResult;
import com.zzypiper.cli.SlashCommand;
import com.zzypiper.skill.SkillLoader;
import com.zzypiper.skill.SkillSummary;

import java.util.List;
import java.util.Map;
import java.util.Set;

public class SkillsCommand implements SlashCommand {

    @Override
    public String name() { return "skills"; }

    @Override
    public String description() { return "List available skills (● loaded into context, ○ available)"; }

    @Override
    public CommandResult run(List<String> args, CommandContext ctx) {
        SkillLoader loader = ctx.skillLoader();
        if (loader == null) {
            ctx.renderer().printError("Skill loader not available.");
            return CommandResult.CONTINUE;
        }

        List<SkillSummary> summaries = loader.getSummaries();
        Map<String, String> loaded = loader.getLoadedContents();
        Set<String> loadedNames = loaded.keySet();

        List<SkillSummary> loadedList = summaries.stream()
                .filter(s -> loadedNames.contains(s.name())).toList();
        List<SkillSummary> availableList = summaries.stream()
                .filter(s -> !loadedNames.contains(s.name())).toList();

        int nameWidth = summaries.stream().mapToInt(s -> s.name().length()).max().orElse(10);
        String fmt = "  %s %-" + nameWidth + "s  %s%n";

        if (!loadedList.isEmpty()) {
            System.out.println("Loaded skills (" + loadedList.size() + "):");
            for (SkillSummary s : loadedList) {
                System.out.printf(fmt, "●", s.name(), s.description());
            }
            System.out.println();
        }

        if (!availableList.isEmpty()) {
            System.out.println("Available skills (" + availableList.size() + "):");
            for (SkillSummary s : availableList) {
                System.out.printf(fmt, "○", s.name(), s.description());
            }
            System.out.println();
        }

        if (summaries.isEmpty()) {
            System.out.println("(no skills found in " + SkillLoader.SKILLS_DIR + ")");
        }
        return CommandResult.CONTINUE;
    }
}
