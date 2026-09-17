# Tan's Huge Trees — LMax 维护分支

Minecraft 1.20.1 (Forge 47.4.10) 大型树木世界生成模组。
原作 TannyJung，本分支由 LMaxRouterCN 维护，重心为正确性与性能修复。

**核心管线**：region 预扫描 → 二进制树布局（bin）→ 主线程滴灌放置（每 tick 时间预算制）。
树以多 tick 滴灌节奏生成，远处树"陆续冒出"为设计行为，非卡顿。

## 当前状态（1.8.0 · 2026-09）

### 工作正常
- 全新启动游戏进入世界（含自定义超平坦 / biome modifier）：树正常生成
- 可见性：树即时出现于客户端，光照与树影正常
- 性能：主线程放置按每 tick 时间预算滴灌（`deferred_queue_budget_ms`，默认 40ms），无 TPS 尖峰
- 跨区块大树：方块按 chunk 分桶，由 chunk 加载事件驱动落块
- 原作系统：枯树 / 落叶层 / 季节掉叶（leaf litter / abscission）等

### 已知问题
1. **同一游戏进程内切换或新建世界，新世界不生成树**（region 扫描状态跨世界残留，待修复）。
   临时解法：更换世界前完全退出并重启游戏。
2. 旧世界中已扫描过的 region 不会生成新树（幂等设计）。想看到新树，去从未到访过的区域。

## 调试

`config/tanshugetrees/lmax-debuglog.json`：逐子系统日志开关。
其中 `"watchdog_enabled": true` 开启服务端卡顿看门狗（触发阈值 `watchdog_threshold_ms` 在主 config，默认 50）。

---
Mod By TannyJung (April 2021) · Maintained & fixed by LMaxRouterCN (2026)
