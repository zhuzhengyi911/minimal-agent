package com.zzypiper.cli;

import java.io.IOException;
import java.util.List;

/**
 * 处理 {@code !cmd} 输入——直接执行 shell 命令，stdout/stderr 继承到终端。
 */
public class ShellPassthrough {

    private ShellPassthrough() {}

    /**
     * @param line 用户输入的完整行，以 "!" 开头（含 "!"）
     */
    public static void run(String line) {
        String cmd = line.substring(1).strip();
        if (cmd.isEmpty()) {
            System.err.println("! empty command");
            return;
        }

        try {
            Process process = new ProcessBuilder(List.of("sh", "-c", cmd))
                    .inheritIO()
                    .start();
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                System.out.printf("Exit code: %d (failed)%n", exitCode);
            }
        } catch (IOException | InterruptedException e) {
            System.err.println("! shell error: " + e.getMessage());
            Thread.currentThread().interrupt();
        }
    }
}
