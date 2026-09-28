---
icon: download
---

[English](../en/install.md)

# 安装

五步就能从「下载好的 jar」到「手里拿着一口能用的烹饪锅」。

## 1. 放好 jar

把两个插件都丢进服务器的 `plugins/` 目录：

```
plugins/
  CraftEngine-<version>.jar
  farmersdelight-<version>.jar
```

不用设加载顺序——CraftEngine 是硬依赖，服务端始终会先启用它（见 [环境要求](requirements.md)）。

## 2. 首次启动

启动服务器。首次启动时 FarmersDelight 会做两件值得知道的事：

- **释放它的 CraftEngine 资源。** 全套随包的方块 / 物品 / 配方 / 战利品定义会写到
  `plugins/CraftEngine/resources/farmersdelight/`。这一步只在第一次启动时发生。
- **写出自己的配置。** `plugins/FarmersDelight/config.yml`、`display-overrides.yml`（砧板显示覆盖表）、
  `drops.yml`、`world-data.yml`、`gui.yml` 与 `lang/*.yml` 会出现。老版本把这些表放在 `config.yml` 里，
  升级时会被自动搬到新文件并打印一行迁移提示。

控制台会打印一段精简的启动摘要——大约两行，报告调度器、砧板模式、漏斗设置、进度状态，还有一行 *Content ready*，
数出加载了多少烹饪锅和砧板配方、生物掉落规则、宠物食物和进度。看到那行 *Content ready*，就是内容解析成功的第一个确认。

## 3. 生成并托管资源包

资源包归 CraftEngine 管，不归 FarmersDelight。FarmersDelight 只提供 CraftEngine 打进包里的资源文件。按 CraftEngine
自己的文档去生成和发包即可，这里没有任何 FarmersDelight 专属的东西。

短版说明和你大概率会碰到的两种故障，见 [资源包](resource-pack.md)。

## 4. `/ce reload all`

资源在硬盘上、包也在发送之后，执行：

```
/ce reload all
```

`/ce reload all` 会一步完成两件事：重载 CraftEngine 配置**并**重新生成资源包——正是这次重建包，才让新释放出来的
FarmersDelight 模型和贴图送达客户端。单独的 `/ce reload` 只重新读取配置（方块、物品、配方），**不会**重建包；
`/ce reload pack` 则只重建包。每次你新增或改动 `plugins/CraftEngine/resources/farmersdelight/` 下的文件后，都执行一次
`/ce reload all`。

### 数据包里的 FarmersDelight 段落

除了 CE 自己的原生段落，FarmersDelight 还向 CraftEngine 注册了四个属于自己的段落。任何 CE 数据包——不只是
`plugins/CraftEngine/resources/farmersdelight/`，也包括你自己或第三方放进 `resources/` 的数据包——都可以在
`configuration/` 下的任意 YAML 里声明它们，CraftEngine 会把内容交给 FarmersDelight：

| 根键 | 作用 |
| --- | --- |
| `cooking_recipes` | 厨锅配方 |
| `cutting_recipes` | 砧板配方 |
| `special_recipes` | 配方查看界面里的特殊配方卡片 |
| `farmersdelight_advancements` | 附属进度树 |

```yaml
# 例：plugins/CraftEngine/resources/corndelight/configuration/farmersdelight/cooking_pot_recipes.yml
cooking_recipes:
  corndelight_boiled_corn:
    ingredients:
      - '#c:crops/corn'
    result: corndelight:boiled_corn
    experience: 0.15
    cook-time: 100
    category: meals
```

- 段落里的字段写法和 `plugins/FarmersDelight/recipes/*.yml` 一模一样，只有根键名不同（见上表）。
- 数据包内容在插件自己的配方之后加载：同 id 时插件配方优先，并打印一条跳过提示。装在前面的数据包同样优先于装在后面的。
- 文件名随意，只认根键；同一个根键可以分散在多个文件里。
- 进度树的命名空间默认取数据包 `pack.yml` 里的 `namespace`。要让一个数据包发布多个命名空间的进度树，用 CraftEngine 的
  `farmersdelight_advancements#<命名空间>:` 写法。CraftEngine 自己已经占用了 `advancements` 和 `advancement`
  两个根键（它的实现是空的），所以进度树必须用 `farmersdelight_advancements`。
- 这些段落是 CraftEngine 在加载数据包时读取的，改完要执行 `/ce reload all`（或重启）。`/fd reload recipes` 只重读
  `plugins/FarmersDelight/recipes/*.yml`，不会重读数据包。
- 旧写法已经不再读取：数据包根目录的 `advancements.yml`，以及 `<数据包>/farmersdelight/*.yml`。把它们移到
  `<数据包>/configuration/` 下并改用上表的根键。
- 随包的四个附属已经用这套段落：`plugins/CraftEngine/resources/<附属>/configuration/farmersdelight/` 下的
  `cooking_recipes` / `cutting_recipes` / `special_recipes` 段（CrabbersDelight、BrewinAndChewin、BarbequesDelight、
  EndsDelight）。它们原先的 `plugins/<附属>/recipes/{cooking_pot_recipes,cutting_board_recipes,special_recipes}.yml`
  已不再被读取，见[迁移说明](migration.md)。

## 5. 确认方块和物品都在

用 CraftEngine 的给予命令给自己来一个工作站物品：

```
/ce item give <你的名字> farmersdelight:cooking_pot
```

如果你拿到一口贴图和名字都正确的烹饪锅，说明物品解析和资源包两边都在正常工作。把它放在热源上（点燃的营火、
点燃的 `farmersdelight:stove`、岩浆块，或岩浆——见管理员 Wiki 的 `heat-sources` 一节），右键打开它的界面。

再抽查几个 id：

```
/ce item give <你的名字> farmersdelight:cutting_board
/ce item give <你的名字> farmersdelight:skillet
/ce item give <你的名字> farmersdelight:stove
```

如果物品发出来了，但显示**紫黑色缺失贴图**，说明插件加载正常，只是资源包没送到客户端——这是包的问题，不是
FarmersDelight 的问题，去看 [资源包](resource-pack.md)。

如果命令报「未知物品」，说明 CraftEngine 内容没解析成功——去控制台找一条 CraftEngine 行为报错（见
[确认行为已加载](verifying.md)），再执行 `/ce reload`。

## 下一步

[资源包 →](resource-pack.md)
