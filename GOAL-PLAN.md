# TansHugeTrees 终极重构与修复计划 (1.20.1 Forge)

## 当前状态
- 分支: `1.20.1forge-LMaxFixAndImprove`
- 工作目录: `D:/Documents/mcmod/TansHugeTrees`
- 核心痛点: 作者不懂并发，MCreator 冗余多，硬编码泛滥，主线程阻塞。

## 阶段一：精准切除毒瘤 (解决 TPS 卡死与死锁) - [已完成]
- [x] 1. 废除 `Core.java` 中的 `GlobalLocking`，消除异步区块生成（如 Distant Horizons）时的死锁。
- [x] 2. 审计 `GameUtils.java` 和 `CacheManager.java`，将普通 `HashMap` 全部升级为 `ConcurrentHashMap`，修复并发读写冲突。
- [x] 3. 审计 `OutsideUtils.java`，为 `readOnlineTXT` 增加 3s/5s 强制超时，防止主线程网络阻塞。

## 阶段二：实地测试与并发写入排查
- [ ] 1. 观察 DH 并发生成区块时的 MSPT，确认死锁是否彻底消除。
- [ ] 2. 排查多线程同时生成巨树时，底层方块写入（Level.setBlock）是否会出现线程安全问题。

## 阶段三：剥离 MCreator 累赘与深度重构
- [ ] 审计 MCreator 自动生成的注册表和事件总线，剔除无用代码。
- [ ] 引入线程池处理巨树生成/数据加载，通过事件回调与主线程通信。
- [ ] 将硬编码数值提取为配置项，实现程序通用化。