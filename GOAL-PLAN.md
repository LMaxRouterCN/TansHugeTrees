
---

## [2026-07-27] 世界生成性能修复（LMax Fix V15）— 待用户测试

### 根因
干净实例确认 Feature 注册正常，place() 首次新世界被调用，但卡死0%。
原因：`Caches.TreeShape.getTreeShape()` 和 `Caches.TreeSettings.get()` 都是 synchronized 静态方法+文件I/O，
MC多线程区块生成时类级锁把所有worker线程串行化。
另外 `TreeLocation.run()` 的 `scanned_regions.contains()` 检查是空块没return。

### 修复
- getTreeShape()/TreeSettings.get(): 类级synchronized改为per-key锁(ConcurrentHashMap+双重检查)
- 移除getTreeShape()里7行debug println
- TreeLocation.run(): 空块加return，添加扫描耗时日志

### 待确认
- 世界生成不再卡死
- 树和雪正常生成
- 扫描耗时日志