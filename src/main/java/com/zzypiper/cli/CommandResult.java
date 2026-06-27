package com.zzypiper.cli;

/** 斜杠命令执行后的返回指令。 */
public enum CommandResult {
    /** 继续 REPL 循环。 */
    CONTINUE,
    /** 退出程序。 */
    QUIT
}
