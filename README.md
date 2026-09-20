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

## 调试

`config/tanshugetrees/lmax-debuglog.json`：逐子系统日志开关。
其中 `"watchdog_enabled": true` 开启服务端卡顿看门狗（触发阈值 `watchdog_threshold_ms` 在主 config，默认 50）。

## 关于本分支 / About this Fork

本分支基于 TannyJung 的原作，仅在 1.20.1 (Forge) 下进行正确性与性能修复，不新增内容。修复完善后，改动将以 PR 形式回馈上游——本项目的功劳属于原作者。

This fork is based on Tan's Huge Trees by TannyJung, focused on correctness & performance fixes for 1.20.1 Forge only. Once the fixes mature, changes will be contributed back upstream via pull requests. Full credit goes to the original author.

本分支仅公开源码；编译版本的发布将在获得原作者明确许可后进行。若原作者提出任何异议，本仓库将立即配合处理。

This repository only publishes source code. Compiled builds will be released only with explicit permission from the original author. Any concerns from the author will be complied with immediately.

---
Mod By TannyJung (April 2021) · Maintained & fixed by LMaxRouterCN (2026)
