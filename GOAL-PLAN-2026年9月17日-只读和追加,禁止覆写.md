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
