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
 * 2. 独立守护线程每 5ms 检查一次，距离上次 tick 超过阈值即进入"卡顿事件"（episode）
 * 3. [LMax Fix V47] 事件门控（替代旧版 500ms 冷却轰炸式刷屏）：
 *    - 事件开始：报告一次主线程完整堆栈 + 锁信息（reportStall，每事件仅一次）
 *    - 冻结持续到里程碑（1s / 5s / 20s，此后每 10s）：追加全线程 dump
 *      （RUNNABLE/BLOCKED 全栈 ≤12 帧 + 阻塞锁归属 + 死锁环检测；纯 park 空闲线程压一行）
 *    - 主线程恢复后的第一个 tick：打事件总结行（总时长 + 报告数），聊天摘要刷新为真实总时长
 * 4. 聊天栏摘要带点击复制（tellraw，主线程恢复后发送，每事件一条）
 *
 * V47 动机（2026-09-08 取证）：42s 级大冻结中主线程堆栈只显示 managedBlock 等
 * chunk 管线（主线程是受害人视角），看不到是谁饿死 worldgen 管线——
 * 全线程 dump 才能拍到持锁者 / 连锁加载者 / 死锁环的现行。
 *
 * 配置项（在 config.txt 中）：
 * - watchdog_enabled：是否启用看门狗
 * - watchdog_threshold_ms：触发阈值（毫秒），默认 50
 * - watchdog_dump_all_threads：里程碑时刻是否 dump 全部线程堆栈，默认 true
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

    // [LMax Fix V47] 全线程 dump 开关（watchdog_dump_all_threads），由 Handcode.Config 读取
    // volatile：主线程配置加载时写入，看门狗线程读取
    public static volatile boolean dumpAllThreadsEnabled = true;

    // 检查间隔（毫秒），看门狗线程的 sleep 间隔
    private static final long CHECK_INTERVAL_MS = 5;

    // ===== [LMax Fix V47] 卡顿事件状态（episode gating，替代旧 500ms 冷却）=====

    // 是否处于卡顿事件中（true = 主线程自上次 tick 起持续冻结）
    // 看门狗线程置位，主线程在 updateTickTime() 复位；volatile 保证跨线程可见
    private static volatile boolean episodeActive = false;

    // 事件开始的墙钟时间（毫秒），仅用于事件结束总结行的总时长计算
    private static volatile long episodeStartWallMs = 0;

    // 本事件已产生的报告数（初始报告 + 已触发的里程碑 dump）
    private static volatile int episodeReportCount = 0;

    // 下一个待触发的里程碑下标（0 = 尚未触发任何里程碑）
    // 看门狗线程单写递增，主线程在事件结束时复位；竞态最坏效果 = 多/漏一次诊断 dump，可接受
    private static volatile int nextMilestoneIndex = 0;

    // 里程碑表：冻结持续到该时长时做一次全线程 dump
    // 1s 抓早期形态，5s 抓中期，20s 起每 10s 抓长期饥饿者（42s 冻结 = 1/5/20/30/40s 共 5 次）
    // 注：这是诊断仪器的取样节奏，非业务调度策略；如需外置可后续提取到 Handcode.Config
    private static final long[] DUMP_MILESTONES_MS = {1_000, 5_000, 20_000, 30_000, 40_000, 50_000, 60_000, 70_000, 80_000, 90_000};

    /**
     * 启动看门狗守护线程。
     * 多次调用安全，线程已在运行时仅更新阈值。
     */
    public static void start () {
        if (watchdogThread != null && watchdogThread.isAlive()) {
            return;
        }

        watchdogThread = new Thread(() -> {
            Core.logger.info("[TST Watchdog] Watchdog daemon started. Threshold: {}ms, dumpAllThreads: {}", thresholdMs, dumpAllThreadsEnabled);

            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(CHECK_INTERVAL_MS);

                    // 等待第一次 tick 后才开始监控
                    if (!armed) {
                        continue;
                    }

                    long elapsedMs = (System.nanoTime() - lastTickNanoTime) / 1_000_000;

                    if (elapsedMs > thresholdMs) {

                        if (!episodeActive) {
                            // [LMax Fix V47] 事件开始：初始报告（主线程堆栈 + 锁），每事件仅一次
                            beginEpisode(elapsedMs);
                        } else {
                            // [LMax Fix V47] 里程碑推进：冻结持续到里程碑时长时追加全线程 dump
                            while (nextMilestoneIndex < DUMP_MILESTONES_MS.length
                                    && elapsedMs >= DUMP_MILESTONES_MS[nextMilestoneIndex]) {
                                dumpMilestone(elapsedMs, DUMP_MILESTONES_MS[nextMilestoneIndex]);
                                nextMilestoneIndex++;
                            }
                        }
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

        // [LMax Fix V47] 事件结束检测：主线程 tick 恢复 = 冻结事件结束，打总结行并复位状态
        if (episodeActive) {
            long totalMs = System.currentTimeMillis() - episodeStartWallMs;
            Core.logger.warn("[TST WATCHDOG] Stall episode ended: {}ms total, {} report(s)", totalMs, episodeReportCount);

            // [LMax Fix V47] 刷新待发聊天摘要为真实总时长（初始报告里的 ms 是冻结初期读数，偏低）
            if (pendingChatSummary != null) {
                pendingChatSummary = "Server thread stalled for " + totalMs + "ms (episode total, " + episodeReportCount + " report(s))";
            }

            episodeActive = false;
            nextMilestoneIndex = 0;
            episodeReportCount = 0;
        }

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
     * [LMax Fix V47] 事件开始：置位事件状态 + 初始报告（主线程堆栈 + 锁信息）
     */
    private static void beginEpisode (long elapsedMs) {
        episodeActive = true;
        episodeStartWallMs = System.currentTimeMillis();
        episodeReportCount = 1;
        nextMilestoneIndex = 0;
        reportStall(elapsedMs);
    }

    /**
     * [LMax Fix V47] 里程碑报告：冻结持续到 milestoneMs 时取样。
     * dumpAllThreadsEnabled=true → dump 全部线程（含死锁检测）；
     * =false → 退化为补主线程顶帧 15 帧（事件链不断流）。
     */
    private static void dumpMilestone (long elapsedMs, long milestoneMs) {
        episodeReportCount++;

        Thread serverThread = findServerThread();
        if (serverThread == null) {
            Core.logger.warn("[TST WATCHDOG] Milestone +{}ms (stalled {}ms): server thread not found!", milestoneMs, elapsedMs);
            return;
        }

        ThreadMXBean bean = ManagementFactory.getThreadMXBean();
        ThreadInfo info = bean.getThreadInfo(serverThread.getId());

        StringBuilder sb = new StringBuilder();
        sb.append("\n");
        sb.append("====================================================================================\n");
        sb.append("[TST WATCHDOG] MILESTONE +").append(milestoneMs).append("ms (stalled ").append(elapsedMs).append("ms)\n");
        if (info != null) {
            sb.append("Server thread state: ").append(info.getThreadState()).append("\n");
            if (info.getLockInfo() != null) {
                sb.append("Server waiting on: ").append(info.getLockInfo()).append("\n");
            }
        }

        if (dumpAllThreadsEnabled) {
            try {
                appendAllThreadsDump(bean, sb);
            } catch (java.lang.UnsupportedOperationException e) {
                // 极少数 JVM 不支持 monitor dump：退化为仅主线程顶帧，保证事件链不断流
                sb.append("(all-thread dump unsupported on this JVM: ").append(e).append(")\n");
                sb.append("--- Server Thread Stack Trace (top 15) ---\n");
                StackTraceElement[] stack = serverThread.getStackTrace();
                int maxLines = Math.min(stack.length, 15);
                for (int i = 0; i < maxLines; i++) {
                    sb.append("\t").append(stack[i].toString()).append("\n");
                }
            }
        } else {
            // 开关关闭：只补主线程顶帧（保持事件可观测性）
            sb.append("--- Server Thread Stack Trace (top 15) ---\n");
            StackTraceElement[] stack = serverThread.getStackTrace();
            int maxLines = Math.min(stack.length, 15);
            for (int i = 0; i < maxLines; i++) {
                sb.append("\t").append(stack[i].toString()).append("\n");
            }
        }
        sb.append("====================================================================================\n");

        Core.logger.warn(sb.toString());
    }

    /**
     * [LMax Fix V47] 全线程 dump（单条日志事件输出，避免与其他日志交错）：
     * - findDeadlockedThreads() 检测死锁环（监视器 + 可同步锁），涉事线程标 [DEADLOCKED]
     * - 非空闲线程打全栈（≤12 帧）+ 阻塞锁归属（waiting on ... held by ...）
     * - 纯 park/wait 且无锁归属的空闲线程压成一行（worker 池 idle / Netty epoll 等无信息量）
     */
    private static void appendAllThreadsDump (ThreadMXBean bean, StringBuilder sb) {

        // 死锁检测（返回 null = 无死锁环）
        long[] deadlockedIds = bean.findDeadlockedThreads();
        java.util.HashSet<Long> deadlocked = new java.util.HashSet<>();
        if (deadlockedIds != null) {
            for (long id : deadlockedIds) deadlocked.add(id);
            if (!deadlocked.isEmpty()) {
                sb.append("!!! DEADLOCK DETECTED: ").append(deadlocked.size()).append(" thread(s) in cycle !!!\n");
            }
        }

        // dumpAllThreads(lockedMonitors, lockedSynchronizers, maxDepth)
        ThreadInfo[] all = bean.dumpAllThreads(true, false, 12);
        for (ThreadInfo ti : all) {
            if (ti == null) continue;

            Thread.State state = ti.getThreadState();
            boolean is_deadlocked = deadlocked.contains(ti.getThreadId());

            // 空闲线程压缩：WAITING/TIMED_WAITING 且无锁归属 且 栈顶为 park/wait → 一行带过
            // （停在同步锁上的 BLOCKED 线程有 lockInfo，不会进入此分支）
            if ((state == Thread.State.WAITING || state == Thread.State.TIMED_WAITING)
                    && ti.getLockInfo() == null
                    && isIdleTopFrame(ti)) {
                sb.append("[").append(ti.getThreadName()).append("]")
                        .append(is_deadlocked ? " [DEADLOCKED]" : "")
                        .append(" idle (").append(state).append(")\n");
                continue;
            }

            sb.append("[").append(ti.getThreadName()).append("]")
                    .append(is_deadlocked ? " [DEADLOCKED]" : "")
                    .append(" ").append(state);
            if (ti.getLockInfo() != null) {
                sb.append(" | waiting on ").append(ti.getLockInfo());
                if (ti.getLockOwnerName() != null) {
                    sb.append(" (held by ").append(ti.getLockOwnerName())
                            .append(" id=").append(ti.getLockOwnerId()).append(")");
                }
            }
            sb.append("\n");

            StackTraceElement[] frames = ti.getStackTrace();
            for (StackTraceElement ste : frames) {
                sb.append("\t").append(ste.toString()).append("\n");
            }
        }
    }

    /**
     * [LMax Fix V47] 判定空闲线程：栈顶为 Unsafe.park / LockSupport.park / Object.wait
     */
    private static boolean isIdleTopFrame (ThreadInfo ti) {
        StackTraceElement[] frames = ti.getStackTrace();
        if (frames.length == 0) return true;
        String top = frames[0].toString();
        return top.contains("Unsafe.park")
                || top.contains("LockSupport.park")
                || top.contains("Object.wait");
    }

    /**
     * 抓取主线程堆栈和锁信息，输出完整报告到日志，并保存摘要供聊天栏发送。
     * [LMax Fix V47] 现为卡顿事件的初始报告（每个事件仅调用一次，事件内后续由里程碑接管）。
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