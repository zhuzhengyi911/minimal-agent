package com.zzypiper.cli;

import java.util.List;

/** 斜杠命令接口，每个 /xxx 命令实现此接口。 */
public interface SlashCommand {

    /** 命令主名称，不含 /，如 "clear"。 */
    String name();

    /** 别名列表，如 ["exit"]。默认无别名。 */
    default List<String> aliases() {
        return List.of();
    }

    /** 在 /help 中显示的一行简短描述。 */
    String description();

    /** 完整用法说明，/help <cmd> 时展示。默认为 "/" + name()。 */
    default String usage() {
        return "/" + name();
    }

    /**
     * 执行命令。
     *
     * @param args 命令名之后空格分割的参数列表（可为空）
     * @param ctx  当前 REPL 上下文
     * @return CONTINUE 继续循环，QUIT 退出程序
     */
    CommandResult run(List<String> args, CommandContext ctx);
}
