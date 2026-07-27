        

          
package tannyjung.tanshugetrees_handcode.debug;

import tannyjung.tanshugetrees_core.Core;
import tannyjung.tanshugetrees_core.game.GameUtils;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;

/**
 * 轻量级看门狗：监控服务器主线程卡顿。
 *
 * 工作原理：
 * 1. Loops.tick() 每个 tick 调用 updateTickTime() 更新时间戳
 * 2. 独立守护线程每 5ms 检查一次，如果距离上次 tick 超过阈值则触发报告
 * 3. 报告输出到 latest.log（含完整堆栈和锁信息）
 * 4. 主线程恢复后，下一次 tick 会在聊天栏发送摘要（带点击复制到剪贴板）
 *
 * 配置项（在 config.txt 中）：
 * - watchdog_enabled：是否启用看门狗
 * - watchdog_threshold_ms：触发阈值（毫秒），默认 50
 */
public class Watchdog {

    // 最后一次 tick 的时间戳（纳秒）
    private static volatile long lastTickNanoTime = System.nanoTime();

    // 是否已武装（第一次 tick 后才开始监控，防止服务器启动期间误报）
    private static volatile boolean armed = false;

    // 待发送的聊天报告（主线程恢复后在 tick 中发送）
    private static volatile String pendingChatSummary = null;
    private static volatile String pendingClipboardContent = null;

    // 看门狗线程
    private static Thread watchdogThread = null;

    // 触发阈值（毫秒），由 Handcode.Config 读取
    public static long thresholdMs = 50;

    // 检查间隔（毫秒），看门狗线程的 sleep 间隔
    private static final long CHECK_INTERVAL_MS = 5;

    // 防止重复报告的最小间隔（毫秒），避免在持续卡顿时刷屏
    private static volatile long lastReportTime = 0;
    private static final long REPORT_COOLDOWN_MS = 500;

    /**
     * 启动看门狗守护线程。
     * 多次调用安全，线程已在运行时仅更新阈值。
     */
    public static void start () {
        if (watchdogThread != null && watchdogThread.isAlive()) {
            return;
        }

        watchdogThread = new Thread(() -> {
            Core.logger.info("[TST Watchdog] Watchdog daemon started. Threshold: {}ms", thresholdMs);

            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(CHECK_INTERVAL_MS);

                    // 等待第一次 tick 后才开始监控
                    if (!armed) {
                        continue;
                    }

                    long elapsedMs = (System.nanoTime() - lastTickNanoTime) / 1_000_000;

                    if (elapsedMs > thresholdMs) {
                        // 冷却检查，避免在持续卡顿时刷屏
                        long now = System.currentTimeMillis();
                        if (now - lastReportTime < REPORT_COOLDOWN_MS) {
                            continue;
                        }
                        lastReportTime = now;

                        reportStall(elapsedMs);
                    }
                } catch (InterruptedException e) {
                    break;
                } catch (Exception e) {
                    // 看门狗自身不能崩，吞掉所有异常但记录日志
                    try {
                        Core.logger.error("[TST Watchdog] Watchdog internal error", e);
                    } catch (Exception ignored) {
                        // 连 logger 都挂了，什么都不做
                    }
                }
            }

            Core.logger.info("[TST Watchdog] Watchdog daemon stopped.");
        }, "TST-Watchdog");

        watchdogThread.setDaemon(true);
        watchdogThread.start();
    }

    /**
     * 在每个服务器 tick 时调用，更新时间戳并武装看门狗。
     * 应在 Loops.tick() 开头调用。
     */
    public static void updateTickTime () {
        armed = true;
        lastTickNanoTime = System.nanoTime();
    }

    /**
     * 在每个服务器 tick 时调用，检查是否有待发送的看门狗报告。
     * 此方法在主线程上执行，安全地访问游戏 API。
     * 应在 Loops.tick() 中调用。
     */
    public static void checkPendingReport (ServerLevel level_server) {
        if (pendingChatSummary != null && level_server != null) {
            String chatSummary = pendingChatSummary;
            String clipboardContent = pendingClipboardContent;
            pendingChatSummary = null;
            pendingClipboardContent = null;

            sendChatReport(level_server, chatSummary, clipboardContent);
        }
    }

    /**
     * 抓取主线程堆栈和锁信息，输出完整报告到日志，并保存摘要供聊天栏发送。
     */
    private static void reportStall (long elapsedMs) {
        Thread serverThread = findServerThread();
        if (serverThread == null) {
            Core.logger.warn("[TST Watchdog] Server thread not found! Stalled for {}ms", elapsedMs);
            return;
        }

        ThreadMXBean bean = ManagementFactory.getThreadMXBean();
        ThreadInfo info = bean.getThreadInfo(serverThread.getId());

        // 构建完整报告（输出到日志）
        StringBuilder fullReport = new StringBuilder();
        fullReport.append("\n");
        fullReport.append("====================================================================================\n");
        fullReport.append("[TST WATCHDOG] Server thread stalled for ").append(elapsedMs).append("ms!\n");
        fullReport.append("Thread State: ").append(info.getThreadState()).append("\n");

        // 锁信息
        if (info.getLockInfo() != null) {
            fullReport.append("Waiting on lock: ").append(info.getLockInfo()).append("\n");
            if (info.getLockOwnerId() != -1) {
                fullReport.append("Lock held by: Thread[").append(info.getLockOwnerName())
                           .append("] (ID=").append(info.getLockOwnerId()).append(")\n");

                // 获取持锁线程的堆栈
                ThreadInfo lockOwnerInfo = bean.getThreadInfo(info.getLockOwnerId());
                if (lockOwnerInfo != null) {
                    fullReport.append("--- Lock Owner Stack Trace ---\n");
                    for (StackTraceElement ste : lockOwnerInfo.getStackTrace()) {
                        fullReport.append("\t").append(ste.toString()).append("\n");
                    }
                }
            } else {
                fullReport.append("(Lock not held by any thread)\n");
            }
        }

        // 主线程完整堆栈（直接从 Thread 对象获取，比 ThreadInfo 默认 8 层更完整）
        fullReport.append("--- Server Thread Stack Trace ---\n");
        StackTraceElement[] stack = serverThread.getStackTrace();
        for (StackTraceElement ste : stack) {
            fullReport.append("\t").append(ste.toString()).append("\n");
        }
        fullReport.append("====================================================================================\n");

        // 输出到日志
        Core.logger.warn(fullReport.toString());

        // 构建聊天栏摘要
        StringBuilder summary = new StringBuilder();
        summary.append("Server thread stalled for ").append(elapsedMs).append("ms");
        summary.append(" | State: ").append(info.getThreadState());
        if (info.getLockInfo() != null) {
            summary.append(" | Lock: ").append(info.getLockInfo());
            if (info.getLockOwnerName() != null) {
                summary.append(" (held by ").append(info.getLockOwnerName()).append(")");
            }
        }

        // 构建剪贴板内容（摘要 + 前 15 行主线程堆栈 + 持锁线程堆栈）
        StringBuilder clipboard = new StringBuilder();
        clipboard.append("[TST WATCHDOG] ").append(summary).append("\n\n");
        clipboard.append("--- Server Thread Stack Trace (top 15) ---\n");
        int maxLines = Math.min(stack.length, 15);
        for (int i = 0; i < maxLines; i++) {
            clipboard.append("\t").append(stack[i].toString()).append("\n");
        }
        if (info.getLockOwnerId() != -1) {
            ThreadInfo lockOwnerInfo = bean.getThreadInfo(info.getLockOwnerId());
            if (lockOwnerInfo != null) {
                clipboard.append("\n--- Lock Owner Stack Trace (top 10) ---\n");
                StackTraceElement[] lockStack = lockOwnerInfo.getStackTrace();
                maxLines = Math.min(lockStack.length, 10);
                for (int i = 0; i < maxLines; i++) {
                    clipboard.append("\t").append(lockStack[i].toString()).append("\n");
                }
            }
        }

        // 保存为待发送报告（在下一次 tick 时由主线程发送）
        pendingChatSummary = summary.toString();
        pendingClipboardContent = clipboard.toString();
    }

    /**
     * 在聊天栏发送带点击复制功能的报告。
     * 必须在主线程上调用。
     */
    private static void sendChatReport (ServerLevel level_server, String summary, String clipboardText) {
        // 对文本做 JSON 转义
        String escapedSummary = escapeJSON(summary);
        String escapedClipboard = escapeJSON(clipboardText);

        // 构建 tellraw JSON
        // 格式: [THT] 看门狗检测到卡顿：... [点击此处复制]
        String json = "[{\"text\":\"\",\"color\":\"white\"},"
                + "{\"text\":\"[" + Core.mod_id_short + "] \",\"color\":\"yellow\"},"
                + "{\"text\":\"看门狗检测到卡顿：" + escapedSummary + "，完整报告已输出到 latest.log \",\"color\":\"red\"},"
                + "{\"text\":\"[点击此处复制]\",\"color\":\"aqua\",\"underlined\":true,"
                + "\"clickEvent\":{\"action\":\"copy_to_clipboard\",\"value\":\"" + escapedClipboard + "\"},"
                + "\"hoverEvent\":{\"action\":\"show_text\",\"contents\":\"点击复制报告摘要到剪贴板\"}}]";

        try {
            GameUtils.Command.run(level_server, Vec3.ZERO, "tellraw @a " + json);
        } catch (Exception e) {
            Core.logger.error("[TST Watchdog] Failed to send chat report", e);
        }
    }

    /**
     * JSON 字符串转义。
     */
    private static String escapeJSON (String text) {
        if (text == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.toString();
    }

    /**
     * 查找服务器主线程。
     * 优先通过 MinecraftServer 获取，后备方案是遍历所有线程。
     */
    private static Thread findServerThread () {
        // 优先通过 MinecraftServer 获取
        if (Core.currentServer != null) {
            try {
                Thread t = Core.currentServer.getRunningThread();
                if (t != null) {
                    return t;
                }
            } catch (Exception ignored) {
            }
        }

        // 后备：遍历所有线程找名为 "Server thread" 的线程
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            if (t.getName().equals("Server thread")) {
                return t;
            }
        }
        return null;
    }
}