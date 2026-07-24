---
icon: wrench
---

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
| FarmersDelight 从不启用，日志说缺依赖 | 没装 CraftEngine | 装一个兼容的 CraftEngine（针对 **26.7** 编译）；它是硬 `depend`。 |
| 插件加载失败，报版本不支持 / 类错误 | 服务端低于 MC 1.21 或 Java 低于 21 | 用 **Paper/Folia 1.21.1** 跑在 **Java 21+** 上。 |
| 自定义物品 / 方块显示紫黑贴图 | 客户端没用上当前资源包 | 执行 **`/ce reload all`** 重建包（单独的 `/ce reload` 不会）；确认玩家接受了包。见 [资源包](resource-pack.md)。 |
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
