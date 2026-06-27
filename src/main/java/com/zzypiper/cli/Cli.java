package com.zzypiper.cli;

import com.zzypiper.agent.Agent;
import com.zzypiper.boot.AgentBootstrap;
import com.zzypiper.cli.command.ClearCommand;
import com.zzypiper.cli.command.CompactCommand;
import com.zzypiper.cli.command.HelpCommand;
import com.zzypiper.cli.command.MemoryCommand;
import com.zzypiper.cli.command.ModeCommand;
import com.zzypiper.cli.command.ModelCommand;
import com.zzypiper.cli.command.QuitCommand;
import com.zzypiper.cli.command.SessionCommand;
import com.zzypiper.cli.command.SettingsCommand;
import com.zzypiper.cli.command.SkillsCommand;
import com.zzypiper.cli.command.StatusCommand;
import com.zzypiper.cli.command.ToolsCommand;

import java.nio.file.Path;

/**
 * 命令行入口——负责解析参数、引导两种运行模式（交互 REPL / 非交互 -p）。
 * 自身不关心组件如何构建，通过 {@link AgentBootstrap} 获取组装好的 Agent。
 */
public class Cli {

    private static final String VERSION = "0.1.0";

    public static void main(String[] args) {
        // --help / --version 无需初始化 Agent，快速退出
        for (String arg : args) {
            if ("--help".equals(arg) || "-h".equals(arg)) {
                printHelp();
                return;
            }
            if ("--version".equals(arg)) {
                System.out.println("minimal-agent " + VERSION);
                return;
            }
        }

        StartupArgs startupArgs = StartupArgs.parse(args);
        Path workDir = startupArgs.workDir().orElse(Path.of(System.getProperty("user.dir")));

        AgentBootstrap.BuildResult buildResult = AgentBootstrap.build(workDir);
        Agent agent = buildResult.buildAgent();

        // --mode 覆盖默认权限
        startupArgs.modeOverride().ifPresent(m -> agent.getPermissionPolicy().setMode(m));

        Renderer renderer = new Renderer(startupArgs.verbose());

        // 非交互模式
        if (startupArgs.printPrompt().isPresent()) {
            new PrintRunner(agent).run(startupArgs.printPrompt().get());
            buildResult.shutdown();
            return;
        }

        // 交互 REPL 模式
        CommandContext ctx = new CommandContext(agent, buildResult.settings(), startupArgs, renderer, buildResult);
        CommandRegistry registry = buildRegistry(ctx);

        Runtime.getRuntime().addShutdownHook(new Thread(buildResult::shutdown));

        new ReplLoop(ctx, registry).start();
    }

    // -------------------------------------------------------------------------
    // 命令注册
    // -------------------------------------------------------------------------

    private static CommandRegistry buildRegistry(CommandContext ctx) {
        CommandRegistry registry = new CommandRegistry();
        registry.register(new HelpCommand(registry));
        registry.register(new ClearCommand());
        registry.register(new CompactCommand());
        registry.register(new StatusCommand());
        registry.register(new MemoryCommand());
        registry.register(new SkillsCommand());
        registry.register(new ToolsCommand());
        registry.register(new ModeCommand());
        registry.register(new ModelCommand());
        registry.register(new SessionCommand());
        registry.register(new SettingsCommand());
        registry.register(new QuitCommand());
        return registry;
    }

    private static void printHelp() {
        System.out.println("Usage: java -jar agent.jar [OPTIONS] [PROMPT]");
        System.out.println();
        System.out.println("  PROMPT              直接传入 prompt（等价于 -p）");
        System.out.println("  -p, --print PROMPT  非交互模式：执行单轮后打印结果并退出");
        System.out.println("  -v, --verbose       显示完整 tool 调用参数和结果");
        System.out.println("      --model ID      覆盖模型 ID");
        System.out.println("      --mode LEVEL    权限模式 (read_only|workspace_write|danger_full_access)");
        System.out.println("      --dir PATH      工作目录（默认当前目录）");
        System.out.println("      --no-mcp        跳过 MCP server 初始化");
        System.out.println("      --version       打印版本后退出");
        System.out.println("      --help          打印此帮助后退出");
    }
}
