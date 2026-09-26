package tannyjung.tanshugetrees_core;

// [LMax Fix V54 刀S] [长期记忆: 149] 预算三键热重载 — WatchService 事件驱动(零轮询)监听 config/config.toml,
// 文件变更时重读并热应用 budget_mode / deferred_queue_budget_ms / deferred_queue_budget_expr 三键
// (经 TreePlacer.DeferredQueue.reloadBudget → compileProgram 原子换装 Program), 服务器无需重启.
// 作用域锁死三键: 其余 config 键改动需重启生效(与 lmax-debuglog.json 同边界), 避免半写半应用状态扩散.
// max 规程 [长期记忆: 060] 沿袭: 机制(监听/解析/换装)在代码层, 策略(表达式/数值)在用户数据层.
//
// 线程模型: 单守护线程 "THT-ConfigWatch"; WatchService.take() 事件驱动阻塞(无事件零 CPU, 拒绝轮询).
// 防抖: 编辑器一次保存常拆成多事件(元数据+内容先后到) — take 唤醒后 300ms 有界等待收敛尾事件
// (守护线程内的有界等待, 不阻塞游戏任何线程; 是防抖收尾不是轮询循环).
// 防半写: 三键不齐 = 编辑/写入中途, 跳过本次应用(下次事件再试), 永不应用残缺值.
// 失败语义 fail-open: watcher 任何异常退出 = 热重载缺席, 游戏零影响(静态值仍由启动加载持有).
// [U5] 读取链统一为 ConfigToml.getValues(night-config 解析), 与启动加载同一条读链; 旧手搓行级 parse 退役.

import java.io.File;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.Map; // [U5 Stage1] ConfigToml.getValues returns Map<String,String>

public class WatchConfigReload {

    private static volatile Thread watch_thread = null;

    // Core.start() 末尾调用(loadDebugLogConfig 之后, path_config 已就绪). 幂等: 重复调用零副作用.
    public static synchronized void start () {
        if (watch_thread != null) return;
        File dir = new File(Core.path_config);
        if (dir.isDirectory() == false) {
            System.err.println("[LMax] budget hot-reload not started: config dir missing " + dir.getPath());
            return; // 异常环境静默缺席(fail-open, 不炸启动)
        }
        Thread thread = new Thread(WatchConfigReload::loop, "THT-ConfigWatch");
        thread.setDaemon(true); // 不阻止 JVM 退出
        watch_thread = thread;
        thread.start();
        System.out.println("[LMax] budget hot-reload watching: " + dir.getPath() + "/config.toml");
    }

    private static void loop () {
        // try-with-resources: watcher 线程退场时自动释放 WatchService 句柄
        try (WatchService ws = FileSystems.getDefault().newWatchService()) {
            Path dir = new File(Core.path_config).toPath();
            // ENTRY_CREATE 兼容"临时文件+改名"式保存的编辑器(改名落到目录触发 create 事件)
            dir.register(ws, StandardWatchEventKinds.ENTRY_MODIFY, StandardWatchEventKinds.ENTRY_CREATE);
            while (true) {
                WatchKey key = ws.take(); // 事件驱动阻塞: 无事件零 CPU
                boolean touched = drain_key(key);
                if (touched == false) continue;
                // 防抖: 300ms 有界等待, 收敛同一次保存的尾事件(守护线程内等待, 不阻塞游戏)
                try { Thread.sleep(300); } catch (InterruptedException e) { return; }
                while (true) { // 收尾: 防抖窗口内到达的事件属同一次保存, 不再二次等待
                    WatchKey extra = ws.poll();
                    if (extra == null) break;
                    drain_key(extra);
                }
                apply();
            }
        } catch (InterruptedException e) {
            return; // 关闭信号: 静默退场(fail-open)
        } catch (Exception e) {
            watch_thread = null; // 允许未来 start() 重试
            System.err.println("[LMax] budget hot-reload watcher stopped (hot-reload now absent, game unaffected): " + e);
        }
    }

    // 排空单个 key 的事件表; 返回是否触及 config.toml. OVERFLOW 事件(context=null)忽略.
    private static boolean drain_key (WatchKey key) {
        boolean touched = false;
        for (WatchEvent<?> event : key.pollEvents()) {
            if (event.context() != null && event.context().toString().equals("config.toml")) {
                touched = true;
            }
        }
        key.reset();
        return touched;
    }

    // 重读 config.toml 并热应用三键. 半写守卫: 三键不齐 = 编辑/写入中途, 跳过本次(下次事件再试).
    private static void apply () {
        // [U5] 读取链统一为 ConfigToml.getValues(night-config 解析); 三键不齐 = 编辑/写入中途, 跳过本次(下次事件再试).
        try {
            File file = new File(Core.path_config + "/config.toml");
            if (file.isFile() == false) return;
            Map<String, String> data = tannyjung.tanshugetrees_core.outside.ConfigToml.getValues(file.getPath());
            String mode = data.get("deferred_queue_budget_mode");
            String expr = data.get("deferred_queue_budget_expr");
            Integer budget = null;
            // [U5] Integer.parseInt(null) throws NumberFormatException: one guard covers missing key and non-numeric value.
            try { budget = Integer.parseInt(data.get("deferred_queue_budget_ms")); } catch (NumberFormatException e) { budget = null; }
            if (mode == null || expr == null || budget == null) {
                System.err.println("[LMax] budget hot-reload skipped: incomplete keys (mode=" + mode + " ms=" + budget + ")");
                return;
            }
            tannyjung.tanshugetrees_handcode.systems.world_gen.TreePlacer.DeferredQueue.reloadBudget(mode, budget, expr);
            // reloadBudget keeps the old Program on compile failure (warn-once); this line only reports received values.
            System.out.println("[LMax] budget hot-reloaded: mode=" + mode + " ms=" + budget + " expr='" + expr + "'");
        } catch (Exception e) {
            System.err.println("[LMax] budget hot-reload failed (keeping current values): " + e);
        }
    }
}
