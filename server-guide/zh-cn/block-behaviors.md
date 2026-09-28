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
| 数据包声明的厨锅 / 砧板 / 特殊配方与进度树 | `plugins/CraftEngine/resources/<数据包>/configuration/**.yml` 中的 `cooking_recipes`、`cutting_recipes`、`special_recipes`、`farmersdelight_advancements` 段 | `/ce reload all` |
| 插件自己的配方文件 | `plugins/FarmersDelight/recipes/*.yml` | `/fd reload recipes` |

CE YAML 有意保持无注释。将它当作纯数据文件，本页才是字段说明。不要使用 Bukkit 的 `/reload`；它会让 CE 注册表和已调度任务处于不确定状态。

执行 `/ce reload` 后，砧板和煎锅显示会按每 tick 的小批次重建。若需要更慢或更快的恢复过程，可在 `config.yml` 调整
`performance.reload-visual-refreshes-per-tick`；默认值为 32，且不会加载区块。

## 通用方块列表写法

`bottom-blocks`、`grow-on.blocks`、`connector-blocks`、`unaffected-blocks` 等行为参数都可使用相同列表：

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

## 分组选项的写法

同一个概念下的多个参数可以**分组**书写，也可以沿用原来的平铺写法 —— 两种都支持，同时存在时分组优先（键名里的 `-` 与 `_`
互相等价，和以前一样）：

```yaml
- type: farmersdelight:stove
  crackle-sound: farmersdelight:block.stove.crackle   # 单个参数保持平铺
  burn:                                               # 等价于 burn-enabled + burn-damage
    enabled: true
    damage: 1.0
  ignite:                                             # 等价于 ignite-enabled + ignite-sound + fire-charge-sound
    enabled: true
    sound: minecraft:item.flintandsteel.use
    fire-charge-sound: minecraft:item.firecharge.use
  extinguish:                                         # 等价于 extinguish-enabled + extinguish-sound + water-extinguish-sound
    enabled: true
    sound: minecraft:block.fire.extinguish
    water-sound: minecraft:entity.generic.extinguish_fire
```

目前已分组的参数（分组写法 ← 旧写法）：

| 行为 | 分组 |
| --- | --- |
| `farmersdelight:stove`（以及附属复用该行为的炉灶） | `burn.{enabled,damage}`、`ignite.{enabled,sound,fire-charge-sound}`、`extinguish.{enabled,sound,water-sound}` |
| `brewinandchewin:fiery_fondue_pot` | `burn.{enabled,damage}` |
| `farmersdelight:cooking_pot` | `support.{display,require-non-full}`、`handle-toggle-sound.{sound,volume,pitch}`、`boil.{sound,soup-sound}`、`sound.{chance,volume,pitch-min,pitch-max}` |
| `farmersdelight:skillet` | `support.{display,require-non-full}` |
| `farmersdelight:organic_compost` | `light.{high-bonus,low-bonus,threshold}`、`water.{bonus}`、`activator.{bonus-per-neighbor}` |
| `farmersdelight:rich_soil` | `mushroom-colony.{brown,red}` |
| `farmersdelight:tall_crop`、`farmersdelight:wild_plant`、`farmersdelight:mushroom_colony` | `light.{requirement,max-requirement}`、`spawn-light.{requirement,max-requirement}`、`bone-meal.{is-target,age-bonus,overflow,success-chance,min-age-bonus,max-age-bonus,climb-chance}` |
| `farmersdelight:tall_crop` | `half.{property,lower-value,upper-value}`、`max-age.{lower,upper}`、`upper.{block,min-age}`、`harvest-tool.{tags,items}` |
| `farmersdelight:mushroom_colony` | `grow-on.{blocks,block-tags}`、`place-on.{blocks,block-tags,overrides-default}`、`harvest-tool.{tags,items}` |
| `farmersdelight:tatami` | `pair.{property,while-sneaking}` |

其余单个参数保持平铺写法：`crackle-sound`、`tool-damage`、`add-food-sound`、`sizzle-sound`、`knife-sound`、`permission`、
`grow-speed`、`max-age`（蘑菇簇的单个上限，与高杆作物的 `max-age.{lower,upper}` 不同）、`rich-soil-block`、
`requires-water`、`bottom-blocks`、`bottom-block-tags` 等。`bottom-*` 之所以不合并，是因为 CraftEngine 自带的
`bush_block` 也用同名平铺参数，两种行为放在同一个方块上时保持同样的写法更不容易看错。CraftEngine 自带的行为
（`crop_block`、`bush_block`、`item_display`、`simple_storage_block` 等）使用它们自己的参数表，不受这里的影响。

## 蘑菇簇

```yaml
- type: farmersdelight:mushroom_colony
  mushroom-type: minecraft:brown_mushroom
  grow-on:
    blocks:
      - farmersdelight:rich_soil
      - farmersdelight:organic_compost
  place-on:
    block-tags:
      - minecraft:mushroom_grow_block
    overrides-default: false
```

`grow-on.blocks` 与 `grow-on.block-tags` 只决定已存在的蘑菇簇是否继续增长，不决定物品能否直接放置。

`place-on.blocks` 与 `place-on.block-tags` 是直接放置规则。`place-on.overrides-default: false` 时，它们会在原版逻辑上追加支撑：
所有 `minecraft:mushroom_grow_block`、`config.yml` 中 `mushroom-colonies.placement.always-valid-supports` 的方块，以及光照不高于上限的实心方块。设为 `true` 后，两个列表成为严格白名单；严格白名单为空时不能放置。

上述四组列表都还接受旧写法（`grow-on-blocks`、`grow-on-block-tags`、`place-on-blocks`、`place-on-block-tags`、
`place-on-overrides-default`）。

其余常用字段有 `age-property`、`max-age`、`grow-speed`、`light.requirement`、`bone-meal.min-age-bonus`、
`bone-meal.max-age-bonus`、`harvest-tool.tags` 和 `harvest-tool.items`（平铺写法的 `light-requirement`、
`bonemeal-min-age-bonus`、`bonemeal-max-age-bonus`、`harvest-tool-tags`、`harvest-tool-items` 同样可读）。

沃土与有机堆肥默认已携带 CE 标签 `minecraft:mushroom_grow_block`，因此随包配置既能在其上放置也能令其继续生长。自定义支撑只要添加该 CE 标签即可加入默认放置规则；若还要让蘑菇簇生长，也应加入 `grow-on.*`。

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
  half:
    property: half
  supporting-property: supporting
  grow-speed: 0.25
  light:
    requirement: 9
  requires-water: true
  upper:
    block: farmersdelight:rice_upper
```

`age-property` 与 `half.property` 为必填。行为会在加载时验证类型，避免出现无法生长或收获的坏作物。`supporting-property` 可选。
还可配置 `max-age.{lower,upper}`、`half.{lower-value,upper-value}`、`reset-on-harvest`、`harvest-tool.{tags,items}`、
`extra-planting-items` 及常规的 `bottom-blocks` / `bottom-block-tags`。这些字段的平铺写法（`half-property`、
`max-age-lower`、`half-lower-value`、`upper-block`、`harvest-tool-tags` 等）继续可读。

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
  support:
    display: true
    require-non-full: true
  handle-toggle-sound:
    sound: minecraft:block.lantern.place
    volume: 0.7
    pitch: 1.0
```

`support.display` 控制该工作站自身的托盘 / 把手渲染状态。自定义模型没有对应外观时设为 `false`。
`support.require-non-full` 会让完整方块上方不显示托盘；需要让模型覆盖完整方块时设为 `false`。
`place-tray-on-open` 仅适用于厨锅，决定打开界面时是否立刻刷新支撑状态。这两项仍接受旧写法
（`display-support`、`require-non-full-support`）；把手音效最早写成 `handle-toggle-sound: <音效 id>` 搭配
`handle-toggle-sound-volume` / `-pitch`，这种写法也仍然可读。

### 烹饪消耗食材后的返还物品

厨锅在烹饪完成、扣除食材时，按下面的顺序决定每种食材返还什么（`container-returns` 只是优先级最高的覆盖表，
不是唯一来源）：

1. `config.yml` 的 `container-returns`（键可以是 CE 自定义 id，也可以是原版 id）；
2. 物品自己声明的 CraftEngine `craft-remainder`：`fixed` 恒定返还，`recipe_based` 按**正在烹饪的菜谱 id**匹配，
   匹配不到则用它配置的 `fallback`；
3. 物品的 `use_remainder` 组件（原版语义：饮用 / 食用后留下什么）；
4. 物品材质的原版合成返还物 —— 对 CE 物品即其基础材质的返还物，因此基于 `minecraft:honey_bottle` 的自定义饮料
   无需任何配置就会返还玻璃瓶；
5. 原版没有声明返还物的桶 / 瓶兜底（牛奶桶、水桶、熔岩桶 → 桶；蜂蜜瓶 → 玻璃瓶）。

所以 `farmersdelight:milk_bottle: minecraft:glass_bottle` 这类条目通常已不再必要，只在需要覆盖上述推断（或把某个
返还物改成别的物品、用 `""` 关闭返还）时才需要写。厨锅专属的额外兜底（鱼桶、炖菜、药水等）仍可通过
`cooking-pot.ingredient-remainders` 补充。

### 厨锅配方的必需容器（可推断）

厨锅配方里的 `container:` 字段现在**可以省略**：省略时插件按上面那套解析顺序去问**结果物品**自己声明的返还物，
得到的就是这条配方需要的容器（汤类结果 → 碗，饮料结果 → 玻璃瓶），因为"吃/喝完剩下什么"正是盛装它的容器。
因此手写配方和附属包不必再为每条汤 / 饮料重复写 `container:`；配方编辑器保存时也会把推断出的容器**显式写入**
文件，方便你后续查看与修改。

要覆盖或关闭推断：

| 写法 | 含义 |
| --- | --- |
| `container: minecraft:bowl` | 显式指定，覆盖推断 |
| `container: minecraft:bowl`（自定义物品则写对象形式） | 同上，可用完整物品快照 |
| `container: none`（也接受 `air` / 空字符串） | 明确声明「这条配方不需要容器」，忽略结果里的返还物 |

推断的结果里如果是**工具类**返还物（玉米热狗的`minecraft:stick`、火腿的`minecraft:bone`），不会被当成容器 ——
默认排除列表见 `config.yml` 的 `cooking-pot.container-inference.excluded-remainders`（可用原版 id 或 CE 自定义 id，
大小写不敏感）；`cooking-pot.container-inference.enabled: false` 可整体关闭推断，改为要求每条配方显式写
`container:`（或 `container: none`）。

推断只在**读取配方文件**时发生；通过 API（`FarmersDelightApi.registerCookingPotRecipe`）注册的配方仍以调用方传入的
容器为准。启动日志在真的有推断时会输出一行「已按配方结果推断 N 个厨锅配方的必需容器」（属于 `recipe` 调试分类，
需 `debug.categories` 打开），便于确认。

把手音效也按单个厨锅行为配置，音量设为 `0` 即静音。`skillet` 行为同样接受 `permission`、`support.display` 和
`support.require-non-full`，含义相同；原有的 `add-food-sound` 与 `sizzle-sound` 仍可用。它们都放在 CE 方块定义内，
因此不同模型的自定义工作站可以各自设置。

### 炉灶（stove）

`farmersdelight:stove` 要求方块声明布尔属性 **`fire`**（名字固定，不可改）。缺少这个属性、或同名属性不是布尔类型时，
方块在加载阶段就会报错并指出配置节点与属性名，做法与 `craftengine:crop_block` 要求 `age` 相同 —— 点燃和熄灭都要写这个
属性，没有它炉灶就永远无法切换火焰。

```yaml
- type: farmersdelight:stove
  crackle-sound: farmersdelight:block.stove.crackle
  burn:
    enabled: true
    damage: 1.0
  ignite:
    enabled: true
    sound: minecraft:item.flintandsteel.use
    fire-charge-sound: minecraft:item.firecharge.use
  extinguish:
    enabled: true
    sound: minecraft:block.fire.extinguish
    water-sound: minecraft:entity.generic.extinguish_fire
  tool-damage: 1
```

| 参数 | 默认值 | 含义 |
| --- | --- | --- |
| `crackle-sound` | `farmersdelight:block.stove.crackle` | 点燃时偶尔播放的噼啪声。 |
| `burn.enabled` | `true` | 站在点燃炉灶中央烤面上是否受到伤害。 |
| `burn.damage` | `1.0` | 每次烫伤伤害。 |
| `ignite.enabled` | `true` | 是否允许打火石 / 火焰弹点燃。 |
| `extinguish.enabled` | `true` | 是否允许锹 / 水桶熄灭。 |
| `ignite.sound` | `minecraft:item.flintandsteel.use` | 打火石点燃音效。 |
| `ignite.fire-charge-sound` | `minecraft:item.firecharge.use` | 火焰弹点燃音效。 |
| `extinguish.sound` | `minecraft:block.fire.extinguish` | 锹熄灭音效。 |
| `extinguish.water-sound` | `minecraft:entity.generic.extinguish_fire` | 水桶熄灭音效。 |
| `tool-damage` | `1` | 锹 / 打火石每次消耗的耐久。 |

上表的分组参数都还能用旧写法（`burn-enabled`、`ignite-sound`、`water-extinguish-sound` 等），两种同时存在时分组优先；
旧资源包不需要改动。

声音 ID 必须形如 `minecraft:block.fire.extinguish`；写错会在方块加载时报错并给出配置路径，而不是静默回退到原版音效。
点燃与熄灭两种状态互斥：点燃状态只接受锹 / 水桶，未点燃状态只接受打火石 / 火焰弹；水桶会留下空桶（创造模式不消耗）。

这四个交互由行为自身实现，与原模组 `AbstractStoveBlock` 的 `tryToIgnite` / `tryToExtinguish` 一致，因此任何复用该行为的
附属炉灶（例如 Ends Delight 的末地炉灶）都自动获得这些交互，不需要在资源包里重复写 `events`。

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
