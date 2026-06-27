package com.zzypiper.cli;

import com.zzypiper.session.TurnSummary;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

/**
 * REPL 主循环：读取用户输入，分发到斜杠命令 / shell 直通 / Agent。
 *
 * <p>输入分类：
 * <ul>
 *   <li>{@code /cmd [args]} — 斜杠命令，dispatch 到 CommandRegistry</li>
 *   <li>{@code !shell cmd}  — shell 直通，ShellPassthrough 执行</li>
 *   <li>空行              — 忽略</li>
 *   <li>其他文本          — 传给 Agent.runTurn()</li>
 * </ul>
 *
 * <p>多行输入：行末加 {@code \} 表示续行，空行结束多行块。
 */
public class ReplLoop {

    private static final String PROMPT = "> ";

    private final CommandContext ctx;
    private final CommandRegistry registry;

    public ReplLoop(CommandContext ctx, CommandRegistry registry) {
        this.ctx = ctx;
        this.registry = registry;
    }

    public void start() {
        printWelcome();

        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
        boolean running = true;

        while (running) {
            System.out.print(PROMPT);
            System.out.flush();

            String line = readLine(reader);
            if (line == null) {
                // EOF (Ctrl-D)
                System.out.println();
                break;
            }

            line = line.strip();
            if (line.isEmpty()) continue;

            // 多行续行：以 \ 结尾
            if (line.endsWith("\\")) {
                line = readMultiLine(reader, line.substring(0, line.length() - 1));
                if (line == null) break;
            }

            if (line.startsWith("/")) {
                CommandResult result = registry.dispatch(line, ctx);
                if (result == CommandResult.QUIT) {
                    running = false;
                }
            } else if (line.startsWith("!")) {
                ShellPassthrough.run(line);
            } else {
                runAgentTurn(line);
            }
        }
    }

    private void runAgentTurn(String input) {
        Spinner spinner = new Spinner("Thinking");
        spinner.start();
        try {
            TurnSummary summary = ctx.agent().runTurn(input);
            spinner.stop();
            ctx.renderer().printTurn(summary, ctx.usageTracker());
        } catch (Exception e) {
            spinner.stop();
            ctx.renderer().printError("Agent error: " + e.getMessage());
        }
    }

    /** 读取多行输入（以 \ 续行，空行结束）。 */
    private String readMultiLine(BufferedReader reader, String firstPart) {
        StringBuilder sb = new StringBuilder(firstPart).append('\n');
        while (true) {
            System.out.print("... ");
            System.out.flush();
            String line = readLine(reader);
            if (line == null || line.strip().isEmpty()) break;
            if (line.endsWith("\\")) {
                sb.append(line, 0, line.length() - 1).append('\n');
            } else {
                sb.append(line).append('\n');
                break;
            }
        }
        return sb.toString().strip();
    }

    private String readLine(BufferedReader reader) {
        try {
            return reader.readLine();
        } catch (IOException e) {
            return null;
        }
    }

    private void printWelcome() {
        String model = ctx.agent().getApiClient().getModelConfig().modelId();
        String mode  = ctx.permissionPolicy().getMode().name().toLowerCase();
        System.out.println("minimal-agent  model=" + model + "  mode=" + mode);
        System.out.println("Type /help for commands, /quit to exit.");
        System.out.println();
    }
}
