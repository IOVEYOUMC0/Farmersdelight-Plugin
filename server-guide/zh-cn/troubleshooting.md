---
icon: wrench
---

[English](../en/troubleshooting.md)

# 故障排查

## 日志在哪

FarmersDelight 打印的一切都进普通的服务器控制台和 `logs/latest.log`。没有单独的日志文件。

### 控制台 / 日志语言

FarmersDelight 自己日志行的语言由 `config.yml` 里的 `language:` 控制：

- **留空（`language: ''`）** —— 跟随 JVM / 系统语言；
- **`en_us` 或 `zh_cn`** —— 强制该语言。

它管的是**日志、控制台输出，以及没有玩家上下文的任何文本**。各个玩家仍按各自客户端语言看界面和聊天，与此设置无关。

解析是有兜底的：所配语言没安装时，FarmersDelight 会退回 CraftEngine/JVM 语言，再退回某个已加载的兜底语言，并记录用了
哪个兜底。启动时它还会把插件更新引入的**新增**语言键合并进你现有的 `lang/*.yml`，让文件跨升级保持完整。随包语言文件在
`plugins/FarmersDelight/lang/`。

### 调试日志

默认关闭。追某个具体子系统时，窄范围开启：

```yaml
debug:
  enabled: true
  categories: [cooking_pot, stove, skillet, tray]
```

有用的类别：`cooking_pot`、`stove`、`skillet`、`tray`，外加 `startup`、`recipe`、`loot` 用来看逐子系统的启动细分。
想要安静的服务器就 `categories: []`（或保持 `enabled: false`）；`all` 或 `*` 只临时用。这些类别里的一切也会以
`FINE` 级别写出，所以调高日志器级别就能看到，不必开 debug。

## 现象 → 原因 → 解决

| 现象 | 可能原因 | 解决 |
| --- | --- | --- |
| FarmersDelight 从不启用，日志说缺依赖 | 没装 CraftEngine | 安装与服务器匹配的 CraftEngine **26.9.1**；它是硬 `depend`。 |
| 插件加载失败，报版本不支持 / 类错误 | 服务端低于 MC 1.21.4 或 Java 低于 21 | 用 **Paper/Folia 1.21.4+** 跑在 **Java 21+** 上。 |
| 自定义物品 / 方块显示紫黑贴图 | 客户端没用上当前资源包 | 执行 **`/ce reload all`** 重建包（单独的 `/ce reload` 不会）；确认玩家接受了包。见 [资源包](resource-pack.md)。 |
| 手持煎锅交互报 `ObfuscatedItemModelProcessor` / `NoClassDefFoundError` | CraftEngine 26.9.1 不再提供旧的客户端模型处理器 | 更新到本次 FarmersDelight 构建后完整重启；插件会回退服务端物品模型并只记录一次警告，再执行 **`/ce reload all`** 重建资源包。 |
| `/ce item give ... farmersdelight:cooking_pot` 报未知物品 | CraftEngine 内容没解析 | 查控制台有无 CraftEngine 行为报错（[确认行为已加载](verifying.md)）；修好点名的方块配置；`/ce reload`。 |
| 控制台出现点名某属性的 CraftEngine 行为报错 | 某方块配置缺了必需属性 | 不是崩溃，是信号。把点名的属性补回该方块，再 `/ce reload`。见 [确认行为已加载](verifying.md)。 |
| 方块放下了但什么都不做（如耕地湿度永不变） | 它的行为因缺属性而中止加载 | 同上——读那条行为报错，补属性。 |
| 热源上的烹饪锅 / 煎锅不烹饪 | 下方的块不是被识别的热源 | 对照 `config.yml` 的 `heat-sources`（点燃的营火、`farmersdelight:stove[fire:true]`、岩浆、熔岩、火）。 |
| 漏斗吃物品 / 在工作站附近重复创建容器 | 漏斗桥接冲突 | 设 `hopper-interactions.enabled: false` 隔离，再逐工作站调子开关。见 [首要配置项](first-config.md)。 |
| 更新后被删的配方又回来了 | `recipes.merge-missing-bundled` 把它加回来了 | 若你是故意删配方，保持 `merge-missing-bundled: false`。见 [迁移](migration.md)。 |
| 更新带来的新设置好像没作用 | 读到了旧值 | 配置自动迁移会在启动时对齐键名；若你手改过，确认键名与随包 `config.yml` 一致。 |
| 改了随包 CE 资源但没生效 | 改动没被拾取 | 执行 `/ce reload all`（只改配置可用 `/ce reload`，但模型 / 贴图改动需要 `all` 来重建包）。若你是**删**了某文件想让它一直消失，注意 `craftengine-resources.auto-completion: true` 会还原被删文件——设成 `false` 才能保留删除。 |
| 控制台警告活跃方块 / 烹饪锅数量 | 越过性能阈值 | 仅警告，什么都不阻止。调 `performance.*` 阈值或各工作站的 `tick-budget`。 |
| `/reload` 或 PlugMan 操作后报错 | 热重载把活引用拆了 | 永远不要热重载。`/stop` 再开，或用 `/fd reload` / `/ce reload`。 |

## 多人煎锅负载

手持烹饪只检查正在烹饪的会话，没有会话时停止计时任务；配方按材质索引查询，模型在 CE 打包时生成。正常 TPS 下，N 个持续烹饪会话每秒约检查 20N 次、主动刷新进度最多约 5N 次，另有开始、结束和原版背包同步。这是代码操作量，不能代替真实服务器的 MSPT、GC 与网络测量。

关闭 `skillet.handheld.progress-display.enabled` 后，外观不变时不再周期构建和发送显示副本，原版库存同步仍保留烹饪外观。关闭 `skillet.handheld.enabled` 停止手持烹饪，也会跳过后续自动模型生成。

放置煎锅默认每 4 tick 最多轮询 512 个已记录的锅；超过 `skillet.tick-budget` 会延后处理，烹饪也可能变慢。烟雾和声音先判定是否触发，再查询区块观察者；默认概率下约 87.3% 的轮次可跳过查询。`performance.chunk-effect-packet-budget` 限制的是每区块每 tick 的特效广播次数，每次仍会发送给范围内的多名玩家，不能把它视为总网络包上限。密集烹饪区可降低 `skillet.effects.viewer-distance`、特效概率或关闭特效，再通过采样确认收益。

## 按功能采样性能

持有 `farmersdelight.admin` 权限的玩家可运行 `/fd stats profile 200 all`，正常 TPS 下采样约 10 秒；`/fd perf` 是 `/fd stats` 的别名。普通版与 debug 版均可使用，无须打开逐条 debug 日志。期间再次启动采样会被拒绝，避免覆盖别人的结果；结束后仍可用 `/fd stats` 查看上一份结果。发起者移动或离线不影响自动停止。

| 功能参数 | 计时范围 |
| --- | --- |
| `all` | 下列所有功能 |
| `cooking_pot` | 单个厨锅在所属区域实际执行更新 |
| `handheld` | 单个活动手持会话的检查、推进及完成结算 |
| `handheld_display` | 手持外观刷新，包括副本构建及提交；不含网络线程实际发送 |
| `skillet` | 单个放置煎锅更新，包括调用的特效及出餐逻辑 |
| `stove` | 单个炉灶烹饪更新，不含实体烫伤 |

例如 `/fd stats profile 600 handheld` 观察手持负载，再用 `/fd stats profile 600 handheld_display` 检查显示刷新。时长范围为 20 至 12000 tick；省略时长为 200，省略功能为 `all`。采样结束输出调用数、每秒调用数、累计/平均/最大耗时和 P95。全部耗时以毫秒表示，调用率使用实际经过时间。零调用表示窗口内未执行此功能，不表示功能没有成本。

每个功能只保留最近 4096 次调用用于分位计算，总次数、累计和最大值覆盖整次采样。厨锅热点带世界和坐标，最多跟踪先遇到的 4096 个锅，超限会提示遗漏调用数。采样关闭时不调用计时器、不记录样本，也不增加全玩家扫描或逐 tick 日志。

功能计时在实际执行线程上进行，统计在采样窗口内开始并完成的调用，包含被调用逻辑；`handheld_display` 可能已计入 `handheld`，各行不能相加。厨锅调度轮次在 Paper 上包含同步更新，在 Folia 上主要包含调度提交，不能作为区域执行成本。纳秒计时测量的是经过时间，会受线程停顿影响；这些结果不是 CPU 占用、全服 MSPT 或总网络流量。需要调用栈、GC 和全服瓶颈时使用服务端已有的 spark。

`-PdebugTools=true` 构建会额外生成 `farmersdelight-1.0.2-debug.jar`，包含 `/fd debugtools` 和 `/fd debug` 造景工具；普通包仍为 `farmersdelight-1.0.2.jar`。两者是同一个插件，只安装其中一个。

小规模手动测试可用 `/fd debugtools test cooking_pot 64 200`、`skillet`、`stove` 或 `all`。它在玩家附近分批创建测试工作站并自动采样；每批最多处理 16 个位置。用 `/fd debugtools undo` 清理，或用 `/fd debugtools stop` 停止尚未完成的批次。手持路径使用 `/fd debugtools test handheld 1 200`，需要主手煎锅、副手可烹饪食材和附近热源；它使用玩家当前物品，不替换背包内容。

## 对症选择重载命令

| 你改了…… | 执行 |
| --- | --- |
| `config.yml` 的值 | `/fd reload config` |
| `gui.yml` 布局 | `/fd reload gui` |
| 两者，外加配方 / 语言 | `/fd reload all` |
| CraftEngine 资源——模型 / 贴图（需要重建包） | `/ce reload all`（或 `/ce reload pack`） |
| 仅 CraftEngine 配置——方块 / 物品 / 配方，不重建包 | `/ce reload` |
| 任何需要干净重新加载类的东西 | `/stop` 再开服 |

需要时，`/fd cleanup` 会从已加载区块里清掉孤立的插件状态（需要 `farmersdelight.admin`）。

## 下一步

[迁移与升级 →](migration.md)
