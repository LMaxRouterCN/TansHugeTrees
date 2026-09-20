# GOAL-PLAN — TansHugeTrees 大修战役 (LMax Fix V52)
> 更新: 2026-09-20 22:10 | 阶段: 刀Q已部署待运行时验证 / 桌面清空
> 判例库: .agent/memory.md (001-141) 本文件=作战地图; 上代快照: .agent/GOAL-PLAN_v50_snapshot.md + v51_snapshot.md

## 0. 项目身份
- 仓库: D:\Documents\mcmod\TansHugeTrees (1.20.1 Forge 47.4.10)
- 管线: 扫描(TreeLocation)→region bin(字典short编码)→放置(TreePlacer/executor)→客户端
- 部署链: gradlew build --offline → jar新鲜度<15min → 旧jar清除 → MD5双验 → mods唯一
- 部署红线: 游戏进程(命令行含minecraft|forge)运行中禁部署; gradle daemon放行
- harness已知bug(2026-09-20判例): gradle类指令回执卡running(daemon继承管道句柄EOF不来)——执行完整但回执死信, max已去拷打作者(根修=退出码判定); 期间build类操作做好回执丢失预期, 靠取证判状态

## 1. 状态快照 (2026-09-20 22:10)
- HEAD: b81bef3
- mods: tanshugetrees-1.8.0-20260920214025.jar (刀Q) MD5 593BA340701023E84D410569AF815207 双验, mods唯一
- 现役日志: latest.log = 09-19 01:40 (刀Q零运行时)

## 2. 待办
### 2.1 max烟测(刀O+刀Q合并): 启动到主菜单退出即可
### 2.2 刀Q运行时验收(下次进世界, 协议见记忆141):
- 新世界 dictionary.txt 无dup无gap
- DD-REJECT-E4 = 0
- "[THT] Tree shape data missing" warn 仅真缺文件时
### 2.3 L1704案D挂账: E9=0基线已立, 有村庄世界时grep复验
### 2.4 死码挂账: CAV66 catch-return无shape_locks.remove(不可达防御路径, 不修)

## 3. 已结案
| 案 | 修复 | 判例 |
|---|---|---|
| 幽灵方块 | 刀L(直写)+刀N(主线程收敛) | 128/130 |
| 跨世界静态泄漏 | 刀K(region_scan_claims清场) | 130 |
| Watchdog跨世界假episode | 刀O(部署即结案, max裁决免专门验收) | 137 |
| 单人暂停假episode(130s) | 非bug | 139 |
| E4吞树62棵(字典id分配竞态) | 刀Q: 甲(注册原子化: synchronized+双重检查+id改max+1+空串守卫)+丁(毒缓存不入档+warn+V16锁泄漏补修) | 140/141, 运行时验证待§2.2 |

## 4. 方法论铁律
- 会计闭环: 入口计数=Σ出口+PASS
- 观测者坐标先对账再判失败(两翻案同因)
- 定性竞态必须按查询key分组, id级聚合=假象(E4三连翻案)
- 字典错位先全量dump按id分组找dup+gap(k连dup→k-1连gap=并发指纹)
- 会话世界按dictionary.txt mtime对账, 不凭世界编号猜
- jar时间戳vs commit必核; "运行了"≠"写入了"; 回执死信时以git/文件指纹取证判定(b81bef3判例)

## 5. 测试环境备忘
- max画像: 开LAN时Esc/M地图不暂停; 结束=任务管理器杀进程; 体感不卡=真无卡顿
- 单人Esc暂停/外部杀进程=watchdog假episode天然源(预期行为非bug)

## 6. 日志 (2026-09-20 归位轮)
- 刀O结案: max裁决改动面单一看门狗, 免专门验收, 烟测即加载层确认
- E4批审通过(甲+丁), 刀Q两轮侦察: DataText全CHM容器无罪→单锁方案; 计划外顺修两个(#5空串污染+V16锁泄漏)
- 刀Q落地: 五处手术, jar 20260920214025(62975KB), MD5双验, commit c636a70+b81bef3
- harness死信事故: exec被kill于未知阶段, 取证判明全链完整跑完(手术→build→部署→commit), 判据=commit指纹+jar mtime+手术痕迹计数
- 待max: 烟测+进世界验证