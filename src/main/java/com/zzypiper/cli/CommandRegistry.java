package com.zzypiper.cli;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 斜杠命令注册表，按名称（含别名）索引并 dispatch 用户输入。
 */
public class CommandRegistry {

    /** 保留插入顺序，用于 /help 展示。 */
    private final Map<String, SlashCommand> byName = new LinkedHashMap<>();

    public CommandRegistry register(SlashCommand cmd) {
        byName.put(cmd.name(), cmd);
        for (String alias : cmd.aliases()) {
            byName.put(alias, cmd);
        }
        return this;
    }

    /**
     * 解析并执行一行以 "/" 开头的输入。
     *
     * @param line 包含 "/" 前缀的完整行，如 "/mode read_only"
     * @param ctx  当前上下文
     * @return CONTINUE 或 QUIT
     */
    public CommandResult dispatch(String line, CommandContext ctx) {
        // 去掉开头的 /
        String body = line.substring(1).strip();
        String[] parts = body.split("\\s+", 2);
        String name = parts[0].toLowerCase();
        List<String> args = parts.length > 1
                ? List.of(parts[1].split("\\s+"))
                : List.of();

        SlashCommand cmd = byName.get(name);
        if (cmd == null) {
            ctx.renderer().printError("Unknown command: /" + name + " — try /help");
            return CommandResult.CONTINUE;
        }
        return cmd.run(args, ctx);
    }

    /** 返回所有主命令（不含别名），按注册顺序排列。 */
    public List<SlashCommand> allCommands() {
        List<SlashCommand> seen = new ArrayList<>();
        for (SlashCommand cmd : byName.values()) {
            if (!seen.contains(cmd)) seen.add(cmd);
        }
        return seen;
    }
}
