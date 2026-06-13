# TansHugeTrees 终极重构与修复计划 (1.20.1 Forge)

## 当前状态
- 分支: `1.20.1forge-LMaxFixAndImprove`
- 核心痛点: 作者不懂并发，MCreator 冗余多，硬编码泛滥，主线程阻塞。

## 阶段一：精准切除毒瘤 (解决 TPS 卡死与死锁)
- [x] 1. 废除 `Core.java` 中的 `GlobalLocking`。
- [x] 2. `CacheManager` 升级 `ConcurrentHashMap`。
- [x] 3. `OutsideUtils` 增加网络超时。
- [ ] 4. 拆除 `TreePlacer$Data` 中的第二把全局对象锁，解决 DH 线程池饥饿问题。
- [ ] 5. 解决多线程并发下的跨区块树木截断（劈树）问题。

## 阶段二：实地测试与并发写入排查
- [ ] 观察 DH 并发生成区块时的 MSPT。
- [ ] 排查底层方块写入的线程安全问题。

## 阶段三：剥离 MCreator 累赘与深度重构
- [ ] 审计 MCreator 注册表，剔除无用代码。
- [ ] 提取硬编码为配置项。