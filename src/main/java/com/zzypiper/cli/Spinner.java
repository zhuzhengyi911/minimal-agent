package com.zzypiper.cli;

/**
 * 终端动态 spinner，在阻塞操作期间给用户即时反馈。
 *
 * <p>用法：
 * <pre>
 *   Spinner spinner = new Spinner("Thinking");
 *   spinner.start();
 *   try {
 *       // 长耗时操作
 *   } finally {
 *       spinner.stop();
 *   }
 * </pre>
 */
public class Spinner {

    private static final String[] FRAMES = {"⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏"};
    private static final int INTERVAL_MS = 80;

    private final String message;
    private Thread thread;

    public Spinner(String message) {
        this.message = message;
    }

    public void start() {
        thread = new Thread(() -> {
            int i = 0;
            while (!Thread.currentThread().isInterrupted()) {
                System.out.print("\r" + FRAMES[i++ % FRAMES.length] + " " + message + "…   ");
                System.out.flush();
                try {
                    Thread.sleep(INTERVAL_MS);
                } catch (InterruptedException e) {
                    break;
                }
            }
            // 清除 spinner 行
            System.out.print("\r" + " ".repeat(message.length() + 8) + "\r");
            System.out.flush();
        });
        thread.setDaemon(true);
        thread.start();
    }

    public void stop() {
        if (thread != null) {
            thread.interrupt();
            try {
                thread.join(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
