package com.zzypiper.cli;

import com.zzypiper.permission.ModeEnum;

import java.nio.file.Path;
import java.util.Optional;

/**
 * 解析后的启动参数。
 *
 * <pre>
 * Usage: java -jar agent.jar [OPTIONS] [PROMPT]
 *
 *   PROMPT              直接传入 prompt（等价于 -p）
 *   -p, --print PROMPT  非交互模式：执行单轮后打印结果并退出
 *   -v, --verbose       详细模式：显示完整 tool 调用参数和结果
 *       --model ID      覆盖模型 ID
 *       --mode LEVEL    权限模式：read_only | workspace_write | danger_full_access
 *       --dir PATH      工作目录（默认当前目录）
 *       --no-mcp        跳过 MCP server 初始化
 *       --version       打印版本后退出
 *       --help          打印帮助后退出
 * </pre>
 */
public record StartupArgs(
        Optional<String> printPrompt,
        boolean verbose,
        Optional<String> modelOverride,
        Optional<ModeEnum> modeOverride,
        Optional<Path> workDir,
        boolean noMcp
) {

    /** 解析 main() 的 args 数组，未知参数忽略。 */
    public static StartupArgs parse(String[] args) {
        Optional<String> printPrompt = Optional.empty();
        boolean verbose = false;
        Optional<String> modelOverride = Optional.empty();
        Optional<ModeEnum> modeOverride = Optional.empty();
        Optional<Path> workDir = Optional.empty();
        boolean noMcp = false;
        String positional = null;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "-p", "--print" -> {
                    if (i + 1 < args.length) printPrompt = Optional.of(args[++i]);
                }
                case "-v", "--verbose" -> verbose = true;
                case "--model" -> {
                    if (i + 1 < args.length) modelOverride = Optional.of(args[++i]);
                }
                case "--mode" -> {
                    if (i + 1 < args.length) modeOverride = parseModeEnum(args[++i]);
                }
                case "--dir" -> {
                    if (i + 1 < args.length) workDir = Optional.of(Path.of(args[++i]));
                }
                case "--no-mcp" -> noMcp = true;
                default -> {
                    if (!args[i].startsWith("-") && positional == null) {
                        positional = args[i];
                    }
                }
            }
        }

        // 位置参数等价于 -p
        if (printPrompt.isEmpty() && positional != null) {
            printPrompt = Optional.of(positional);
        }

        return new StartupArgs(printPrompt, verbose, modelOverride, modeOverride, workDir, noMcp);
    }

    private static Optional<ModeEnum> parseModeEnum(String s) {
        return switch (s.toLowerCase()) {
            case "read_only", "readonly" -> Optional.of(ModeEnum.READ_ONLY);
            case "workspace_write", "write" -> Optional.of(ModeEnum.WORKSPACE_WRITE);
            case "danger_full_access", "danger", "full" -> Optional.of(ModeEnum.DANGER_FULL_ACCESS);
            default -> {
                System.err.println("Unknown mode: " + s + ". Using workspace_write.");
                yield Optional.empty();
            }
        };
    }
}
