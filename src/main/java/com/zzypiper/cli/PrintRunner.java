package com.zzypiper.cli;

import com.zzypiper.agent.Agent;
import com.zzypiper.session.ContentBlock;
import com.zzypiper.session.KindEnum;
import com.zzypiper.session.Message;
import com.zzypiper.session.TurnSummary;

/**
 * 非交互模式（-p）：执行单轮 Agent，仅打印助手文字后退出。
 * 适合脚本/CI 集成。
 */
public class PrintRunner {

    private final Agent agent;

    public PrintRunner(Agent agent) {
        this.agent = agent;
    }

    /** 运行单轮，打印结果，以 System.exit() 终止进程。 */
    public void run(String prompt) {
        try {
            TurnSummary summary = agent.runTurn(prompt);
            for (Message msg : summary.getAssistantMessages()) {
                for (ContentBlock block : msg.getBlocks()) {
                    if (block.getKind() == KindEnum.TEXT && block.getText() != null) {
                        System.out.print(block.getText());
                    }
                }
            }
            System.out.println();
            System.exit(0);
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(1);
        }
    }
}
