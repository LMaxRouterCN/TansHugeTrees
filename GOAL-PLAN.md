# GOAL-PLAN — TansHugeTrees 大修战役 (LMax Fix V51)
> 更新: 2026-09-20 03:00 夜班终版 | 阶段: 刀O已部署待验收 / E4已定案待批刀
> 判例库: .agent/memory.md (001-140) 本文件=作战地图; 上代快照: .agent/GOAL-PLAN_v50_snapshot.md

## 0. 项目身份
- 仓库: D:\Documents\mcmod\TansHugeTrees (1.20.1 Forge 47.4.10)
- 管线: 扫描(TreeLocation)→region bin(字典short编码)→放置(TreePlacer/executor)→客户端
- 部署链: gradlew build --offline → jar新鲜度<15min → 旧jar清除 → MD5双验 → mods唯一
- 部署红线: 游戏进程(命令行含minecraft|forge)运行中禁部署; gradle daemon放行(判例: PROC 24044误拦)
- 测试台: 世界(60)=草面grove / 世界(61)=雪面grove(polaris试验台); 旧54/55=刀K/N时代

## 1. 状态快照 (2026-09-20 03:00)
- HEAD: ff1d7c3 + 夜班收工commit(含本文件)
- mods: tanshugetrees-1.8.0-20260920021345.jar (刀O) MD5双验过, mods唯一
- 现役日志: latest.log = 09-19 01:29-01:40 (世界60→61切换, Esc暂停+任务管理器杀进程收尾)

## 2. 待办 (max醒来裁决)
### 2.1 刀O验收 (下次开游戏即测, 判据4条 见记忆137)
①60s+假episode消失 ②真episode分布不变(基线29个全≤5s) ③切世界窗口零WATCHDOG ④无聊天假警报
### 2.2 E4修法批审 (根因定案 见记忆140: 字典id分配竞态)
- 甲(推荐): getDictionary注册原子化 = synchronized注册锁 + id改max+1(行数+1在有gap时复用旧id二次撞)
- 丁(推荐): Caches L101-103 读空不入缓存(防其他miss源永久化)
- 乙(可选): E4日志去重
- 丙(否): 旧档手术不需要——受害者行均在鬼行前, 冷读首匹配自动恢复正确; 62棵死树region bin重放(语义待验证)理论复活=修后天然验证场景
### 2.3 L1704案D挂账 (记忆136): E9=0基线已立, 有村庄世界时grep复验
### 2.4 静默面知情: 同类字典碰撞换树形无日志(wendy×wendy); 树id碰撞腐蚀距离检测((55)id32); 甲案一揽子全修

## 3. 已结案
| 案 | 修复 | 判例 |
|---|---|---|
| 幽灵方块 | 刀L(直写)+刀N(主线程收敛) | 128/130 |
| 跨世界静态泄漏 | 刀K(region_scan_claims清场) | 130 |
| Watchdog跨世界假episode | 刀O(已部署待验收) | 137 |
| 单人暂停假episode(130s) | 判别器结案=非bug | 139, max裁决不修 |
| E4吞树62棵 | 定案=字典id竞态 | 140, 修法待批 |

## 4. 方法论铁律
- 会计闭环: 入口计数=Σ出口+PASS
- 观测者坐标先对账再判失败(两翻案同因)
- 定性竞态必须按查询key分组(location全文), id级聚合=假象(E4三连翻案)
- 字典错位先全量dump按id分组找dup+gap(k连dup→k-1连gap=并发指纹)
- 会话世界按dictionary.txt mtime对账, 不凭世界编号猜(R7自首#4)
- jar时间戳vs commit必核; "运行了"≠"写入了"

## 5. 测试环境备忘
- max画像: 开LAN时Esc/M地图不暂停; 结束=任务管理器杀进程; 体感不卡=真无卡顿(人证)
- 单人Esc暂停/外部杀进程=watchdog假episode天然源(预期行为非bug)

## 6. 夜班记录 (2026-09-20 02:10-03:00, max睡眠期自动模式)
- R1-R2: 判别器结案(139, 暂停门)+E4溯源; 部署被gradle daemon误拦→R3修红线(按CommandLine判身份)
- R3: 刀O部署成功: build 8s → jar 20260920021345(62MB) → MD5 31FB5EDD...双验 → mods唯一; commit ff1d7c3
- R4-R7: E4四轮侦办: 竞态假说→静态缺失→路径错位→字典id分配竞态终审定案(140)
- 关键证据: (60)id14 dup(wendy_231×bush_704) (61)id29 dup(wendy_742×bush_704); 62=21+41全polaris; 死亡率3.7%/4.7%≈1/wendy池20
- 休眠: 03:39 THT_AutoSleep自动shutdown /h | 取消: schtasks /Delete /TN THT_AutoSleep /F