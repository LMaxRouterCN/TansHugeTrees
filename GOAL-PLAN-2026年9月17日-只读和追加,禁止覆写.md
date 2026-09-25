>我是max,这条信息是我手写的,如果你看到这条信息,我得提醒你一下,不要绝对相信这个文件里写的东西,他们可能并没有在实际上做完他们说的改动
>并且我在前几天就不允许贡献者覆写这个文件了,所有人都只能追加内容
>我会手动编辑这个文件删除过时的信息
>我有时候会在下面的审计结果下批注,有批注就优先听我的
>压力测试环境:路径"E:\MC\.minecraft\versions\Industrial Revolution 2040\",整合包500+模组,每次编译后把jar放进mods,然后启动游戏,mc1.20.1,forge47.4.10,java21.0.7,内存分配14g,占用10g左右,启动游戏后新建世界,超平坦,地面高度27,地面方块草方块(没有雪),群系固定雪林minecraft:grove
>默认测试环境:路径"E:\MC\.minecraft\versions\TEST 1.20.1-Forge_47.4.10\",纯净包59模组,每次编译后把jar放进mods,然后启动游戏,mc1.20.1,forge47.4.10,java21.0.7,内存分配5g,占用3-4g左右,启动游戏后新建世界,超平坦,地面高度27,地面方块草方块(没有雪),群系固定雪林minecraft:grove
>预期的正常情况:地面被雪覆盖,大树和枯树正常频率生成,密度平均,每棵树都完整生成,无崩溃卡死,tps无尖峰卡顿
>原模组情况:地面被雪覆盖,大树和枯树生成,但dh生成的大树密度是mc生成的3-5倍,有极高频率的劈树现象,进入世界会直接卡死(第一次的修改简单的修复了此bug,所以才能正常测试),tps尖峰卡顿频发

   在安全的情况下追求最高的速度


━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
## 2026-09-25 V55 性能主刀·树全灭案手术(五刀) 编译绿待部署

案: 冷启动 259s/region(每树即冲 33154 次文件开关) + player_center 双扫 5region≈40% 算力 + 无任务优先级
判例: 176(刀V/V-2) 177(刀W/W-2) 178(刀X)  备份: .agent_temp_files/backup_v55

刀V[176]: writeData 每树即冲 → markDirty(dirty_regions 台账) + 宿主任务尾 drainDirty(全维度脏集,
  摘牌-冲刷-复查循环, 8 轮熔断防活锁)。铁律: 先写缓存后标脏 + 宿主尾必 drain(竞态闭合)。
  崩溃语义等价(claims FALSE 原子回滚重扫, 同种子重生同树)。冷启动 259s → 预期秒级。
刀V-2[176]: listFiles 目录列举缓存(list_files_cache + filterBinFiles 工厂, V16 过滤随逻辑搬家,
  null 目录不缓存保留重探)。clearWorldState 清场兜底。
刀W[177]: TreeLocation.run() 认领前 player_center 门禁(legacy 让路消灭双扫)。region 模式缺省
  零行为变化; [region 模式退场预备]注释已埋: 默认翻 player_center 后门改无条件 return → legacy 可删。
刀W-2[177]: pregenComputeRegion 尾部桥接三断线: drainDirty → claims TRUE → wakeOnRegionComplete
  (唤醒/终态判定/offer 快标记原由 legacy 尾供血, 禁后由本尾部接管; V42 不变量: drain 同步在前)。
刀X[178]: Handcode pregen_task_priority = fifo(缺省)/nearest/farthest, 三键齐(声明/模板/解析)。
  PregenEngine.nextTask 出队外壳化: fifo=poll / 极值=CLQ 弱一致遍历+原子 remove(平局保先见者);
  玩家快照 offer 主线程写, 池线程读; 维度外极值模式排尾; 非法值退化 fifo; 鞘翅警告入模板注释。

改动面: TreeLocation(10 锚) Handcode(3 锚) PregenEngine(4 锚) | 锚校验全过 fail-fast
验证: compileJava BUILD SUCCESSFUL 52s | 门禁/桥接/三键眼检在位 | flushCachesAsync 残 2(定义+drain)
验证教训: Select-String -Pattern 裸 [regex]::Escape($k) 必炸(判例: PS-Pattern表达式); 匹配行数≠字符次数
待: max 退游戏 → build jar → 部署 → 验收(世界66 盘上树直读 + 新世界冷启动计时 + priority 热切)
下期排期: config 系统改造(max 令: 修完测完启动)

## 2026-09-25 02:0x V55 部署完成
jar 20260925005623 -> E:\MC\.minecraft\versions\TEST 1.20.1-Forge_47.4.10\mods
(旧035014归档mod-jar-backups\*.disabled可逆, FINAL-COUNT=1单jar)
实例路径修正判例: 旧记忆 E:\MC.minecraft 系抄丢'\.'段 -> 真实 E:\MC\.minecraft\ (129号已覆盖)
待验收: 阶段1零配置=世界66回放33k树+新世界冷启动计时(刀V: 259s/region->秒级)
        阶段2=config改pregen_mode=player_center->U2接管+pregen_task_priority三值

## 2026-09-25 06:xx 刀Y 空白区根因修复(世界68验收翻案收口)
案: 空白正方形(r.-1,-1等区零树)+轴向长条; 速度半场刀V已结案(秒级启动+跑图提速)
根因: drainDirty惊群+熔断饿死宿主区脏键 -> 刀W-2尾claims TRUE早于落盘(铁证-1,-1: TRUE 02:36:01 / bin 02:40:52 = 4m51s)
  -> 被wake的chunk读空盘+3x3全TRUE -> V42 TERMINAL误杀整region(-1,-1 x1064 chunk处决)
  惊群机理: 8池线程尾并发drain同抓CHM迭代首元素, 摘牌失败continue烧轮->吞吐1/8; 并发波~30脏键 vs 8轮熔断
刀Y: 1)宿主尾双点(run+pregenComputeRegion) drainDirty后claims前 显式flushCachesAsync本区直冲(幂等)
  2)摘牌失败round--不烧轮 3)熔断8->64 | 判例180
密度判读: 68=正确执行(0,0区4490树); 66=双扫时代虚高(31438记录)+异种子非严格A/B; 想更密=multiply_rarity旋钮
世界68自愈: claims/TERMINAL纯内存态, 刀Y jar重启->re-offer重算(确定性同树)正确落盘->空区补齐
build: 受控32s(offline+no-daemon; 前次撞3600s exec墙) | jar: tanshugetrees-1.8.0-20260925060503.jar | FINAL-COUNT=1
部署勘误: 归档路径凭记忆猜错一次(D:\mcmod\mod-jar-backups不存在->Move失败但ARCHIVED打印无条件=假绿)
  实际归档目录: D:\Documents\mcmod\mod-jar-backups (035014/05623两代.disabled可逆)
晨验收: 1)世界68回放-空区自愈 2)新世界冷启动 3)读latest.log THT行定量

## 待办·4号案: 结构周围树空白(搁置, 优先级让位legacy退场)
现象: 要塞范围地面不长树, 过渡生硬, 空白呈chunk级正方形(判定粒度出卖形状)
二假说: A.THT主动避让(结构检测跳树, 方向正确粒度粗) / B.地表误杀(要塞入口改造地表->ground检测失败)
取证半途: DetailedDetection.test全文未读; 前次结构API搜索是非递归(只扫顶层Handcode.java, systems/全漏), 'ground_block=none'孤证不算数
修复光谱: 不动(现状,功能对美观打折) / footprint求交(正方形->结构实形+树半径, 中等) / 衰减带(最美最贵)
触发: max想修时喊一声, 从DetailedDetection.test读起(注意实际路径在systems/world_gen/); 判据=检测在计算侧还是放置侧, 是否依赖chunk加载

## 待办·5号案: 树生成状态指示器(max 25提出, 刀Z保留资产)
需求: 加载屏/游戏内进度条或转圈指示灯, 显示: 树在队列(深度)/扫描中/树入队/树出队/树放置/及更多
刀Z保留资产(勿误删): world_gen_icon 键(总开关, 建址=Overlays.eventInGame World Gen Icon 块墓碑);
  TreeLocation.world_gen_overlay_details_biome/_tree 活字段(getData 每次扫描写, 现成'扫描中'数据源);
  纹理 overlay_region_gen.png(4帧)+overlay_region_gen_bar.png(16段进度条)
设计备忘: 旧20tick自排程动画loop已随刀Z火化; 新实现=渲染帧直读原子计数(DeferredQueue深度/
  PregenEngine pending+in_flight+computed/TreePlacer放置计数), 渲染帧读取非轮询链
触发: max想搞时喊一声

---

## 刀Z战报 (2026-09-25) — legacy region 扫描链连根拔除

**裁决**: A pregen_mode 连根删 / B world_gen_icon 保留 (max 裁决不变)
**范围**: 五文件手术 (TreeLocation/Overlays/PregenEngine/WorldGen/Handcode) + EventCenter(core包) 补刀

### 战史 — 三道防线各拦一刀
1. **手术 32/32 全绿**: TL/HC/PE/WG/OV 五组断言步骤全过 + VERIFY-OK + 5号案落盘 778B
2. **断言网拦首刀**: PE-HEADER 锚「共存安全网」2命中 (类头PE8 + catch注释PE213, 未读区段盲区) → 自动还原 git → 重刀修正 (双条件锚 + strict计数 + VERIFY追加player_center) → 32/32
3. **编译器拦 build 红**: EventCenter.java:236 (core包) 悬挂引用 TreeLocation.start — 触点搜索三轮只圈 handcode 包, core 包整树盲区 (判例182/183) → 刀c: 236行替换墓碑注释 (processed_chunks 仅守护放置幂等) + TL114/115 stale注释翻新 → 全src五模式 grep CLEAN → build 绿 21s

### 判例沉淀
- 触点搜索必须覆盖整棵 src 树 (两包全量); 单包递归 = 画错圈
- 运行验收不能替代编译验证 (让路门短路令悬挂引用零症状)

### 部署
- jar 20260925183228 服役 (mods THT=1); 060503 归档 .disabled
- .gitignore 增 backup_v*/ (本地手术备份目录隔离)