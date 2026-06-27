package com.zzypiper.cli;

import com.zzypiper.api.ModelConfig;
import com.zzypiper.api.TokenUsage;
import com.zzypiper.api.TurnUsage;
import com.zzypiper.api.UsageTracker;
import com.zzypiper.session.ContentBlock;
import com.zzypiper.session.KindEnum;
import com.zzypiper.session.Message;
import com.zzypiper.session.TurnSummary;

import java.util.List;

/**
 * 终端输出渲染器，集中管理所有 CLI 输出格式。
 */
public class Renderer {

    private final boolean verbose;

    public Renderer(boolean verbose) {
        this.verbose = verbose;
    }

    // -------------------------------------------------------------------------
    // 助手输出
    // -------------------------------------------------------------------------

    /**
     * 打印 TurnSummary 中所有助手消息的文字块，并在末尾输出 tool call 摘要（verbose 时展开）。
     */
    public void printTurn(TurnSummary summary, UsageTracker tracker) {
        // 1. 助手文字
        for (Message msg : summary.getAssistantMessages()) {
            for (ContentBlock block : msg.getBlocks()) {
                if (block.getKind() == KindEnum.TEXT && block.getText() != null) {
                    System.out.print(block.getText());
                }
            }
        }
        System.out.println();

        // 2. Tool call 摘要（verbose 时展开参数和结果）
        if (verbose) {
            for (Message msg : summary.getAssistantMessages()) {
                for (ContentBlock block : msg.getBlocks()) {
                    if (block.getKind() == KindEnum.TOOL_USE) {
                        System.out.println("  ◆ " + block.getToolName());
                        if (block.getToolInput() != null) {
                            System.out.println("    input: " + truncate(block.getToolInput(), 200));
                        }
                    }
                }
            }
            for (Message msg : summary.getToolResults()) {
                for (ContentBlock block : msg.getBlocks()) {
                    if (block.getKind() == KindEnum.TOOL_RESULT) {
                        String mark = block.isError() ? "  ✗" : "  ✓";
                        System.out.println(mark + " " + block.getToolName()
                                + ": " + summarizeOutput(block.getToolOutput()));
                    }
                }
            }
        } else {
            // 默认模式：一行列出调用的工具
            List<String> toolNames = summary.getAssistantMessages().stream()
                    .flatMap(m -> m.getBlocks().stream())
                    .filter(b -> b.getKind() == KindEnum.TOOL_USE && b.getToolName() != null)
                    .map(ContentBlock::getToolName)
                    .toList();
            if (!toolNames.isEmpty()) {
                System.out.println("  ◆ " + String.join(", ", toolNames));
            }
        }

        // 3. Token 摘要
        TurnUsage latest = tracker.latestTurn();
        if (latest != null) {
            TokenUsage actual = latest.actual();
            ModelConfig model = null; // cost 计算需要 ModelConfig，此处跳过精确 cost
            String costStr = "";
            System.out.printf("[↑%d ↓%d cache-read %d%s]%n",
                    actual.inputTokens(),
                    actual.outputTokens(),
                    actual.cacheReadInputTokens(),
                    costStr);
        }
    }

    // -------------------------------------------------------------------------
    // 工具调用渲染（给 CompactCommand 等使用）
    // -------------------------------------------------------------------------

    public void printToolCall(String toolName, String inputPreview) {
        if (verbose) {
            System.out.println("  ◆ " + toolName);
            System.out.println("    input: " + truncate(inputPreview, 200));
        } else {
            System.out.println("  ◆ " + toolName + "  " + truncate(inputPreview, 60));
        }
    }

    public void printToolResult(String result, boolean error) {
        String mark = error ? "  ✗" : "  ✓";
        System.out.println(mark + " " + summarizeOutput(result));
    }

    // -------------------------------------------------------------------------
    // 通用打印
    // -------------------------------------------------------------------------

    public void printInfo(String msg) {
        System.out.println(msg);
    }

    public void printError(String msg) {
        System.err.println("! " + msg);
    }

    /**
     * 打印简单对齐的两列表格。
     *
     * @param col1Width 第一列宽度（空格填充）
     * @param rows      每行 [col1, col2] 的字符串数组
     */
    public void printTable(int col1Width, List<String[]> rows) {
        String fmt = "  %-" + col1Width + "s  %s%n";
        for (String[] row : rows) {
            System.out.printf(fmt, row[0], row.length > 1 ? row[1] : "");
        }
    }

    // -------------------------------------------------------------------------
    // 工具方法
    // -------------------------------------------------------------------------

    private String truncate(String s, int max) {
        if (s == null) return "";
        s = s.replace('\n', ' ').replace('\r', ' ');
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    private String summarizeOutput(String output) {
        if (output == null || output.isEmpty()) return "(empty)";
        int lines = output.split("\n", -1).length;
        if (lines > 3) return lines + " lines";
        return truncate(output, 120);
    }
}
