# FarmersDelight Wiki

[English](README.md) | **中文**

[![bStats](https://bstats.org/signatures/bukkit/FarmersDelight.svg)](https://bstats.org/plugin/bukkit/FarmersDelight/32571)

> 本插件通过 [bStats](https://bstats.org) 收集匿名统计数据（服务器数 / 玩家数、服务端软件与版本分布等）。服主可在 `plugins/bStats/config.yml` 中全局关闭。

FarmersDelight 是一个基于 CraftEngine 26.5 的 Farmer's Delight 风格玩法插件。CraftEngine 负责自定义物品、方块、家具、模型、字体图片和标签，本插件负责把这些资源接成真正能玩的服务端逻辑。

## 1. 插件做了什么

本插件新增并实现了这些系统：

- 厨锅：6 个原料槽、容器槽、待输出槽、输出槽、烹饪进度、世界粒子、打包掉落、内容提示；漏斗交互可配置开启。
- 砧板：放置食材、小刀/工具处理、概率多输出、配方查看；可配置是否允许副手小刀/副手食材。
- 炉灶：基于原版营火配方烹饪，带 CE 物品显示。
- 煎锅：基于原版营火配方烹饪，带 CE 物品显示；默认不通过漏斗传导热量。
- 作物与采集：水稻、野生水稻、高作物、成熟收割、小刀收割草秆。
- 小刀战利品：用小刀击杀成年生物时额外掉落火腿、皮革、羽毛等，可配置概率和抢夺加成。
- 食物效果：宠物食物、舒适效果、营养效果、容器返还。
- 配方查看 GUI：厨锅和砧板配方列表、详情页、标签/多选原料展示。
- 配方编辑 GUI：管理员可在游戏内可视化创建、编辑、删除厨锅与砧板配方，支持标签原料（按物品反查标签 + 排除项编辑）、或选 `a|b` 原料、自定义厨锅槽位；不输入配方 ID 时打开配方列表点击即可编辑。
- 进度系统：基于 UltimateAdvancementAPI（可选依赖）的 Farmer's Delight 风格进度，toast 与聊天提示按玩家客户端语言本地化、使用 CE 物品图标；未安装该插件时自动关闭，不影响其它功能。

## 2. 安装环境

- Java 21
- Paper / Purpur 1.21+
- CraftEngine 26.5
- 可选依赖：UltimateAdvancementAPI（进度系统）、AuraSkills（厨锅经验奖励）。未安装对应插件时该功能自动关闭，不影响插件其它部分。进度功能支持的 Minecraft 版本由所安装的 UltimateAdvancementAPI 构建决定（随附构建覆盖 1.21.x 与 26.1.x）。

插件内调度通过 scheduler adapter 适配 Paper 与 Folia；在 Folia 上优先走全局、区域或实体调度器。Folia 环境建议在测试服验证厨锅/砧板容器、方块存储、显示同步和异步保存流程。

安装步骤：

1. 将 `farmersdelight-1.0.0.jar` 放入服务器 `plugins` 目录。
2. 安装 CraftEngine 26.5。
3. 启动服务器。首次启动时本插件会自动把内置 CraftEngine 资源配置整包释放到 `plugins/CraftEngine/resources/farmersdelight`，之后每次启动会补回被删除/缺失的文件（见下方 `craftengine-resources.auto-completion`）。
4. 等待生成插件配置文件。
5. 修改配置后可使用 `/fd reload`；修改 jar、CraftEngine 资源或资源包后建议完整重启服务器。
6. 如需进度系统，另行安装 UltimateAdvancementAPI 插件（可选）。

### 2.1 CraftEngine 资源自动补齐

本插件自带的 CraftEngine 资源会在首次启动整包释放到 `plugins/CraftEngine/resources/farmersdelight`，之后每次启动还会自动补回被删除/缺失的文件：

```yaml
craftengine-resources:
  auto-completion: true
```

如果你手动删除了某些内置配置且不希望被重新加回，设为 `false`（首次整包释放不受该开关影响）。

生成文件：

```text
plugins/FarmersDelight/config.yml
plugins/FarmersDelight/gui.yml
plugins/FarmersDelight/recipes/cooking_pot_recipes.yml
plugins/FarmersDelight/recipes/cutting_board_recipes.yml
```

厨锅、砧板、炉灶和煎锅的方块运行数据都保存在 CraftEngine BlockEntity 数据中。

默认 `config.yml` 按功能归属组织：厨锅相关选项在 `cooking-pot`，砧板在 `cutting-board`，煎锅在 `skillet`，炉灶在 `stove`。旧版本的分散路径仍会被读取作为兼容 fallback，但新配置建议使用默认文件里的新路径。

调试日志在 `debug` 中配置。正常服务器保持关闭；排查问题时需要同时开启总开关并指定分类。`categories` 留空不会输出分类调试日志；需要临时全开时写 `all` 或 `*`。

```yaml
debug:
  enabled: true
  categories:
    - cooking_pot
```

## 3. CraftEngine 侧需要配置什么

CraftEngine 侧负责“资源长什么样、叫什么、有哪些标签”。本插件通过 CE ID 调用这些资源。

通常需要在 CraftEngine 资源包里准备：

- 物品：小刀、食材、熟食、容器、GUI 图标、进度图标等。
- 方块/家具：厨锅、砧板、炉灶、煎锅、托盘、水稻、野生作物、蘑菇群落、绳子、榻榻米等。
- 标签：例如 `farmersdelight:knives`、`farmersdelight:heat_sources`。
- 语言：物品名称和 lore 建议写在 CE 的语言/显示配置里，插件 GUI 会优先读取物品自己的显示名称。
- 图片字体：`gui.yml` 里使用的 `<image:farmersdelight:cooking_pot_gui>`、`<image:farmersdelight:recipelist>` 等需要在 CE 资源侧存在。

本插件注册的主要行为 ID：

```text
farmersdelight:cooking_pot
farmersdelight:cutting_board
farmersdelight:skillet
farmersdelight:stove
farmersdelight:tall_crop
farmersdelight:wild_rice
farmersdelight:rope
farmersdelight:tatami
farmersdelight:mushroom_colony
farmersdelight:upper_half_loot_relay
```

CE 方块或家具配置中需要把对应行为挂到对应资源上。例如厨锅挂 `farmersdelight:cooking_pot`，砧板挂 `farmersdelight:cutting_board`，炉灶挂 `farmersdelight:stove`。CE 配置语法以 CraftEngine 26.5 的资源配置为准。

### 3.1 方块行为挂载

行为参数写在 CraftEngine 方块/家具资源的 `behavior` 下。常见写法如下，具体缩进以 CE 资源文件结构为准：

```yaml
behavior:
  - type: farmersdelight:cooking_pot
    permission: farmersdelight.use.cooking_pot
```

如果同一个资源还挂了 CE 自身行为或其他插件行为，继续在 `behavior` 列表里追加即可。

### 3.2 厨锅行为

默认厨锅只需要挂载行为：

```yaml
behavior:
  - type: farmersdelight:cooking_pot
```

可用参数：

| 参数 | 默认值 | 作用 |
| --- | --- | --- |
| `permission` | `farmersdelight.use.cooking_pot` | 打开厨锅需要的权限。写空字符串可关闭本插件的权限检查。 |
| `open-while-sneaking` | `false` | 潜行右键是否也打开厨锅。关闭时潜行交互会让给其他行为。 |
| `place-tray-on-open` | `true` | 打开厨锅时是否自动同步/放置锅下托盘显示。 |
| `boil-sound` | 内置沸腾音效 | 普通烹饪沸腾音效 ID。 |
| `soup-boil-sound` | 内置汤类沸腾音效 | 汤类烹饪沸腾音效 ID。 |
| `sound-chance` | 主配置默认值 | 每次烹饪 tick 播放声音的概率。 |
| `sound-volume` | 主配置默认值 | 沸腾音效音量。 |
| `sound-pitch-min` | 主配置默认值 | 随机音高下限。 |
| `sound-pitch-max` | 主配置默认值 | 随机音高上限。 |
| `data-key` | `farmersdelight:cooking_pot` | CE BlockEntity 中保存厨锅内容的数据键。已放置方块改这个值会让旧内容按新键读取不到，除非自行迁移。 |
| `custom` | 未启用 | 自定义厨锅槽位、配方组和 GUI 标题。 |

自定义厨锅示例：

```yaml
behavior:
  - type: farmersdelight:cooking_pot
    custom:
      id: large_pot
      title: "<image:farmersdelight:large_cooking_pot_gui>"
      input-slots: 9
      pending-output-slots: 2
      output-slots: 3
      container-slots: 2
```

`custom` 参数说明：

| 参数 | 作用 |
| --- | --- |
| `id` | 自定义厨锅 ID。配方文件中使用 `custom_cooking_pot_recipes.<id>` 写这个锅专用配方；`gui.yml` 可用同名厨锅 GUI 配置覆盖界面。 |
| `title` / `gui-title` | 打开 GUI 时使用的标题。通常写图片字体，用来覆盖自定义界面贴图。 |
| `input-slots` | 原料槽数量。 |
| `pending-output-slots` | 待输出槽数量。烹饪完成但尚未转入输出槽的产物会暂存在待输出槽。 |
| `output-slots` | 成品输出槽数量。 |
| `container-slots` | 容器槽数量，例如碗、瓶等。 |

不写 `custom` 时使用默认厨锅布局，不影响原厨锅。

自定义大锅完整配置入口通常有四处，`id` 必须保持一致。下面以 `large_pot` 为例：

1. CE 方块行为，决定这个锅的实际槽位和配方组 ID：

```yaml
behavior:
  - type: farmersdelight:cooking_pot
    custom:
      id: large_pot
      input-slots: 9
      pending-output-slots: 2
      output-slots: 3
      container-slots: 2
```

2. `gui.yml` 的锅本体 GUI，决定玩家打开锅时看到的槽位布局：

```yaml
cooking-pot-guis:
  large_pot:
    title: "<white><offset><icon><shift:-1600>Large Cooking Pot"
    fillers-enabled: true
    rows: 5
    layout:
      - "XIIIIIPBX"
      - "XIIIIIPBX"
      - "RXXXXXXXO"
      - "XXHXXCCOO"
      - "XXXXXXXXX"
    legend:
      I: ingredient
      H: heat
      C: container
      P: progress
      B: buffer
      O: output
      R: recipe
      X: decoration
    items:
      background:
        material: GRAY_STAINED_GLASS_PANE
        item_model: "air"
        hide-tooltip: true
        name: " "
      decoration:
        material: BROWN_STAINED_GLASS_PANE
        name: " "
      recipe:
        material: KNOWLEDGE_BOOK
        name: "查看烹饪配方"
```

3. `gui.yml` 的配方详情 GUI，决定配方页能显示多少个材料。扩展输入槽后建议同步扩展这里的 `ingredient` 槽：

```yaml
recipe-view-gui:
  recipe-detail-cooking-pot-guis:
    large_pot:
      title: "<white><offset><icon><shift:-1600>Large Pot Recipe"
      rows: 6
      layout:
        - "B######P#"
        - "###III###"
        - "###III###"
        - "###III###"
        - "#####C#R#"
        - "#########"
      legend:
        I: ingredient
        C: container
        R: result
        B: back
        P: fill
        "#": background
      items:
        background:
          material: GRAY_STAINED_GLASS_PANE
          item_model: "air"
          hide-tooltip: true
          name: " "
        back:
          material: ARROW
          name: "返回"
        fill:
          material: HOPPER
          name: "填入材料"
```

4. `recipes/cooking_pot_recipes.yml` 的自定义配方组：

```yaml
custom_cooking_pot_recipes:
  large_pot:
    large_stew:
      ingredients:
        - "minecraft:beef"
        - "minecraft:carrot"
        - "minecraft:potato"
        - "farmersdelight:onion"
      container: "minecraft:bowl"
      result: "farmersdelight:beef_stew"
      cook-time: 200
```

如果自定义锅有专用配方但没有 `recipe-detail-cooking-pot-guis.<id>`，插件会在从该锅打开配方 GUI 时向控制台输出 warning，并回退使用默认 `recipe-detail-cooking-pot`。如果某个配方材料数超过详情页 `ingredient` 槽数量，也会在打开该 GUI 时向控制台提示。

### 3.3 砧板行为

```yaml
behavior:
  - type: farmersdelight:cutting_board
    tool-tags:
      - farmersdelight:knives
    tool-items:
      - minecraft:shears
    max-stack-amount: 64
```

可用参数：

| 参数 | 默认值 | 作用 |
| --- | --- | --- |
| `tool-tags` | `farmersdelight:knives`、`farmersdelight:axes`、`farmersdelight:pickaxes` | 可触发砧板配方的工具标签。支持带不带 `#` 的标签写法。 |
| `tool-items` | `minecraft:shears` | 可直接作为砧板工具的具体物品 ID。 |
| `knife-sound` | `farmersdelight:block.cutting_board.knife` | 处理食材时播放的音效。 |
| `max-stack-amount` | `64` | 砧板允许放置的最大物品数量。实际还会受物品自身最大堆叠数限制。 |
| `data-key` | `farmersdelight:cutting_board` | CE BlockEntity 中保存砧板内容的数据键。 |

砧板是否启用堆叠由 `config.yml` 的 `cutting-board.interaction-mode` 控制：`stacking` 启用堆叠，`offhand` 启用副手交互。

### 3.4 炉灶与煎锅行为

炉灶：

```yaml
behavior:
  - type: farmersdelight:stove
    crackle-sound: farmersdelight:block.stove.crackle
```

| 参数 | 默认值 | 作用 |
| --- | --- | --- |
| `crackle-sound` | `farmersdelight:block.stove.crackle` | 炉灶烹饪时播放的燃烧音效。 |

煎锅：

```yaml
behavior:
  - type: farmersdelight:skillet
    add-food-sound: farmersdelight:block.skillet.add_food
    sizzle-sound: farmersdelight:block.skillet.sizzle
```

| 参数 | 默认值 | 作用 |
| --- | --- | --- |
| `add-food-sound` | `farmersdelight:block.skillet.add_food` | 放入食物时播放的音效。 |
| `sizzle-sound` | `farmersdelight:block.skillet.sizzle` | 烹饪时播放的滋滋声。 |

### 3.5 作物、绳子、榻榻米和蘑菇群落行为

高作物：

```yaml
behavior:
  - type: farmersdelight:tall_crop
    age-property: age
    half-property: half
    upper-block: farmersdelight:tomatoes_top
    max-age-lower: 4
    max-age-upper: 3
    grow-speed: 0.25
    light-requirement: 9
    bottom-block-tags:
      - minecraft:dirt
```

| 参数 | 默认值 | 作用 |
| --- | --- | --- |
| `age-property` | `age` | CE 方块状态中表示生长阶段的属性名。 |
| `half-property` | `half` | CE 方块状态中表示上下半部分的属性名。 |
| `supporting-property` | `supporting` | 标记支撑状态的布尔属性名。没有该属性时会跳过。 |
| `grow-speed` | `0.25` | 随机 tick 生长概率系数。 |
| `light-requirement` | `9` | 生长所需最低亮度。 |
| `is-bone-meal-target` | `true` | 是否允许骨粉催熟。 |
| `random-bone-meal-growth` | `false` | 骨粉增长量是否使用随机范围。 |
| `bone-meal-min` / `bone-meal-max` | `1` / `2` | 骨粉增长的最小/最大阶段。 |
| `max-age-lower` / `max-age-upper` | 从属性推断 | 下半/上半最大成熟阶段。收割与掉落应以成熟状态为准。 |
| `half-lower-value` / `half-upper-value` | 从属性推断 | 下半/上半状态值。 |
| `requires-water` | `false` | 放置和存活是否需要水。 |
| `reset-on-harvest` | `true` | 成熟收割后是否重置为未成熟，而不是直接破坏。 |
| `upper-block` | 空 | 下半放置时自动生成的上半方块 ID。 |
| `harvest-tool-tags` | 空 | 可收割的工具标签。 |
| `harvest-tool-items` | 空 | 可收割的具体物品 ID。 |
| `bottom-blocks` | 空 | 可种植/支撑的方块。支持原版方块、原版 BlockData、CE 方块 ID、CE 状态字符串。 |
| `bottom-block-tags` | 空 | 可种植/支撑的方块标签。支持原版方块标签和 CE 方块标签。 |

野生水稻：

```yaml
behavior:
  - type: farmersdelight:wild_rice
    requires-water: true
    bottom-blocks:
      - minecraft:dirt
      - minecraft:grass_block
      - minecraft:mud
```

`farmersdelight:wild_rice` 会处理上下半结构、水源恢复和土壤校验。`requires-water` 默认 `true`，`bottom-blocks` / `bottom-block-tags` 的写法与高作物一致。

蘑菇群落：

```yaml
behavior:
  - type: farmersdelight:mushroom_colony
    age-property: age
    max-age: 3
    grow-speed: 0.25
    light-requirement: 0
    mushroom-type: minecraft:red_mushroom
    grow-on-blocks:
      - minecraft:mycelium
```

| 参数 | 默认值 | 作用 |
| --- | --- | --- |
| `age-property` | `age` | 生长阶段属性名。 |
| `max-age` | 从属性推断 | 最大成熟阶段。 |
| `grow-speed` | `0.25` | 随机 tick 生长概率系数。 |
| `light-requirement` | `0` | 生长所需最低亮度。 |
| `harvest-tool-tags` / `harvest-tool-items` | 空 | 可采集工具。 |
| `mushroom-type` | 空 | 群落对应的蘑菇物品 ID。 |
| `grow-on-blocks` / `grow-on-block-tags` | 空 | 可生长的方块或标签；也兼容 `bottom-blocks` / `bottom-block-tags`。 |

榻榻米：

```yaml
behavior:
  - type: farmersdelight:tatami
    block-id: farmersdelight:tatami
    facing-property: facing
    paired-property: paired
    pair-while-sneaking: false
```

| 参数 | 默认值 | 作用 |
| --- | --- | --- |
| `block-id` | 内置榻榻米 ID | 用于判断相邻方块是否是同类榻榻米。 |
| `facing-property` | `facing` | 朝向属性名。 |
| `paired-property` | `paired` | 是否已配对的布尔属性名。 |
| `pair-while-sneaking` | `false` | 潜行放置时是否也自动配对。 |

绳子：

```yaml
behavior:
  - type: farmersdelight:rope
```

`farmersdelight:rope` 会读取方块状态里的 `north`、`south`、`east`、`west` 布尔属性并自动连接相邻绳子。该行为没有额外配置参数。

上半掉落转发：

```yaml
behavior:
  - type: farmersdelight:upper_half_loot_relay
    lower-half-direction: DOWN
    require-matching-lower-half: true
    require-matching-block: true
    half-property: half
    half-lower-value: lower
    half-upper-value: upper
```

该行为用于双高方块：玩家破坏上半时，把掉落判断转给对应下半，避免上半绕过成熟度或下半状态校验。

## 4. 配置文件分工

`config.yml` 是玩法规则配置。默认文件会写出可配置玩法选项，常用项排在前面，高级项排在后面。

`gui.yml` 只负责 GUI：标题、布局、按钮、背景、图标、进度条、配方页面。主配置不再读取 GUI 小节。

`recipes/cooking_pot_recipes.yml` 是厨锅配方。

`recipes/cutting_board_recipes.yml` 是砧板配方。

## 5. 常用配置

语言：

```yaml
language: ''
```

留空会跟随 JVM/系统语言；也可以写 `zh_cn`、`en_us` 强制服务端日志和控制台文本语言。玩家界面文本仍会优先按玩家客户端语言显示。

砧板交互模式：

```yaml
cutting-board:
  interaction-mode: stacking
```

`stacking` 会启用 64 堆叠并关闭副手交互。`offhand` 会启用副手交互并关闭 64 堆叠。

煎锅漏斗导热：

```yaml
skillet:
  heat:
    allow-conductors: false
```

默认关闭。厨锅仍可使用导热方块判定热源，煎锅不会因为漏斗在下方就被当成加热。

厨锅破坏掉落：

```yaml
cooking-pot:
  pack-contents-on-break: true
```

`true` 表示厨锅被破坏时把内部原料、容器、待输出槽和输出槽保存进掉落的厨锅物品；`false` 表示掉落空锅并把锅内物品散落到世界中。

厨锅成品经验奖励：

```yaml
cooking-pot:
  experience-reward:
    mode: vanilla
    auraskills:
      skill: farming
      multiplier: 1.0
      raw: false
```

`mode` 可以写 `vanilla`、`auraskills`、`both`、`none`。奖励在玩家从厨锅输出槽取走成品，或拿容器右键取出待输出成品时结算；漏斗自动取出没有玩家上下文，因此不会触发 AuraSkills 经验。`multiplier` 使用配方 `experience` 乘倍率，写 `amount` 则改为每次取出固定经验。

## 6. 物品 ID 与清空写法

物品 ID 可以写原版或 CE 物品：

```yaml
minecraft:bowl
farmersdelight:flint_knife
```

可选物品输出可以写下面任意值来清空：

```yaml
""
none
null
empty
air
minecraft:air
```

适用位置：

- `knife-drops.<entity>.normal`
- `knife-drops.<entity>.burning`
- `straw-drops.<type>.drop`
- `cooking-pot.container-returns.<item>`
- `gui.yml` 中可选展示物品

配方的 `result` 不建议清空；如果不要某个配方，直接删除那条配方。

## 7. 厨锅

厨锅默认有 6 个原料槽、1 个待输出槽、1 个输出槽和 1 个容器槽。配方匹配成功并且有热源时开始烹饪；完成后产物优先进入输出槽，需要容器或输出槽暂时放不下时才暂存在待输出槽。

漏斗逻辑默认关闭。插件使用 CraftEngine `WorldlyContainerHolder` 容器路线处理漏斗交互，不再使用 Bukkit 事件模拟漏斗路线。需要漏斗输入输出时再开启：

```yaml
hopper-interactions:
  enabled: false
cooking-pot:
  hopper-interactions: true
cutting-board:
  hopper-interactions: true
```

开启后的设计逻辑：

- 上方漏斗：向原料槽塞入原料。
- 侧面漏斗：主要向容器槽塞容器，也允许按容器/输出规则交互。
- 下方漏斗：从输出槽抽取成品；待输出槽会在容器/空间满足后转入输出槽。

拿容器右键厨锅取出待输出槽里的成品时，只处理取出的成品和手持容器，不会刷新整锅数据导致其它内容丢失。

厨锅正在烹饪时会优先保持当前配方。只要当前配方仍然可以由锅内物品完成，继续放入稻米、其它食材或额外批次材料都不会重置烹饪进度；只有当前配方不再满足并切换到另一条配方时，进度才会重新开始。

厨锅配方示例：

```yaml
cooking_pot_recipes:
  vegetable_soup:
    ingredients:
      - "minecraft:carrot"
      - "minecraft:potato"
      - "minecraft:beetroot"
      - "farmersdelight:cabbage|farmersdelight:cabbage_leaf"
    container: "minecraft:bowl"
    result: "farmersdelight:vegetable_soup"
    result-count: 1
    experience: 1.0
    cook-time: 200
    category: meals
```

原料写法：

- 普通物品：`minecraft:carrot`
- CE 物品：`farmersdelight:cabbage`
- 标签：`#farmersdelight:vegetables`
- 同一格任选：`farmersdelight:cabbage|farmersdelight:cabbage_leaf`
- 排除：`#namespace:tag,!namespace:item,!#namespace:other_tag`

## 8. 砧板

砧板可以放上物品，再用配置的小刀或工具处理。输出支持多个结果和概率。

砧板配方示例：

```yaml
cutting_board_recipes:
  raw_pasta:
    input: "farmersdelight:wheat_dough"
    tool: "#farmersdelight:knives"
    results:
      - item: "farmersdelight:raw_pasta"
        count: 1
```

多个概率输出：

```yaml
cutting_board_recipes:
  wild_tomatoes:
    input: "farmersdelight:wild_tomatoes"
    tool: "#farmersdelight:knives"
    results:
      - item: "farmersdelight:tomato_seeds"
      - item: "farmersdelight:tomato"
        chance: 0.2
      - item: "minecraft:green_dye"
        chance: 0.1
```

工具可以写单个 `tool`，也可以写多个 `tools`。

## 9. 热源

热源可来自原版方块、原版标签、CE 方块、CE 标签。

```yaml
heat-sources:
  tags:
    - farmersdelight:heat_sources
  vanilla-blocks:
    - minecraft:magma_block
    - minecraft:lava_cauldron
    - minecraft:lava
    - minecraft:fire
    - minecraft:soul_fire
  vanilla-tags:
    - minecraft:campfires
  custom-blocks:
    - farmersdelight:stove[fire:true]
  conductors:
    - minecraft:hopper
  conductor-tags: []
```

CE 资源侧已给某个自定义方块加上 `farmersdelight:heat_sources` 标签时，不需要再单独写进 `custom-blocks`。

## 10. 世界展示与模型适配

替换 CE 模型后，常见问题是物品显示偏移、太大、太小、悬浮文字遮挡。优先调整 `config.yml` 里的展示参数，不建议直接改代码。

### 10.1 砧板物品展示

砧板的全局展示参数在 `cutting-board` 下：

```yaml
cutting-board:
  default-display-offset: 0,0,0
  display-item-spread: 0.15
```

- `default-display-offset`：所有砧板物品的整体偏移，格式 `x,y,z`。更换砧板模型后所有物品都偏向同一侧时，调整该项。
- `display-item-spread`：多个物品堆在砧板上时的散开范围。`0.15` 表示最大约 `+-0.075` 方块，接近原模组。显示过于分散时可调小，例如 `0.08`。

单个物品显示不对时，用 `display-overrides` 单独覆盖：

```yaml
cutting-board:
  display-overrides:
    farmersdelight:tomato:
      display-item: farmersdelight:tomato_display
      style: item
      position: 0,0.02,0
      translation: 0,0,0
      rotation: 90,180,0
      scale: 0.55
```

- `display-item`：用另一个 CE/原版物品当展示模型。
- `style`：`auto` 自动判断；`item` 强制平面物品；`block` 强制方块/立体物品。
- `position`：在全局偏移基础上继续移动。
- `translation`：ItemDisplay 内部平移。通常保持默认，模型轴心不对时再调整。
- `rotation`：覆盖旋转角度，单位度。
- `scale`：覆盖缩放，可以写 `0.55` 或 `0.55,0.55,0.55`。

### 10.2 煎锅物品展示

煎锅参数在 `skillet.display`：

```yaml
skillet:
  display:
    scale: 0.5
    y-offset: 0.1
    item-spread: 0.15
```

- `scale`：食物大小。
- `y-offset`：食物基础高度。陷进锅里就调高，漂浮明显就调低。
- `item-spread`：多个食物的随机散开范围。默认 `0.15` 接近原模组，即最大约 `+-0.075` 方块。

### 10.3 炉灶物品展示

炉灶参数在 `stove.display`：

```yaml
stove:
  display:
    scale: 0.375
    slot-offsets:
      - "0.3,1.02,0.2"
      - "0.0,1.02,0.2"
      - "-0.3,1.02,0.2"
      - "0.3,1.02,-0.2"
      - "0.0,1.02,-0.2"
      - "-0.3,1.02,-0.2"
```

- `scale`：炉灶上食物大小。
- `slot-offsets`：6 个槽位的坐标，格式 `x,y,z`。坐标以 north 朝向为基准，插件会按炉灶朝向自动旋转。
- 槽位顺序：前排右、前排中、前排左、后排右、后排中、后排左。
- 更换炉灶模型后食物没落在烤面上时，优先调 `slot-offsets` 的 `x/z`；高度不对时调整每一项的 `y`。

### 10.4 厨锅进度文字

厨锅悬浮进度文字在 `cooking-pot.progress-display`：

```yaml
cooking-pot:
  progress-display:
    enabled: true
    show-recipe-name: false
    y-offset: 1.2
    scale: 0.5
    visibility-distance: 10.0
    look-dot-threshold: 0.95
    update-interval-ticks: 8
    disable-above-active-pots: 512
```

- `enabled`：是否启用厨锅烹饪中的悬浮 TextDisplay。大量厨锅压测时可先关掉做基线对比。
- `show-recipe-name`：是否显示正在烹饪的配方名。
- `y-offset`：文字高度。更换高锅或矮锅模型后可调整该项。
- `scale`：文字缩放。
- `visibility-distance`：玩家离厨锅多少格内才可能看到。
- `look-dot-threshold`：看向判定阈值，越接近 `1` 要求看得越准。提示太难触发可调到 `0.90`。
- `update-interval-ticks`：悬浮进度文字刷新间隔。只影响 TextDisplay 更新频率，不会放慢真实烹饪时间。
- `disable-above-active-pots`：活跃厨锅数量超过该值时自动关闭悬浮 TextDisplay，`0` 表示不按数量自动关闭。

## 11. GUI

GUI 配置在 `gui.yml`。每个界面都有：

- `title`：标题，支持 MiniMessage 和 CE 图片字体。
- `title-layout`：配合 CE 图片字体做标题背景。
- `rows`：行数。
- `layout`：每个字符代表一个槽位。
- `legend`：字符到槽位类型的映射。
- `items`：按钮或装饰物品。

GUI 物品写法：

```yaml
items:
  recipe:
    material: KNOWLEDGE_BOOK
    name: "查看烹饪配方"
    lore:
      - "点击此处查看配方"
  arrow:
    item: farmersdelight:cooking_time
    name: "烹饪过程"
```

`material` 使用 Bukkit 原版材质，`item` 使用 CraftEngine 物品。配方详情页会尽量读取展示物品自己的名称，所以 CE 侧物品名称写好后，配方页面也会更自然。

### 11.1 厨锅配方详情的填入按钮

厨锅配方详情页的 `fill` 按钮用于把玩家背包里匹配当前配方的材料移动到厨锅内：

- 普通点击：每个配方材料最多填入 1 个匹配物品。
- Shift 点击：按配方材料循环匹配，尽量填满厨锅输入槽。
- 完全没有可匹配材料时，不会移动锅内物品，也不会返回厨锅界面。
- 如果锅内原物品无法安全退回玩家背包，不会执行填入，避免吞物品。
- 原地刷新配方详情页时会保留详情页烹饪进度条动画，不会因为填入按钮状态变化而把进度条显示重置到起点。
- 填入材料本身不会强制清零厨锅真实烹饪进度；正在烹饪的配方仍可完成时，进度继续保留。

按钮显示完全在 `gui.yml` 中配置。四个状态分别是：

| 配置项 | 什么时候显示 |
| --- | --- |
| `fill` | 默认状态，玩家还没有点击填入按钮。 |
| `fill-success` | 成功填入至少一个材料或容器后的状态。默认流程会返回厨锅界面，通常只会短暂显示；保留该项用于统一配置成功状态的材质和文本。 |
| `fill-missing` | 背包里没有任何可匹配当前配方的材料或容器。 |
| `fill-inventory-full` | 厨锅内原物品无法安全退回玩家背包。 |

示例：

```yaml
recipe-view-gui:
  recipe-detail-cooking-pot:
    items:
      fill:
        item: farmersdelight:recipe_fill
        name: "填入材料"
        lore:
          - "&e从背包移动匹配的材料。"
          - "&7Shift 点击填满可匹配材料。"
      fill-missing:
        item: farmersdelight:recipe_fill_missing
        name: "没有可填材料"
        lore:
          - "&c背包里没有可匹配当前配方的材料。"
      fill-inventory-full:
        item: farmersdelight:recipe_fill_blocked
        name: "背包空间不足"
        lore:
          - "&c请先清理背包，再尝试填入材料。"
```

这些按钮也可以使用原版材质：

```yaml
fill:
  material: HOPPER
  custom-model-data: 10001
  name: "填入材料"
```

修改 `gui.yml` 后使用 `/fd reload gui` 生效；如果按钮使用了新的 CraftEngine 物品或资源包贴图，仍建议完整重启服务器并让玩家重新加载资源包。

### 11.2 游戏内配方编辑器

管理员可以用 `/fd recipe edit pot|board [id]` 在游戏内可视化编辑配方，无需手动改 YAML。编辑器界面文本全部来自 `gui.yml` 的编辑器小节和语言文件，没有硬编码。

交互采用“光标笔刷”模型：点击背包里的物品会把它的副本放到光标上，再点编辑器槽位放入；真实背包物品不会被消耗。

- 原料格：左键放入光标物品作为普通原料；在已有原料上继续放会合并成或选（`a|b`）。
- 原料格右键 + 光标持物：按该物品所属标签弹出标签选择器（见下）。
- 原料格 Shift 点击或右键多选原料：打开或选构建器，可逐项删除或一键“全部清除”。
- 标签选择器：列出该物品所属的全部标签（CraftEngine 自定义标签 + 原版标签）。左键某标签直接使用；右键进入排除项编辑，列出该标签包含的物品，点击在“排除/包含”间切换。
- 保存、删除（带确认页）、数量/烹饪时间/经验/优先级/分类等都在界面内调整。

编辑器相关的 `gui.yml` 小节：`recipe-editor-gui`、`recipe-editor-cooking-pot-guis.<id>`（自定义大锅覆盖）、`recipe-cutting-board-editor-gui`、`recipe-editor-confirm-delete-gui`、`recipe-choice-builder-gui`、`recipe-tag-picker-gui`。保存会写入对应配方文件并自动重载配方。

CraftEngine 侧用自定义配置覆盖原版物品时，建议同时给 CE 物品写一个指向原版语言键的显示名，避免 GUI、配方页或物品提示只显示 ID。例如覆盖原版物品 ID 时，可以在 CE 物品显示名里使用：

```yaml
name: "<l10n:item.minecraft.${__ID__}>"
```

`${__ID__}` 表示当前物品 ID 的路径部分。例如 `minecraft:cooked_beef` 会对应到原版语言键 `item.minecraft.cooked_beef`。玩家客户端会按自己的语言显示“熟牛肉 / Cooked Beef”等本地化名称，而不是显示 `minecraft:cooked_beef` 或 CE 内部 ID。

如果覆盖的是自定义命名空间物品，也可以按同样思路写自己的语言键：

```yaml
name: "<l10n:item.farmersdelight.${__ID__}>"
```

前提是资源包语言文件里存在对应翻译。没有翻译时，客户端通常会回退显示语言键本身。

## 12. 小刀掉落与草秆

小刀识别：

```yaml
knife-config:
  tags:
    - farmersdelight:knives
  items:
    - farmersdelight:flint_knife
    - farmersdelight:iron_knife
    - farmersdelight:golden_knife
    - farmersdelight:diamond_knife
    - farmersdelight:netherite_knife
```

小刀击杀额外掉落：

```yaml
knife-drops:
  pig:
    normal: farmersdelight:ham
    burning: farmersdelight:smoked_ham
    chance: 0.5
    looting-multiplier: 0.1
```

草秆掉落：

```yaml
straw-drops:
  mature_wheat:
    drop: farmersdelight:straw
    min-amount: 1
    max-amount: 2
```

## 13. 食物效果

宠物食物：

```yaml
pet-foods:
  farmersdelight:dog_food:
    entities:
      - WOLF
    require-tamed: true
    restore-health: true
    effects:
      - type: SPEED
        duration: 6000
        amplifier: 0
        ambient: false
        particles: true
```

马食吸引：

```yaml
pet-foods:
  farmersdelight:horse_feed:
    entities:
      - HORSE
      - DONKEY
      - MULE
    require-tamed: false
    restore-health: true
    effects: []
    sound: ENTITY_GENERIC_EAT
    sound-volume: 0.8
    sound-pitch: 0.8
    particles: true
    particle-type: HEART
    particle-count: 4
    tempt:
      enabled: true
      range: 10.0
      move-speed: 1.25
      tick-interval: 10
      ignore-owned-tamed: true
```

`pet-foods` 的每个键就是一个食物物品 ID，支持 CraftEngine 物品 ID 和原版物品 ID。`entities` 使用 Bukkit `EntityType` 名称，例如 `HORSE`、`DONKEY`、`MULE`、`LLAMA`、`CAMEL`。`tempt` 是手持吸引配置；`tick-interval` 单位是 tick，10 tick 约等于 0.5 秒。需要更多可吸引食物时，直接在 `pet-foods` 下新增物品条目即可。

营养效果（吃下后清除疲劳值、防止掉饿）：吃 `nourishment-foods` 列表里的食物时生效。`duration` 单位为秒，默认按原版模组分档（30 / 60 / 180 / 300）。舒适效果（周期回血）在原版模组里已弃用，因此 `comfort-foods` 默认关闭、列表留空；机制代码保留，需要可自行启用并添加食物。

```yaml
comfort-foods:
  enabled: false
  foods: {}
nourishment-foods:
  enabled: true
  foods:
    farmersdelight:cooked_rice:
      duration: 30        # 30/60/180/300 秒分档
    farmersdelight:beef_stew:
      duration: 180
    farmersdelight:noodle_soup:
      duration: 300
```

容器返还：

```yaml
cooking-pot:
  container-returns:
    farmersdelight:milk_bottle: minecraft:glass_bottle
```

## 14. 命令与权限

主命令通过代码动态注册，命令名是 `/farmersdelight`，别名是 `/fd`。所有子命令都会先检查基础权限 `farmersdelight.command`；没有这个权限时，即使拥有某个子命令权限，也不会进入子命令。

命令总览：

```text
/fd help
/fd recipe
/fd recipe cooking_pot
/fd recipe cutting_board
/fd recipe edit pot [配方ID] [自定义组]
/fd recipe edit board [配方ID]
/fd reload
/fd reload all
/fd reload config
/fd reload gui
/fd reload lang
/fd reload recipes
/fd reload advancements
/fd cleanup
```

命令说明：

| 命令 | 别名/参数 | 权限 | 默认授权 | 说明 |
| --- | --- | --- | --- | --- |
| `/fd` | `/farmersdelight` | `farmersdelight.command` | 所有玩家 | 显示玩家当前有权限查看的帮助项。 |
| `/fd help` | `/fd ?` | `farmersdelight.command` | 所有玩家 | 显示帮助信息。 |
| `/fd recipe` | `/fd recipes` | `farmersdelight.command` + `farmersdelight.command.recipe` | 所有玩家 | 打开配方查看主界面。只能由玩家执行。 |
| `/fd recipe cooking_pot` | `pot`、`cookingpot` | `farmersdelight.command` + `farmersdelight.command.recipe` | 所有玩家 | 直接打开厨锅配方列表。只能由玩家执行。 |
| `/fd recipe cutting_board` | `board`、`cuttingboard` | `farmersdelight.command` + `farmersdelight.command.recipe` | 所有玩家 | 直接打开砧板配方列表。只能由玩家执行。 |
| `/fd recipe edit pot [id] [组]` | `pot`、`cookingpot` | `farmersdelight.command` + `farmersdelight.command.recipe` + `farmersdelight.admin` | OP | 打开厨锅配方编辑器。带 ID 直接编辑该配方（不存在则新建）；带自定义组可编辑自定义大锅配方；不带 ID 打开配方列表点击编辑。只能由玩家执行。 |
| `/fd recipe edit board [id]` | `board`、`cuttingboard` | `farmersdelight.command` + `farmersdelight.command.recipe` + `farmersdelight.admin` | OP | 打开砧板配方编辑器。带 ID 直接编辑（不存在则新建）；不带 ID 打开配方列表点击编辑。只能由玩家执行。 |
| `/fd reload` | 默认等同 `/fd reload config` | `farmersdelight.command` + `farmersdelight.admin` | OP | 重载 `config.yml`。 |
| `/fd reload config` | - | `farmersdelight.command` + `farmersdelight.admin` | OP | 只重载常规玩法配置。 |
| `/fd reload gui` | - | `farmersdelight.command` + `farmersdelight.admin` | OP | 重载 `gui.yml`，用于刷新 GUI 标题、布局、按钮、图标等。 |
| `/fd reload lang` | `language`、`languages` | `farmersdelight.command` + `farmersdelight.admin` | OP | 重载语言文件。 |
| `/fd reload recipes` | `recipe` | `farmersdelight.command` + `farmersdelight.admin` | OP | 重载厨锅、砧板配方，并刷新 CE 物品缓存。 |
| `/fd reload advancements` | `advancement` | `farmersdelight.command` + `farmersdelight.admin` | OP | 重载进度并重建进度树（需安装 UltimateAdvancementAPI；未安装则跳过）。 |
| `/fd reload all` | - | `farmersdelight.command` + `farmersdelight.admin` | OP | 完整重载配置、GUI、语言、配方和进度。修改 CE 资源或 jar 后仍建议重启。 |
| `/fd cleanup` | - | `farmersdelight.command` + `farmersdelight.admin` | OP | 清理插件生成的 CE 代理物品展示实体，并清理不符合条件的自动托盘。 |

调试构建额外命令：

| 命令 | 权限 | 默认授权 | 说明 |
| --- | --- | --- | --- |
| `/fd debugtools place <cooking_pot\|skillet\|stove\|stove_blocked\|all> [count] [spacing] [layers]` | `farmersdelight.command` + `farmersdelight.admin` | OP | 批量放置性能测试用方块。只会出现在使用 `-PdebugTools=true` 构建的 jar 中。 |
| `/fd debugtools activate <cooking_pot\|skillet\|stove\|all>` | `farmersdelight.command` + `farmersdelight.admin` | OP | 激活测试方块的烹饪/显示逻辑。只会出现在调试构建中。 |
| `/fd debugtools status` | `farmersdelight.command` + `farmersdelight.admin` | OP | 输出当前 TickManager 性能采样、活跃队列、厨锅实体数量。只会出现在调试构建中。 |
| `/fd debugtools profile [ticks]` | `farmersdelight.command` + `farmersdelight.admin` | OP | 重置 TickManager 采样并在指定 tick 后输出 avg/max/last 耗时，默认 200 ticks。只会出现在调试构建中。 |

正常构建不会包含 `debugtools`、`debug`、`perf` 子命令，也不会把调试工具类打进最终 jar。普通服端不需要给玩家配置任何调试权限。

权限总览：

| 权限 | 默认授权 | 作用范围 | 具体控制内容 |
| --- | --- | --- | --- |
| `farmersdelight.command` | 所有玩家 | 命令入口 | 允许使用 `/fd` 或 `/farmersdelight` 主命令。所有子命令都会先检查它。 |
| `farmersdelight.command.recipe` | 所有玩家 | 配方查看 | 允许使用 `/fd recipe`、`/fd recipe cooking_pot`、`/fd recipe cutting_board` 打开配方 GUI。 |
| `farmersdelight.use.cooking_pot` | 所有玩家 | 厨锅交互 | 默认允许打开厨锅 GUI、向厨锅放入原料/容器、从厨锅取出成品、触发厨锅交互逻辑。 |
| `farmersdelight.use.stove` | 所有玩家 | 炉灶交互 | 允许向炉灶放入食物、取出食物、触发炉灶烹饪和显示逻辑。 |
| `farmersdelight.use.skillet` | 所有玩家 | 煎锅交互 | 允许向煎锅放入食物、取出食物、触发煎锅烹饪和显示逻辑。 |
| `farmersdelight.use.cutting_board` | 所有玩家 | 砧板交互 | 允许向砧板放置物品、取下物品、用小刀或配置工具处理砧板配方。 |
| `farmersdelight.admin` | OP | 管理命令 | 允许使用 `/fd reload ...`、`/fd cleanup`。调试构建中也用于 `/fd debugtools`。 |
| `farmersdelight.*` | OP | 全部权限 | 包含本插件所有公开权限点。适合管理员组，不建议直接给普通玩家。 |

厨锅权限可以在 CraftEngine 方块行为参数里覆盖：

```yaml
behavior:
  type: farmersdelight:cooking_pot
  permission: farmersdelight.use.cooking_pot
```

`permission` 不写时默认使用 `farmersdelight.use.cooking_pot`。写成空字符串时，该厨锅不会做插件侧权限检查；这种写法只建议在领地、WorldGuard 或其它权限系统已经限制交互时使用。自定义厨锅也可以给不同 ID 配不同权限，例如：

```yaml
behavior:
  type: farmersdelight:cooking_pot
  permission: farmersdelight.use.large_cooking_pot
  custom:
    id: large
    input-slots: 9
    pending-output-slots: 2
    output-slots: 3
    container-slots: 2
```

这类自定义权限不会自动写入 `plugin.yml`，权限插件仍然可以直接识别和分配；如果需要 Bukkit 的权限列表显示它，需要在服务端权限插件或插件描述中登记。

## 15. 重载建议

`/fd reload` 默认只重载常规玩法配置，相当于 `/fd reload config`。可用的重载命令：

- `/fd reload config`：重载 `config.yml`。
- `/fd reload gui`：重载 `gui.yml`。
- `/fd reload lang`：重载语言文件。
- `/fd reload recipes`：重载厨锅和砧板配方，并刷新 CE 物品缓存。
- `/fd reload advancements`：重载进度并重建进度树（需安装 UltimateAdvancementAPI）。
- `/fd reload all`：完整重载本插件配置、GUI、语言、配方和进度。

建议完整重启：

- 更新 jar
- 更新 CraftEngine
- 修改 CE 资源配置
- 修改资源包字体、模型、贴图

不建议用 PlugMan 或 Bukkit reload 热重载本插件和 CraftEngine。

## 16. 常见问题

CE 物品名称显示成 ID：检查 CE 物品的显示名、语言文件和资源包是否加载。本插件配方页会优先使用物品本身的名称。

配方详情里原料展示不好看：先确认 CE 侧物品名是否正常，再检查 `gui.yml` 的详情页布局和 `show-ingredient-ids`。

漏斗没有按预期工作：先确认 `hopper-interactions.enabled` 是否开启。该功能默认关闭；开启后再确认方向，厨锅上方塞原料，下方抽成品，侧面塞容器。煎锅默认不接受漏斗导热。

修改配置后没变化：先 `/fd reload`。如果动了 CE 资源或资源包，完整重启。

## 17. 编译

开发环境通过 Maven 仓库解析 CraftEngine 26.5 依赖，`build.gradle.kts` 已使用 `net.momirealms:craft-engine-*` 坐标。若依赖仓库暂时不可用，可先把对应 CraftEngine 产物安装到 `mavenLocal()`。

构建产物是**单个通用 jar**，同时支持 Paper 和 Folia（`plugin.yml` / `paper-plugin.yml` 里 `folia-supported: true` 直接写死，不再按平台拆分）。

常用构建：

```powershell
.\gradlew.bat clean shadowJar
```

输出：

```text
build/libs/farmersdelight-1.0.0.jar
```

可选混淆构建：

```powershell
.\gradlew.bat buildObfuscated
```

混淆输出：

```text
build/libs/farmersdelight-1.0.0-obf.jar
build/reports/proguard/farmersdelight-1.0.0-mapping.txt
```

混淆构建（ProGuard）会保留 Bukkit 主类的生命周期方法、`@EventHandler` 方法（保留但允许改名）、枚举入口和公开 API 包 `com.huidu.farmersdelight.api.event.**`；其余类全部重命名并 repackage 到 `fd`。CraftEngine 方块行为按字符串键 + 工厂引用注册，不依赖类名反射，因此可安全混淆。混淆后插件自有代码只有公开 API 包保持可读。`build/reports/proguard/*-mapping.txt` 仅作内部排查留档（用于反查混淆堆栈），`build/` 已被 git 忽略，不要提交到公开仓库。

调试工具（`/fd debugtools …` 性能测试命令）默认不打进 jar，需要时用 `-PdebugTools=true` 构建：

```powershell
.\gradlew.bat clean shadowJar -PdebugTools=true
```

Folia 环境建议在测试服验证区域调度、容器交互、显示同步和区块卸载保存。
