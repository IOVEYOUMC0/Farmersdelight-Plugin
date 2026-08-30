---
icon: blocks
---

# 方块行为配置

[English](../en/block-behaviors.md)

FarmersDelight 是 Farmer's Delight 的 Paper/Folia 移植。CraftEngine 负责物品、方块、模型、战利品和配方数据；
FarmersDelight 在此基础上补足 CE 本身不提供的有状态玩法：工作站、作物、土壤、绳索、蘑菇簇及其联动。附属也沿用同一模型。

## 配置该放在哪里

| 要改的内容 | 文件 | 应用方式 |
| --- | --- | --- |
| 物品、方块、标签、战利品或 CE 配方 | `plugins/CraftEngine/resources/farmersdelight/configuration/*.yml` | `/ce reload all` |
| 运行参数、权限、性能、显示 | `plugins/FarmersDelight/config.yml` | `/fd reload config` 或重启 |
| 厨锅、砧板和特殊配方 | FD/附属配方配置 | `/ce reload all` |

CE YAML 有意保持无注释。将它当作纯数据文件，本页才是字段说明。不要使用 Bukkit 的 `/reload`；它会让 CE 注册表和已调度任务处于不确定状态。

执行 `/ce reload` 后，砧板和煎锅显示会按每 tick 的小批次重建。若需要更慢或更快的恢复过程，可在 `config.yml` 调整
`performance.reload-visual-refreshes-per-tick`；默认值为 32，且不会加载区块。

## 通用方块列表写法

`bottom-blocks`、`grow-on-blocks`、`connector-blocks`、`unaffected-blocks` 等行为参数都可使用相同列表：

```yaml
some-blocks:
  - minecraft:dirt
  - farmersdelight:rich_soil
  - '#minecraft:mushroom_grow_block'
  - '#myaddon:fertile_soils'
```

- `minecraft:*` 是原版方块。
- `namespace:id` 是 CE 自定义方块 ID。
- `#namespace:tag` 是标签。YAML 中 `#` 会开始注释，因此必须加引号。
- 原版标签经 Bukkit 解析；CE 方块会匹配自身 `settings.tags`。因此自定义方块的 `settings.tags` 能被所有接受方块列表的 FD 行为复用。
- 正常 CE 土壤规则还可接受带状态的原版方块，例如 `minecraft:farmland[moisture=7]`。

## 蘑菇簇

```yaml
- type: farmersdelight:mushroom_colony
  mushroom-type: minecraft:brown_mushroom
  grow-on-blocks:
    - farmersdelight:rich_soil
    - farmersdelight:organic_compost
  place-on-block-tags:
    - minecraft:mushroom_grow_block
  place-on-overrides-default: false
```

`grow-on-blocks` 与 `grow-on-block-tags` 只决定已存在的蘑菇簇是否继续增长，不决定物品能否直接放置。

`place-on-blocks` 与 `place-on-block-tags` 是直接放置规则。`place-on-overrides-default: false` 时，它们会在原版逻辑上追加支撑：
所有 `minecraft:mushroom_grow_block`、`config.yml` 中 `mushroom-colonies.placement.always-valid-supports` 的方块，以及光照不高于上限的实心方块。设为 `true` 后，两个列表成为严格白名单；严格白名单为空时不能放置。

其余常用字段有 `age-property`、`max-age`、`grow-speed`、`light-requirement`、`bonemeal-min-age-bonus`、
`bonemeal-max-age-bonus`、`harvest-tool-tags` 和 `harvest-tool-items`。

沃土与有机堆肥默认已携带 CE 标签 `minecraft:mushroom_grow_block`，因此随包配置既能在其上放置也能令其继续生长。自定义支撑只要添加该 CE 标签即可加入默认放置规则；若还要让蘑菇簇生长，也应加入 `grow-on-*`。

## 绳索

```yaml
- type: farmersdelight:rope
  placement-mode: vanilla
  connection-mode: restricted
```

`placement-mode` 决定放置瞬间的连接方式：

| 值 | 含义 |
| --- | --- |
| `vanilla` | 水平放置连接完整实心面；竖直放置和向下放绳只连接绳索、栏杆/玻璃板与墙。 |
| `restricted` | 只连接绳索、栏杆/玻璃板与墙。 |
| `solid-face` | 每种放置方式都可连接允许的完整实心面。 |

`connection-mode` 决定后续邻居刷新和代码放置绳索的连接方式，可用 `restricted`（默认）或 `solid-face`。

`connector-blocks` 非空时会替换默认的栏杆/玻璃板/墙连接集合。
`connection-exceptions` 存在时会替换默认的实心面排除集合。所有不应连上实心面的方块/标签都要写入；原默认排除为屏障、树叶、潜影盒、南瓜和西瓜。无论这两个列表如何配置，绳索始终连接其他绳索。

## 沃土与作物

```yaml
- type: farmersdelight:rich_soil
  boost-chance: 0.2
  brown-mushroom-colony: farmersdelight:brown_mushroom_colony
  red-mushroom-colony: farmersdelight:red_mushroom_colony
  brown-mushroom-blocks: [minecraft:brown_mushroom, farmersdelight:brown_mushroom]
  red-mushroom-blocks: [minecraft:red_mushroom, farmersdelight:red_mushroom]
  unaffected-blocks: ['#farmersdelight:wild_crops']
```

`boost-chance` 是随机刻加速生长的概率。`brown-mushroom-blocks` 与 `red-mushroom-blocks` 决定沃土把哪些蘑菇转成蘑菇簇；二者默认就是示例中的两个值。`unaffected-blocks` 让植物不受生长加速影响。

`farmersdelight:rich_soil_farmland` 使用 `boost-chance`、`moisture-property` 与 `rich-soil-block`。
湿度属性必须存在且为整数。作物通常应组合 CE 自带的 `crop_block` 或 `bush_block` 与 FD 的 `tall_crop` 或 `tomato_vine`；这些并列行为是有意设计，CE 会同时分派。

## 高杆作物与番茄

```yaml
- type: farmersdelight:tall_crop
  age-property: age
  half-property: half
  supporting-property: supporting
  grow-speed: 0.25
  light-requirement: 9
  requires-water: true
  upper-block: farmersdelight:rice_upper
```

`age-property` 与 `half-property` 为必填。行为会在加载时验证类型，避免出现无法生长或收获的坏作物。`supporting-property` 可选。
还可配置 `max-age-lower`、`max-age-upper`、`half-lower-value`、`half-upper-value`、`reset-on-harvest`、
`harvest-tool-tags`、`harvest-tool-items`、`extra-planting-items` 及常规的 `bottom-blocks` / `bottom-block-tags`。

番茄的三个 ID 必须在每个藤蔓阶段保持一致：

```yaml
- type: farmersdelight:tomato_vine
  blocks:
    budding: farmersdelight:budding_tomatoes
    tomatoes: farmersdelight:tomatoes
    crop-on-rope: farmersdelight:tomato_crop_on_rope
  min-light: 9
  max-stack-height: 3
```

悬挂阶段存在 CE `bush_block.max-height` 时，它就是实际攀爬上限。`max-stack-height` 仅为无该行为时的回退值，不要无意设置互相冲突的值。

## 厨锅与煎锅

### 自定义厨锅示例

在 CraftEngine 方块配置中，为自定义厨锅使用 `farmersdelight:cooking_pot` 行为，并在 `custom` 中声明配方组和槽位数量：

```yaml
block:
  myaddon:custom_pot:
    behavior:
      - type: farmersdelight:cooking_pot
        custom:
          id: myaddon:custom_pot
          input-slots: 9
          pending-output-slots: 3
          output-slots: 3
          container-slots: 3
```

配方文件使用同一个组 ID：

```yaml
custom_cooking_pot_recipes:
  myaddon:custom_pot:
    soup:
      ingredients:
        - minecraft:carrot
        - minecraft:potato
      result:
        id: minecraft:rabbit_stew
        count: 1
      cooking-time: 200
      container: minecraft:bowl
```

`custom.id` 必须与 `custom_cooking_pot_recipes` 下的组名一致。自定义厨锅的输入槽最多可配置到 54 个；实际界面槽位还需要在 `gui.yml` 的 `recipe-view-gui.recipe-detail-cooking-pot-guis` 下为该组提供布局，否则会回退到默认厨锅布局。配方中的物品、标签和容器都按 FD 的统一解析规则处理，附属也可以通过 `FarmersDelightApi.registerCookingPotRecipe` 注册。

```yaml
- type: farmersdelight:cooking_pot
  permission: farmersdelight.use.cooking_pot
  place-tray-on-open: true
  display-support: true
  require-non-full-support: true
  handle-toggle-sound: minecraft:block.lantern.place
  handle-toggle-sound-volume: 0.7
  handle-toggle-sound-pitch: 1.0
```

`display-support` 控制该工作站自身的托盘 / 把手渲染状态。自定义模型没有对应外观时设为 `false`。
`require-non-full-support` 会让完整方块上方不显示托盘；需要让模型覆盖完整方块时设为 `false`。
`place-tray-on-open` 仅适用于厨锅，决定打开界面时是否立刻刷新支撑状态。

把手音效也按单个厨锅行为配置，音量设为 `0` 即静音。`skillet` 行为同样接受 `permission`、`display-support` 和
`require-non-full-support`，含义相同；原有的 `add-food-sound` 与 `sizzle-sound` 仍可用。它们都放在 CE 方块定义内，
因此不同模型的自定义工作站可以各自设置。

## 其他已注册行为

| 行为 | 用途 | 主要配置位置 |
| --- | --- | --- |
| `basket` | 简单容器方块 | 方块的存储与显示设置。 |
| `connected_rug`、`double_block`、`tatami` | 连通/成对装饰方块 | 属性名、配对 ID 与显示物品。 |
| `cooking_pot`、`cutting_board`、`skillet`、`stove` | 有状态烹饪工作站 | 方块行为识别工作站并承载方块专属模型/交互项；配方和共享参数位于 FD 配置/配方文件。 |
| `organic_compost` | 堆肥转换与蘑菇来源 | 目标沃土 ID 与蘑菇簇 ID。 |
| `wild_plant`、`wild_rice` | 野生植物 | 土壤/水和扩散参数。 |
| `conditional_block_planting` | 物品行为，不是方块行为 | 物品 `behavior.rules`，将被点击的 CE 方块映射为要种下的方块。 |

## 验证一次修改

1. 修改前备份资源文件。
2. 执行 `/ce reload all`。
3. 若有行为报错，先读第一条；它会指出方块节点和缺失或类型不对的属性。
4. 在无保护区域测试方块，再在服务器实际的保护配置下测试。

随包默认值以还原原模组为目标。比起修改已有世界正在使用的定义，更建议新增自定义方块 ID。
