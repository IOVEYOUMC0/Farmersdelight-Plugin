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

本插件注册的方块行为 ID 共 15 个：

```text
farmersdelight:cooking_pot
farmersdelight:cutting_board
farmersdelight:skillet
farmersdelight:stove
farmersdelight:tall_crop
farmersdelight:wild_rice
farmersdelight:wild_plant
farmersdelight:tomato_vine
farmersdelight:mushroom_colony
farmersdelight:rich_soil
farmersdelight:rich_soil_farmland
farmersdelight:organic_compost
farmersdelight:rope
farmersdelight:tatami
farmersdelight:upper_half_loot_relay
```

除此之外还注册了一个**物品**行为 `farmersdelight:conditional_block_planting`，以及两个**事件函数** `farmersdelight:comfort` 和 `farmersdelight:nourishment`，这三个在 3.10 说明。

CE 方块或家具配置中需要把对应行为挂到对应资源上。例如厨锅挂 `farmersdelight:cooking_pot`，砧板挂 `farmersdelight:cutting_board`，炉灶挂 `farmersdelight:stove`。CE 配置语法以 CraftEngine 26.5 的资源配置为准。

需要先记住 CraftEngine 的一个前提：自定义方块**不会继承**它所伪装的原版方块的任何东西——方块标签不继承，行为也不继承。功能需要什么标签（`minecraft:dirt`、`minecraft:climbable`、`farmersdelight:heat_sources` 等），就得在自己的方块上明确写出来。

### 3.1 方块行为挂载

行为参数写在 CraftEngine 方块/家具资源的 `behavior` 下。常见写法如下，具体缩进以 CE 资源文件结构为准：

```yaml
behavior:
  - type: farmersdelight:cooking_pot
    permission: farmersdelight.use.cooking_pot
```

如果同一个资源还挂了 CE 自身行为或其他插件行为，继续在 `behavior` 列表里追加即可。小节名 `behavior:` 和 `behaviors:` CraftEngine 都接受，本插件自带的资源文件里两种写法都出现过。

**参数名一律用连字符。** CraftEngine 不会把 `-` 和 `_` 归一化，键名是按字面查表的。CE 自身有少量选项能同时接受两种写法，是因为它们的解析器显式列举了两个名字；本插件的行为除下表三处例外，全部只认连字符写法。写成 `boil_sound`、`burn_enabled`、`tool_tags`、`max_stack_amount`、`data_key`、`rich_soil_block`、`pair_while_sneaking`、`bottom_blocks` **不会报错**，只是这个键被无视、用回默认值。

本插件中仅有的可多写法参数：

| 参数 | 可用写法 |
| --- | --- |
| `tall_crop` 骨粉阶段加成 | `bone-meal-age-bonus`、`bone_meal_age_bonus` |
| `tall_crop` 额外种植物品 | `extra-planting-items`、`extra_planting_items`、`extraPlantingItems` |
| `comfort` / `nourishment` 条件 | `condition`、`conditions` |

**没有任何地方会校验未知键。** 本插件的行为不会拒绝、不会警告、也不会记录一个它不认识的键。打错字的结果是方块照常加载、安静地按“什么都没配”运行，唯一症状就是游戏里表现为默认值。所以某个参数“不生效”时，先怀疑拼写。键打错字唯一可能致命的情形是间接的：把 `age-property` 这类属性名参数的键写错，会退回默认属性名，而那个默认名此时必须在方块上存在——见 3.2。

**值是怎么读的。** 多数参数对类型比较宽容，但出错方向并不一致，而且都不会有任何提示。唯一的例外是 3.2 列出的那批属性名参数：它们的值要拿去和方块声明的属性对照，写了一个对不上任何属性的名字是错误，代价是这个方块整份行为列表作废——具体表现见 3.2。

| 你写的 | 实际读成 |
| --- | --- |
| 不带引号的 `true` / `false` | 永远正确，推荐只用这种写法。 |
| 带引号的 `"true"` / `"false"` | 多数布尔参数能接受。少数参数只认真正的布尔值，字符串会被丢弃并保留默认值：`wild_rice` 的 `requires-water`，以及 `upper_half_loot_relay` 的 `require-matching-lower-half` / `require-matching-block`。 |
| 布尔参数写 `yes`、`on`、`off` | 一律读成 **`false`**，与英文词义无关——只有字面的 `true` 为真。 |
| 布尔参数写 `0` / `1` | 数字属于类型不匹配，参数会退回**自己的默认值**。所以在默认 `true` 的参数上，`burn-enabled: 0` 反而是**启用**——和写字符串的方向正好相反。 |
| 数值参数写了解析不了的内容（`sound-chance: high`） | 静默换成默认值，方块照常加载。 |
| 键写了但值留空（`grow-speed:` 后面什么都没有） | 等同于没写这个键。 |

### 3.2 容易踩的坑

下面这些错误都会得到一个“看起来正常、行为却不对”的方块，建议先读完再去看各行为的参数表。

- **不写某个参数，未必等于写上它表面上的默认值。** 有几个默认值是**推导**出来的：`tall_crop` 的 `max-age-lower` 取 `age` 属性声明范围的上限，`max-age-upper` 是它减一——在 `0~7` 的作物上不写这两项得到的是 `7` 和 `6`，不是 `4` 和 `3`。也有反过来的：`sound-chance: 0` 会让厨锅静音，而删掉这一行得到的是 `0.1`。
- **自带配置里的值不一定等于代码默认值。** `farmersdelight:rich_soil` 自带配置写的是 `boost-chance: 0.2`，代码默认是 `0.08`——删掉这一行会让自带方块的催熟速率掉到不足一半。
- **开关名字管的范围可能比字面小。** `tall_crop` 上的 `is-bone-meal-target: false` 只跳过**这个行为自己**的骨粉分支；如果同一个方块还挂了 CE 的 `crop_block`，CE 那条骨粉路径完全不受影响，作物照样被催熟。自带的 `farmersdelight:budding_tomatoes` 之所以**同时**写了 `is-bone-meal-target: false` 和 `bone-meal-age-bonus: 0`，就是因为真正起作用的是后者那个归零；这对看起来冗余的配置不要“顺手清理”。
- **`bone-meal-age-bonus: 0` 关不掉 `tall_crop` 的骨粉。** 该行为在使用时把加成夹到至少 1，写 0 仍然会长一阶。这一点和 CE 的 `crop_block` 正好相反——在 `crop_block` 上归零才是让骨粉失效的标准做法。想让只挂 `tall_crop` 的方块免疫骨粉，用 `is-bone-meal-target: false`。
- **空列表等于“没写”，不等于“清空”。** 砧板上写 `tool-tags: []` 不会取消标签工具，而是把四个内置默认标签装回去。插件没有提供“一个标签都不要”的写法，最接近的做法是填一个匹配不到任何物品的标签 ID。
- **配置列表是整体替换，不是追加。** 想在砧板工具里加上 `minecraft:swords`，必须把四个默认标签一起重写；在作物上写 `bottom-blocks` 也会把它内置的整套土壤 fallback 丢掉。
- **给厨锅加 `custom:` 会打乱槽位序号**，哪怕四个槽位数量和默认完全一致。默认布局是原料 0-5、待输出 6、容器 7、输出 8；自定义布局一律按原料 → 待输出 → 输出 → 容器连续排布，容器和输出的位置互换。按默认顺序写的 `gui.yml` 布局会指到错误的槽位上。
- **改 `data-key` 会让已存内容全部读不到。** 厨锅和砧板的内容都存在这个子键下。世界里已经放了这种方块之后再改，所有已放置的方块都会读成空的。这个键在定义变体时选定一次，之后不要再动。
- **九个行为强制要求特定的状态属性；解析不到，丢掉的是行为，不是方块本身。** 这是最容易让人查错方向的一点。CraftEngine 会接住这个错误，打出一条写明文件、配置节点和缺失属性名的消息，然后换上一个什么都不做的普通行为顶替。方块照样注册、照样能放置、你定义的每个状态照样显示、战利品照样掉、物品照样在。没了的是这个行为原本做的所有事。所以游戏里的症状不是“我的方块不见了”，而是“我的炉灶变成一块普通方块了”——不点火、不烫人、也不给上面的锅供热，而且从此一声不吭。加载时那一条错误是它唯一一次开口，务必去翻。受影响的只有这一个方块，资源包其余部分照常加载。
  - 顶替掉的是**整份** `behavior:` 列表，而不是出错的那一条。一个挂了四个行为的方块，只要有一个属性写错，四个一起没，连 CraftEngine 自带的 `waterlogged` 与旋转/镜像处理也一并失效。所以一个拼写错误足以让一个看上去八九不离十的方块彻底变哑。
  - *属性名可改，但改成什么名字就必须存在什么名字的属性：* `tall_crop`（`age-property`，int；`half-property`，`double_block_half`）、`mushroom_colony`（`age-property`，int）、`rich_soil_farmland`（`moisture-property`，int）、`organic_compost`（`composting-property`，int）、`tatami`（`facing-property`，以及必须是布尔的 `paired-property`）、`upper_half_loot_relay`（`half-property`）。
  - *属性名写死，没有参数可以改指向：* `stove` 需要布尔属性 `fire`，`wild_rice` 需要 `double_block_half` 类型的 `half`，`tomato_vine` 需要整数属性 `age`。
  - 这些改名参数改的是“行为去找哪个属性”，不是“可以不要这个属性”。配了一个找不到对应属性的名字是**错误**，不会悄悄退回默认名。
  - 存在性和类型都会校验，只有两处例外——值是按文本比较的：`tatami` 的朝向属性和 `upper_half_loot_relay` 的半属性只按名字查找，因此任何取值能拼出方向名或上下半名称的属性类型都能用。
- **真正可选的属性查找只有两处，再无别处。** 一处是 `tall_crop` 的 `supporting-property`——自带的 `farmersdelight:rice` 根本没有这个属性，它用一个最大 age 值来表达成熟支撑阶段；另一处是 `rope` 的四个连接属性。只有这两处缺属性（或类型不对）是跳过而不是报错：打错字的代价是丢一个功能，而不是丢整份行为列表，而且连一条日志都不会有。
- **缺一个物品 ID 就可能让整个交互失效。** `mushroom_colony` 没有有效的 `mushroom-type` 时，右键会在改动状态之前直接返回，群落变得完全无法采集，也没有任何报错。
- **同名参数在不同行为上含义未必相同。** `light-requirement: 0` 在 `mushroom_colony` 上意思是“跳过亮度检查”，在 `tall_crop` 上则是一次真实比较；`requires-water` 在 `tall_crop` 上宽容、在 `wild_rice` 上严格。配哪个行为就看哪个行为的表。

### 3.3 厨锅行为

默认厨锅只需要挂载行为：

```yaml
behavior:
  - type: farmersdelight:cooking_pot
```

可用参数：

| 参数 | 必填 | 默认值 | 作用 |
| --- | --- | --- | --- |
| `permission` | 否 | `farmersdelight.use.cooking_pot` | 打开厨锅需要的权限。写空字符串可关闭本插件的权限检查。 |
| `open-while-sneaking` | 否 | `false` | 潜行右键是否也打开厨锅。关闭时潜行交互让给其他行为，手上拿着方块可以正常放置。 |
| `place-tray-on-open` | 否 | `true` | 打开厨锅时是否同步/放置锅下托盘显示。还受 `config.yml` 的 `tray.enabled` 总开关限制，总开关关掉时托盘不会出现。 |
| `boil-sound` | 否 | `farmersdelight:block.cooking_pot.boil` | 正在烹饪、还没有成品等待时的环境音效。 |
| `soup-boil-sound` | 否 | `farmersdelight:block.cooking_pot.boil_soup` | 已有成品待取或已展示时的环境音效。 |
| `sound-chance` | 否 | `0.1` | 每次音效派发时播放沸腾声的概率。写 `0` 可静音，删掉这个键不能。 |
| `sound-volume` | 否 | `0.5` | 沸腾音效音量。 |
| `sound-pitch-min` | 否 | `0.9` | 随机音高下限。 |
| `sound-pitch-max` | 否 | `1.1` | 随机音高上限。不大于下限时音高固定为下限值。 |
| `data-key` | 否 | `farmersdelight:cooking_pot` | 保存厨锅内容的子键，CE BlockEntity 和掉落的锅物品上都用它。修改前请先看 3.2。 |
| `custom` | 否 | 未启用 | 自定义槽位数量、配方组和 GUI 标题。 |

四个音效参数在值为空或无法解析时都会回退到内置音效，所以没有办法配出一口无声的锅；要静音请用 `sound-chance: 0`。

厨锅本体不再从行为里读别的东西。交互冷却、进度显示、漏斗交互和托盘总开关都在 `config.yml`，热源来自 `heat-sources` 列表（见第 9 节）——锅**下面**那个方块必须在其中列出。自带厨锅声明了 `facing` 属性供模型使用，行为本身不读它，所以没有 `facing` 的锅也能工作，只是失去朝向。比较器输出由行为自动提供，反映的是所有槽位的整体充满度，而不只是成品槽。

自带配置：

```yaml
behavior:
  - type: farmersdelight:cooking_pot
    boil-sound: farmersdelight:block.cooking_pot.boil
    soup-boil-sound: farmersdelight:block.cooking_pot.boil_soup
    sound-chance: 0.1
    sound-volume: 0.5
    sound-pitch-min: 0.9
    sound-pitch-max: 1.1
```

自定义厨锅示例：

```yaml
behavior:
  - type: farmersdelight:cooking_pot
    data-key: farmersdelight:large_pot
    custom:
      id: large_pot
      title: "<image:farmersdelight:large_cooking_pot_gui>"
      input-slots: 9
      pending-output-slots: 2
      output-slots: 3
      container-slots: 2
```

`custom` 参数说明：

| 参数 | 必填 | 默认值 | 作用 |
| --- | --- | --- | --- |
| `id` | 否 | 未设置 | 自定义厨锅 ID。配方文件中使用 `custom_cooking_pot_recipes.<id>` 写这个锅专用配方；`gui.yml` 可用同名的厨锅 GUI、配方详情 GUI、配方编辑 GUI 配置覆盖界面。 |
| `title` / `gui-title` | 否 | 未设置 | 打开 GUI 时使用的标题，通常写成图片字体。`gui-title` 只在 `title` 缺失或为空白时才会被读取。 |
| `input-slots` | 否 | `6` | 原料槽数量。小于 `1` 会被抬到 `1`。 |
| `pending-output-slots` | 否 | `1` | 待输出槽数量。烹饪完成但尚未转入输出槽的产物暂存在这里。小于 `1` 会被抬到 `1`。 |
| `output-slots` | 否 | `1` | 成品输出槽数量。小于 `1` 会被抬到 `1`。 |
| `container-slots` | 否 | `1` | 容器槽数量，例如碗、瓶等。允许写 `0`，表示取消容器槽。 |

只要写了 `custom` 小节就会切换到自定义布局，哪怕这个小节里只写了一个 `id`。槽位随后按原料 → 待输出 → 输出 → 容器的顺序连续分配，与默认顺序不同，详见 3.2。

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

只写 `custom.id` 而不加同名的 `cooking-pot-guis.<id>` 是允许的，插件会回退到默认厨锅 GUI。如果自定义锅有专用配方但没有 `recipe-detail-cooking-pot-guis.<id>`，插件会在从该锅打开配方 GUI 时向控制台输出 warning，并回退使用默认 `recipe-detail-cooking-pot`。如果某个配方材料数超过详情页 `ingredient` 槽数量，也会在打开该 GUI 时向控制台提示。

### 3.4 砧板行为

```yaml
behavior:
  - type: farmersdelight:cutting_board
    knife-sound: farmersdelight:block.cutting_board.knife
    tool-tags:
      - '#farmersdelight:knives'
      - '#minecraft:axes'
      - '#minecraft:pickaxes'
      - '#minecraft:shovels'
    tool-items:
      - minecraft:shears
```

上面这段自带配置只是把内置默认值原样重写了一遍，起说明作用，并没有改变任何行为。

可用参数：

| 参数 | 必填 | 默认值 | 作用 |
| --- | --- | --- | --- |
| `tool-tags` | 否 | `farmersdelight:knives`、`minecraft:axes`、`minecraft:pickaxes`、`minecraft:shovels` | 其成员可作为切割工具的物品标签。开头的 `#` 可写可不写。写了这个参数就是把四个默认标签整体替换掉；写成空列表反而会把它们装回去。 |
| `tool-items` | 否 | `minecraft:shears` | 在标签之外，直接作为切割工具的具体物品 ID。这里**不能**带 `#`，写标签会得到一个匹配不到任何东西的畸形 ID。 |
| `knife-sound` | 否 | `farmersdelight:block.cutting_board.knife` | 处理食材成功时播放的音效。 |
| `max-stack-amount` | 否 | `64` | 同一块砧板上允许堆叠的同种物品上限。它只能把上限调低、不能调高——实际生效值取本参数、容器堆叠上限、物品自身最大堆叠数三者中的最小值。 |
| `data-key` | 否 | `farmersdelight:cutting_board` | CE BlockEntity 中保存砧板内容的子键。与厨锅的 `data-key` 是同一个坑。 |

砧板是否启用堆叠由 `config.yml` 的 `cutting-board.interaction-mode` 控制：`stacking` 启用堆叠，`offhand` 启用副手交互。堆叠关闭时 `max-stack-amount` 完全不起作用。

砧板的权限节点是写死的 `farmersdelight.use.cutting_board`，与厨锅不同，这里没有 `permission` 参数。`facing` 属性可选但强烈建议声明，缺了它砧板上的物品会固定朝北渲染，不随砧板转向。

### 3.5 炉灶行为

```yaml
behavior:
  - type: farmersdelight:stove
    crackle-sound: farmersdelight:block.stove.crackle
states:
  properties:
    facing:
      type: 4-direction
      default: north
    fire:
      type: boolean
      default: true
```

**`fire` 属性是必需的。** 炉灶的点燃状态读自一个名字必须正好是 `fire` 的布尔方块状态属性。挂了 `farmersdelight:stove` 却没有这个属性的方块照样加载、照样能放置，但会整个丢掉炉灶行为：控制台会写明配置节点和缺失的属性名，而你在世界里拿到的是一块永远不会做饭、也不会烫伤任何人的纯装饰方块。这个名字是写死的，没有参数能把它指到别处。`config.yml` 里的热源条目 `farmersdelight:stove[fire:true]` 匹配的也是同一个属性，所以这样的炉灶同样永远不会给上面的锅和煎锅供热。

| 参数 | 必填 | 默认值 | 作用 |
| --- | --- | --- | --- |
| `crackle-sound` | 否 | `farmersdelight:block.stove.crackle` | 炉灶点燃时播放的燃烧音效。值为空时回退到默认音效，而不是静音。 |
| `burn-enabled` | 否 | `true` | 点燃的炉灶是否灼伤站在烤面上的生物。 |
| `burn-damage` | 否 | `1.0` | 每次灼伤 tick 的伤害。负数会被夹到 `0`，效果是停止伤害而不是治疗。 |

自带炉灶两个灼伤参数都没写，直接用默认值。tick 预算、烹饪时长、冷却、生物扫描半径、粒子频率和六个食物显示偏移都在 `config.yml` 的 `stove` 小节，权限节点是写死的 `farmersdelight.use.stove`。

### 3.6 煎锅行为

```yaml
behavior:
  - type: farmersdelight:skillet
    add-food-sound: farmersdelight:block.skillet.add_food
    sizzle-sound: farmersdelight:block.skillet.sizzle
```

| 参数 | 必填 | 默认值 | 作用 |
| --- | --- | --- | --- |
| `add-food-sound` | 否 | `farmersdelight:block.skillet.add_food` | 放入食物时播放的音效。 |
| `sizzle-sound` | 否 | `farmersdelight:block.skillet.sizzle` | 烹饪时播放的滋滋声。 |

这两个就是煎锅行为的全部参数。值为空会回退到默认音效，所以这里配不出一口无声的煎锅。烹饪时长倍率、火焰附加加成、冷却、漏斗输入、经漏斗传导热量、可视距离以及食物显示的缩放/偏移全部在 `config.yml` 的 `skillet` 小节，权限节点是写死的 `farmersdelight.use.skillet`。自带煎锅声明了 `facing` 供模型和食物摆放使用。

### 3.7 作物类行为

其中四个行为共用同一对土壤参数：

| 参数 | 必填 | 作用 |
| --- | --- | --- |
| `bottom-block-tags` | 否 | 作物可以立在其上的方块标签。开头的 `#` 可写可不写。既匹配原版方块标签，**也**匹配 CE 自定义方块在自己 `settings.tags` 中声明的标签。 |
| `bottom-blocks` | 否 | 具体方块。可用的写法只有三种：原版 ID（`minecraft:farmland`）、原版状态（`minecraft:farmland[moisture=7]`）、CE 方块 ID（`your_pack:custom_soil`）。CE ID 后面带状态后缀是**不支持**的，详见下文。 |

`bottom-blocks` 有三点需要注意。

原版状态条目是**子集**匹配而非精确匹配：只比较你真正写出来的那几个属性，所以 `minecraft:farmland[moisture=7]` 会命中所有湿度为 7 的耕地，其余属性是什么都不管。因此只写裸 ID 和把每个属性都写全，效果是一样的。

CE ID 带状态后缀——`your_pack:custom_soil[level=2]`——不是“被忽略”这么轻。条目会先交给原版方块数据解析器，而它只认 `minecraft:`，一律拒绝；这次解析失败被吞掉后，条目会退回去当成纯材质名再试一次，而这一次会直接拒绝 `[`、`]`、`=` 并抛出异常。第二次抛出没有任何地方接，它会以未知错误的形式从行为构造中逃逸出去，导致这个方块**真的注册不上**——没有方块、没有物品、没有状态，与 3.2 里那类可恢复的属性错误完全不同。要匹配自定义土壤，请用它的裸 ID，或者用它声明的某个标签。

另外条目在解析成原版材质时只看**路径**、忽略命名空间，所以路径与某个原版材质重名的自定义方块（`your_pack:stone`）会被静默解析成原版方块。给自定义土壤起个不会撞名的路径。

配置了其中任一个参数，该行为内置的整套土壤 fallback 就被整体替换掉；两个都不配才会保留 fallback。`mushroom_colony` 是例外——它压根没有内置 fallback，两个都不配的结果是它的生长完全不受土壤限制。

高作物（tall crop）——水稻使用的上下两半作物，带生长、骨粉、成熟上半右键收割和放置校验：

```yaml
behaviors:
  - type: farmersdelight:tall_crop
    extra_planting_items:
      - farmersdelight:rice_panicle
    grow-speed: 0.25
    light-requirement: 6
    is-bone-meal-target: true
    bone_meal_age_bonus:
      type: uniform
      min: 1
      max: 4
    requires-water: true
    harvest-tool-tags:
      - "#farmersdelight:knives"
    bottom-block-tags:
      - "#minecraft:dirt"
    bottom-blocks:
      - minecraft:grass_block
states:
  properties:
    age:
      type: int
      default: 0
      range: 0~4
    half:
      type: double_block_half
      default: lower
```

（这就是自带的 `farmersdelight:rice`。里面那两个下划线写法的键，正是全插件仅有的三个可多写法参数中的两个。）

| 参数 | 必填 | 默认值 | 作用 |
| --- | --- | --- | --- |
| `age-property` | 是 | `age` | 表示生长阶段的整数属性名。**必需**：方块上必须存在这个名字的 `int` 属性，否则方块照样加载但会丢掉这个行为，控制台里会点名该属性。不会回退到 `age`。 |
| `half-property` | 是 | `half` | 区分上下半的属性名。同样**必需**，且类型必须是 `double_block_half`。 |
| `supporting-property` | 否 | `supporting` | 可选的布尔属性，下半成熟时置为 `true`、重置时置为 `false`。这一项是真正可选的：方块没有该名字的属性、或该属性不是布尔类型时都静默跳过。自带的 `farmersdelight:rice` 就没有声明它。 |
| `grow-speed` | 否 | `0.25` | 每次随机 tick 生长一阶的概率。 |
| `light-requirement` | 否 | `9` | 随机 tick 生长所需的最低亮度。骨粉不检查亮度。 |
| `is-bone-meal-target` | 否 | **`true`** | 是否启用**本行为自己**的骨粉分支。管不到别的东西，见 3.2。 |
| `bone-meal-age-bonus` | 否 | `1` | 每次骨粉增加的阶段数。可写普通数字，也可写 `type: uniform` 之类的 CE number provider。使用时被夹到至少 `1`，所以写 `0` 关不掉骨粉。 |
| `max-age-lower` | 否 | `age` 范围的上限 | 下半成熟并生成上半的阶段。 |
| `max-age-upper` | 否 | `max-age-lower` 减一 | 上半可收割的阶段。 |
| `half-lower-value` / `half-upper-value` | 否 | 属性中名为 `lower` / `upper` 的值，没有则取属性的首个/末个值 | 视为上下半的原始属性值。 |
| `requires-water` | 否 | `false` | 为真时，种植还要求种植格或其正下方有水源。**只在放置时校验**，种下之后把水抽掉不会杀死作物。 |
| `reset-on-harvest` | 否 | `true` | 为真时可右键收割成熟的上半，并把下半重置。为 **`false` 时右键收割整个功能都不存在**。 |
| `upper-block` | 否 | 与本方块相同 | 生成上半时使用的方块定义。 |
| `harvest-tool-tags` / `harvest-tool-items` | 否 | 空 | 允许收割的物品标签 / 物品 ID。它们是在 `config.yml` 的 `knife-items` 列表**之上**追加的，那个列表始终有效。 |
| `extra-planting-items` | 否 | 空 | 复用本作物种植逻辑的额外物品 ID。可写列表，也可写单个字符串。 |
| `bottom-block-tags` / `bottom-blocks` | 否 | 内置 fallback | 土壤规则，写法见上。fallback 为耕地、泥土、草方块、泥巴、黏土、沙子、砂土、缠根泥土、灰化土、菌丝，外加任何标签名中含 `farmland`、`soil` 或 `dirt` 的 CE 方块。 |

age 和 half 这两个属性是硬性必需，不是“最好有”：`tall_crop` 方块解析不到其中任何一个，状态和物品都还在，但作物行为会被整个剥掉——所以配错真正换来的恰恰就是一株杵在那里不长、不成熟、也永远收不了的装饰植物，外加一条点名该属性的控制台消息。请以那条消息为准，别看方块长得对不对。被 `extra-planting-items` 认领的物品只能映射到一种作物方块，第二次认领会被忽略并输出控制台警告。每次成功收割上半还会额外掉落 `config.yml` 中 `drops.straw.mature_rice` 配置的物品，这条规则对**所有** `tall_crop` 方块生效，不只是水稻。方块若声明了 `loot` 小节，收割会掷该战利品表，上半掉不出东西时回退掷下半的表；完全没有 `loot` 的方块则改为触发 CE 的 `on: break` 事件链。

野生水稻（wild rice）——一个只提供规则的行为，本身不生长。它决定自然生成的两格野生水稻能否放置、能否存活：

```yaml
behavior:
  - type: double_high_block
  - type: farmersdelight:wild_rice
    requires-water: true
    bottom-block-tags:
      - "#minecraft:dirt"
    bottom-blocks:
      - minecraft:grass_block
      - minecraft:mud
      - minecraft:sand
      - minecraft:red_sand
  - type: farmersdelight:upper_half_loot_relay
```

| 参数 | 必填 | 默认值 | 作用 |
| --- | --- | --- | --- |
| `requires-water` | 否 | **`true`** | 为真时种植格必须是水**源**方块。按严格布尔读取，带引号的 `"false"` 会被丢弃、保留默认值。 |
| `bottom-block-tags` / `bottom-blocks` | 否 | 内置 fallback | 下半可以立在其上的方块。fallback 为泥土、草方块、砂土、缠根泥土、灰化土、菌丝、泥巴、沙子、红沙。 |

这里没有 `half-property`，属性名固定为 `half`，类型必须是 `double_block_half`，而且是必需的——挂了这个行为却没有该属性的方块照样加载，但行为会被丢掉，这株植物也就没有了属于自己的放置与存活规则。上下半的值随后按名字与属性自身的取值匹配。方块 ID 同样是固定的：插件按 `farmersdelight:wild_rice` 查找这个行为，把它挂到别的 ID 上虽然会注册成功，但永远不会有人调用。此外水面上一格必须是空气，上半必须坐在同一个方块 ID 的下半之上——这两条都不可配置。

野生植物（wild plant）——野生卷心菜/洋葱/番茄这类静态野生作物的骨粉扩散。它自身没有存活逻辑，那是同一个方块上 CE `bush_block` 的职责：

```yaml
behavior:
  - type: liquid_flowable_block
  - type: bush_block
    bottom-block-tags:
      - minecraft:dirt
      - minecraft:sand
  - type: farmersdelight:wild_plant
    is-bone-meal-target: true
```

| 参数 | 必填 | 默认值 | 作用 |
| --- | --- | --- | --- |
| `is-bone-meal-target` | 否 | `true` | 为假时行为直接放行这次点击，既不消耗骨粉也不扩散。 |
| `bone-meal-success-chance` | 否 | `0.8` | 一次骨粉真正尝试扩散的概率。无论成败骨粉都会被消耗，所以写 `0` 也照样每次吃掉一个骨粉。 |
| `spread-limit` | 否 | `10` | 周围 9×3×9 范围内的数量上限。原点植株把自己也算进去，因此写 `1` 等于“永不扩散”。小于 `1` 会被抬到 `1`。 |
| `bottom-block-tags` / `bottom-blocks` | 否 | `minecraft:dirt` 或 `minecraft:sand` 标签 | **扩散出的副本**可以落在哪些方块上，而不是植株本身能存在于哪里。这里和 `bush_block` 的存活规则不一致时，副本会在下一次方块更新时脱落。 |

番茄藤（tomato vine）——同一个行为挂在三个番茄方块上，按位置上实际存在的方块分派：

```yaml
behaviors:
  - type: crop_block
    grow-speed: 0.25
    light-requirement: 9
    is-bone-meal-target: true
    bone_meal_age_bonus:
      type: uniform
      min: 1
      max: 2
  - type: bush_block
    bottom-blocks:
      - farmersdelight:tomatoes
    stackable: true
    max-height: 3
    delay: 1
  - type: farmersdelight:tomato_vine
    budding-block: farmersdelight:budding_tomatoes
    tomatoes-block: farmersdelight:tomatoes
    crop-on-rope-block: farmersdelight:tomato_crop_on_rope
    mature-age: 0
```

| 参数 | 必填 | 默认值 | 作用 |
| --- | --- | --- | --- |
| `budding-block` | 否 | `farmersdelight:budding_tomatoes` | 出芽阶段的方块 ID。 |
| `tomatoes-block` | 否 | `farmersdelight:tomatoes` | 地面阶段的方块 ID。 |
| `crop-on-rope-block` | 否 | `farmersdelight:tomato_crop_on_rope` | 攀绳阶段的方块 ID。 |
| `rope-block` | 否 | `farmersdelight:rope` | 攀绳番茄被移除后恢复出来的方块。 |
| `mature-age` | 否 | `0` | 大于 `0` 时攀爬要求达到该阶段。`0` 表示不设这道门槛，所以写 `0` 与不写完全等价。 |
| `min-light` | 否 | `9` | 出芽转地面以及任何一次攀爬所需的最低亮度。 |
| `max-stack-height` | 否 | `3` | 仅作 fallback，通常不起作用，见下。 |
| `budding-max-age` | 否 | `3` | 出芽番茄转为地面番茄的阶段。 |
| `tomatoes-max-age` | 否 | `3` | 地面阶段的最大阶段，也是右键收割视为成熟的阶段。 |
| `hanging-max-age` | 否 | `3` | 攀绳阶段的最大阶段。 |
| `bonemeal-climb-chance` | 否 | `0.3` | 对地面/攀绳番茄使用骨粉时尝试沿绳攀爬的概率。 |
| `bonemeal-bonus-min` / `bonemeal-bonus-max` | 否 | `1` / `4` | 出芽阶段骨粉阶段加成的闭区间范围。 |

三个 ID 必须在三个方块上写得一致：行为拿位置上的方块 ID 去和 `budding-block` / `tomatoes-block` / `crop-on-rope-block` 比对，某个方块自己那份行为写了不同的 ID，它的 tick 就会静默地什么都不做。攀绳方块只要挂了带 `max-height` 且大于 `0` 的 `bush_block`，`max-stack-height` 就会被覆盖——自带配置正是这种情况，要改高度请改 `bush_block` 的 `max-height`。攀爬还要求正上方那格挂着 `farmersdelight:rope` 行为本身，而不只是方块 ID 等于 `rope-block`；后者只用于事后恢复绳子。这个行为没有 `is-bone-meal-target`，骨粉一律接受。三个方块各自都需要名字正好是 `age` 的整数属性和 `is-randomly-ticking: true`；这个 age 属性在行为加载时就会校验，缺了的方块是在加载时被剥掉行为（控制台会点名），而不是照常放置、照常 tick 却永远什么都不干。

蘑菇群落（mushroom colony）——按阶段生长的蘑菇簇，支持剪刀/小刀采收、随机 tick 生长和骨粉：

```yaml
behavior:
  - type: bush_block
    bottom-block-tags:
      - minecraft:mushroom_grow_block
    bottom-blocks:
      - farmersdelight:rich_soil
      - farmersdelight:organic_compost
  - type: farmersdelight:mushroom_colony
    mushroom-type: "minecraft:brown_mushroom"
    light-requirement: 0
    grow-on-blocks:
      - farmersdelight:rich_soil
      - farmersdelight:organic_compost
states:
  properties:
    age:
      type: int
      default: 3
      range: 0~3
```

| 参数 | 必填 | 默认值 | 作用 |
| --- | --- | --- | --- |
| `age-property` | 是 | `age` | 表示生长阶段的整数属性名。**必需**：方块上必须存在这个名字的 `int` 属性，否则方块照样加载但不带这个行为，控制台里会点名该属性。 |
| `max-age` | 否 | `age` 范围的上限 | 生长和骨粉的阶段上限。 |
| `grow-speed` | 否 | `0.25` | 每次随机 tick 生长一阶的概率。 |
| `light-requirement` | 否 | `0` | 随机 tick 生长所需的最低亮度。**`0` 表示完全跳过亮度检查**，而不是要求亮度 0。 |
| `bonemeal-min-age-bonus` / `bonemeal-max-age-bonus` | 否 | `1` / `2` | 骨粉阶段加成的闭区间范围。注意前缀是 `bonemeal-` 而非 `bone-meal-`，它与 `tall_crop` 上那个是两个不同的参数，写法不能互换。 |
| `mushroom-type` | 是 | 空 | 采收时掉落的物品。**实际上是必填项**——没有有效 ID 时群落根本无法采收。 |
| `harvest-tool-tags` | 否 | 空 | 视为剪刀类工具的物品标签。 |
| `harvest-tool-items` | 否 | 空 | 具体物品 ID。只有 `minecraft:shears` 算剪刀类，其余一律算小刀类。 |
| `grow-on-blocks` / `grow-on-block-tags` | 否 | 回落到 `bottom-blocks` / `bottom-block-tags`；后者也没有时则完全不做土壤检查 | 群落**继续生长**所需要坐落的方块。 |

剪刀类工具掉落 1 个蘑菇并把阶段减一；小刀类工具掉落数量等于当前阶段的蘑菇并把阶段清零。无论是否配了 `harvest-tool-items`，`config.yml` 的小刀列表始终作为 fallback 生效。骨粉无法通过这个行为关闭。`mushroom-type` 还决定粒子颜色：ID 中含 `red` 渲染红色粒子，其余渲染棕色。

不要在这个行为上混用两套土壤键。同时写 `grow-on-blocks` 和 `bottom-block-tags` 会让 `grow-on-*` 这一对胜出，你写的 `bottom-block-tags` 被丢弃。选定一对，两个键都用它。四个键全都不写，和在另外三个行为上不写土壤规则不是一回事：这个行为没有内置的土壤 fallback，不配就等于只要 `bush_block` 允许它立住，它在哪儿都能长。另外这两个参数只管生长，群落能**存在**于哪里是同一方块上 `bush_block` 的事——自带配置有意把后者的范围放得更宽。

### 3.8 土壤类行为

沃土（rich soil）——每次随机 tick 先尝试把正上方的蘑菇转化为蘑菇群落；没转化成的话，再掷一次概率给上方植物施骨粉，上方不行则改为下方：

```yaml
behavior:
- type: farmersdelight:rich_soil
  boost-chance: 0.2
  brown-mushroom-colony: farmersdelight:brown_mushroom_colony
  red-mushroom-colony: farmersdelight:red_mushroom_colony
  unaffected-blocks:
  - minecraft:grass_block
  - minecraft:short_grass
  - minecraft:moss_block
  - minecraft:sunflower
  - farmersdelight:brown_mushroom_colony
  - farmersdelight:red_mushroom_colony
  - farmersdelight:wild_rice
```

（上面的排除列表是自带配置的节选，完整列表见 `blocks.yml`。）

| 参数 | 必填 | 默认值 | 作用 |
| --- | --- | --- | --- |
| `boost-chance` | 否 | `0.08` | 每次随机 tick 尝试催熟的概率。小于等于 `0` 关闭催熟，蘑菇转化仍然照常进行。自带方块用的是 `0.2`。 |
| `brown-mushroom-colony` | 否 | `farmersdelight:brown_mushroom_colony` | 上方棕色蘑菇被转化时放置的方块。 |
| `red-mushroom-colony` | 否 | `farmersdelight:red_mushroom_colony` | 上方红色蘑菇被转化时放置的方块。 |
| `unaffected-blocks` | 否 | 空 | 催熟需要跳过的方块。每个沃土方块各自持有自己的列表。 |

`unaffected-blocks` 的条目有三种形态：开头带 `#` 的是**标签**，以 `minecraft:` 开头或完全不带命名空间的是原版方块，其余一切都是 CraftEngine 方块 ID。

标签**不**按命名空间分流。任何一个 `#` 条目，不管什么命名空间，都会被放进那份用来比对自定义方块的标签集合——自定义方块只要在自己的 `settings.tags` 里声明了该标签就算命中。与此同时，每个 `#` 条目还会**额外**去服务器方块标签注册表解析一次（包含数据包标签），解析出来的那个标签才是用来比对原版方块的。所以 `#minecraft:dirt` 既能命中真正的泥土，也能命中任何在 settings 里声明了 `minecraft:dirt` 的自定义方块——这不是纸上谈兵，自带的 `farmersdelight:rich_soil` 声明的正是这个标签。非原版命名空间的标签通常在原版这一侧解析不出东西，实际表现才像“只对 CraftEngine 生效”，但那是注册表查不到的结果，并不是什么按命名空间划分的规则。写了一个服务器不认识的 `minecraft:` 标签会在加载时输出控制台警告；其他命名空间的标签则始终不报，因为 CraftEngine 不对外提供标签索引，无从校验。

匹配时先问 CraftEngine，所以任何条目都不可能通过自定义方块的伪装材质命中它：`minecraft:` 方块条目根本够不到自定义方块，而 `#minecraft:` 标签只能通过该方块自己声明的标签列表命中它，绝不会经由伪装材质。要排除某个自定义方块，请写它的 CE ID，或者写一个它确实声明了的标签。

催熟就是一次普通的骨粉施用，凡是服务器认为可以被骨粉催熟的东西都会受影响，包括其他插件和数据包的作物。转化同时认原版蘑菇和本插件那两个外观相同的自定义蘑菇，且群落一律以 age 0 放置，不会直接长满。

方块必须有 `settings.is-randomly-ticking: true`，否则整个行为根本不会运行。自带方块还带了 `minecraft:dirt`、`minecraft:bamboo_plantable_on`、`minecraft:mushroom_grow_block` 三个标签；不写这些，作物、竹子和蘑菇都会拒绝这块土。

沃土耕地（rich soil farmland）——原版耕地的湿度循环，外加满湿度时催熟上方植物，以及被压实时退回沃土：

```yaml
behavior:
- type: farmersdelight:rich_soil_farmland
  boost-chance: 0.08
  rich-soil-block: farmersdelight:rich_soil
states:
  properties:
    moisture:
      type: int
      default: 0
      min: 0
      max: 7
```

| 参数 | 必填 | 默认值 | 作用 |
| --- | --- | --- | --- |
| `boost-chance` | 否 | `0.08` | **仅在满湿度时**，每次随机 tick 给上方植物施骨粉的概率。小于等于 `0` 关闭催熟，湿度仍然照常循环。 |
| `rich-soil-block` | 否 | `farmersdelight:rich_soil` | 上方被实心方块压住时退回成的方块。 |
| `unaffected-blocks` | 否 | 借用 `rich-soil-block` 所指方块的列表 | 催熟需要跳过的方块。条目写法同上。 |
| `moisture-property` | 是 | `moisture` | 表示湿度的整数属性名。**必需**：方块上必须存在这个名字的 `int` 属性，否则方块照样加载但不带这个行为，控制台里会点名该属性。 |

方块必须声明一个范围 `0~7` 的整数湿度属性。名字可以用 `moisture-property` 改，但配成什么名字就必须存在什么名字的属性，否则这块地会以一块没有湿度循环、没有催熟、也不会被覆盖回退的死耕地放出来，原因只写在控制台里、世界里看不出来。湿度上限硬编码为 7，不可配置。下雨或方块周围 9×9×2 范围内有水会让湿度直接跳到 7，否则每次随机 tick 减一。由于催熟只在满湿度时掷骰，干燥的农田永远不会催熟。

本方块没写 `unaffected-blocks` 时，排除列表会借用沃土方块自己的那份——自带的这一对就是靠这个机制用一份列表同时管住两个方块。注意在这里显式写 `unaffected-blocks: []` 与不写并不相同：前者是把本方块的排除项清空，而不是去借。

压实退回的判定用的是碰撞体积，西瓜、南瓜、移动中的活塞和各类栅栏门除外。作物没有碰撞体积，所以种东西不会让土退回。

有机堆肥（organic compost）——每次随机 tick 扫描自身周围 3×3×3 范围，把激活方块、水和天空光照的加成累加成一个概率，成功则 `composting` 前进一阶，到达最高阶段时转化为沃土：

```yaml
behavior:
- type: farmersdelight:organic_compost
  rich-soil-block: farmersdelight:rich_soil
  max-stage: 7
  activator-bonus-per-neighbor: 0.02
  water-bonus: 0.10
  light-high-bonus: 0.10
  light-low-bonus: 0.05
  light-threshold: 12
  activators:
  - minecraft:brown_mushroom
  - minecraft:red_mushroom
  - minecraft:podzol
  - minecraft:mycelium
  - farmersdelight:brown_mushroom
  - farmersdelight:red_mushroom
  - farmersdelight:brown_mushroom_colony
  - farmersdelight:red_mushroom_colony
  - farmersdelight:organic_compost
  - farmersdelight:rich_soil
  - farmersdelight:rich_soil_farmland
states:
  properties:
    composting:
      type: int
      default: 0
      range: 0~7
```

| 参数 | 必填 | 默认值 | 作用 |
| --- | --- | --- | --- |
| `max-stage` | 否 | `7` | 达到或超过该阶段时转化，而不是继续前进。应当与 `composting` 声明范围的上限保持一致。 |
| `rich-soil-block` | 否 | `farmersdelight:rich_soil` | 完成时放置的方块。 |
| `brown-mushroom-colony` / `red-mushroom-colony` | 否 | 自带的两个群落 ID | 供插件其他部分读取，本行为自己并不放置它们。 |
| `activator-bonus-per-neighbor` | 否 | `0.02` | 3×3×3 范围内每找到一个激活方块增加的概率。 |
| `water-bonus` | 否 | `0.10` | 范围内有水时增加的概率。是一次性加成，不按水方块数量累加。 |
| `light-high-bonus` | 否 | `0.10` | 范围内最高天空光照**严格大于** `light-threshold` 时增加的概率。 |
| `light-low-bonus` | 否 | `0.05` | 否则增加的概率。两者互斥，总有一个生效。 |
| `light-threshold` | 否 | `12` | 上述两档之间的天空光照分界值。 |
| `activators` | 否 | 空 | 计为激活方块的方块。条目写法与 `unaffected-blocks` 相同。 |
| `composting-property` | 是 | `composting` | 表示发酵阶段的整数属性名。**必需**：方块上必须存在这个名字的 `int` 属性，否则方块照样加载但不带这个行为，控制台里会点名该属性。 |

每 tick 的概率 = 激活方块数量 × `activator-bonus-per-neighbor` + 对应档位的光照加成 + 范围内有水时的 `water-bonus`。

这里有两点常让人意外。3×3×3 的扫描把堆肥方块自己也算进去，所以像自带配置那样（照抄原模组）把 `farmersdelight:organic_compost` 列进 `activators`，等于给每个堆肥方块一个永久的 +1 激活数，成堆放置还会自我加速；删掉这一条会明显拖慢发酵。另外光照没有“不加成”这一档：不写那两个光照参数，仍然会加上 `0.10` 或 `0.05`。想让光照不起作用，把两者设成相同值；想让黑暗中的堆肥不因光照而推进，把 `light-low-bonus` 设为 `0`。

`max-stage` 是用“大于等于”和实时属性值比较的，所以它应该等于属性声明范围的上限。设低了，更高的阶段永远到不了；设高了，方块永远转化不成沃土，会卡在最高阶段。

方块必须声明一个表示发酵阶段的整数属性。名字可以用 `composting-property` 改，但配成什么名字就必须存在什么名字的属性，否则方块照样能放、看着也还是堆肥，却永远不发酵、也永远不转化，原因只留在加载日志里。此外它同样需要 `settings.is-randomly-ticking: true`。

### 3.9 绳子、榻榻米与上半掉落转发

绳子：

```yaml
behaviors:
  - type: farmersdelight:rope
states:
  properties:
    north:
      type: boolean
      default: false
    east:
      type: boolean
      default: false
    south:
      type: boolean
      default: false
    west:
      type: boolean
      default: false
```

**这个行为完全没有配置参数**，写在它下面的任何东西都不起作用。四个连接属性按固定名字查找；缺了某一个不会报错，症状是这根绳子在那一侧永远连不上。

连接关系在放置时一次性决定，此后不会再向上重新推导。点在**水平面**上时，绳子可以拴在任何提供完整稳固面的方块上；点在**竖直面**上时，只能拴到绳子、铁栏杆、玻璃板和墙。之后的邻居更新只会重新套用那套受限判定，所以“拴在普通实心方块上”这种连接只能维持到那一侧发生变化为止。手持同种绳子右键绳子会向下放一格到第一个可替换的位置；空手右键则沿绳柱向上找并敲响遇到的第一个钟，搜索距离由 `config.yml` 的 `rope.bell-ring-max-distance` 决定。

自带绳子带有 `minecraft:climbable`、`minecraft:fall_damage_resetting`、`minecraft:mineable/shears`、`minecraft:sword_efficient` 四个标签。这些都不会从被伪装的方块继承，自定义绳子必须自己声明。

榻榻米：

```yaml
behavior:
- type: farmersdelight:tatami
  block-id: farmersdelight:tatami
  facing-property: facing
  paired-property: paired
  pair-while-sneaking: false
states:
  properties:
    facing:
      type: direction
      default: down
    paired:
      type: boolean
      default: false
```

| 参数 | 必填 | 默认值 | 作用 |
| --- | --- | --- | --- |
| `block-id` | 否 | `farmersdelight:tatami`，或上一次解析留下的值 | 配对判定中被认作“榻榻米”的方块 ID。 |
| `facing-property` | 是 | `facing` | 朝向属性名。 |
| `paired-property` | 是 | `paired` | 是否已配对的布尔属性名。 |
| `pair-while-sneaking` | 否 | `false` | `false` 表示潜行放置时不配对，与原版手感一致；`true` 表示总是配对。 |

放置时 `facing` 被设为所点击面的反向，因此放在地面上得到的是一张独立的平垫，贴着另一张榻榻米的侧面放则得到水平朝向，这正是编织长垫纹理的来源。随后配对会把两张垫子一起改写。

这三个名字是**全服共享的、最后解析的配置生效**：同一时刻只有一个方块 ID 会被认作榻榻米。它们的默认值还是自引用的——删掉这个键再重载，之前设过的值会保留下来，而不是恢复成初始值。也就是说一旦写过某个值，之后省略它就不再等于写默认值。另外插件不会校验 `block-id` 是否指向一个存在的方块。

**这两个属性都是必需的。** 挂了这个行为却解析不到其中任何一个的方块照样加载、照样能放，只是不带榻榻米行为了：放置不写朝向、不配对、也拼不出编织长垫。缺的那个属性会在控制台里被点名，而那条消息是这次失败唯一露面的地方——方块本身看着毫无异样。`paired` 必须是布尔，因为配对标记是按布尔写回去的，同名但类型不对和干脆没有一样，代价都是丢掉整个行为。`facing` 则只按名字查找：它的值是按文本读写的，任何取值能拼出方向名的属性类型都能用。真正要满足的是**实际会被写入的那些值**——放置时写的是所点击面的反向，所以放在地面上写的是 `down`。属性没有的值会被丢弃、状态保持不变，因此 `4-direction` 这种没有 `down` 的类型，会让地面放置的垫子停在自己的默认朝向上。

上半掉落转发——用于双高方块，让破坏上半时掉落**下半**的战利品表，而不是上半那张通常为空的表：

```yaml
behavior:
  - type: double_high_block
  - type: farmersdelight:wild_rice
    requires-water: true
  - type: farmersdelight:upper_half_loot_relay
```

它的默认值已经与 CE `double_high_block` 的命名一致，所以本插件就是按上面这种不带参数的形式使用它的。如果你的植物上下半布局比较特殊：

| 参数 | 必填 | 默认值 | 作用 |
| --- | --- | --- | --- |
| `lower-half-direction` | 否 | `DOWN` | 从本方块指向其下半的方向。无法识别的名字会静默回退为 `DOWN`。 |
| `require-matching-lower-half` | 否 | `true` | 该方向上的方块是否必须确实是一个合法的下半。按严格布尔读取。 |
| `require-matching-block` | 否 | `true` | 下半是否必须是同一个方块 ID。`false` 表示任何 half 值匹配的自定义方块都算。按严格布尔读取。 |
| `half-property` | 是 | `half` | 保存 lower/upper 的属性名。**必需**：方块上必须存在这个名字的属性，否则方块照样加载但不带这个行为——上半就只掉自己那张表了——控制台里会点名该属性。只按名字查找、不校验类型，因为上下半的值是按文本比较的。 |
| `half-lower-value` / `half-upper-value` | 否 | `lower` / `upper` | 代表上下半的属性值，比较时忽略大小写。 |

把两个 `require-*` 都设成 `false` 并不能让转发变成无条件的：状态首先仍然必须被识别为上半。而且当方块同时挂了 `farmersdelight:tall_crop` 时，未成熟的上下半配对会在轮到转发之前就直接抑制掉落——未成熟的高作物不会掉落成熟产物正是靠这条路径。如果解析出的下半战利品为空，转发不做任何事，按正常掉落处理。

### 3.10 物品行为与食物效果函数

`farmersdelight:conditional_block_planting` 是一个**物品**行为：让同一个物品根据点击到的方块放置不同的方块。注意物品的 `behavior` 是单个映射，不是列表：

```yaml
items:
  minecraft:brown_mushroom:
    behavior:
      type: farmersdelight:conditional_block_planting
      rules:
        - target: farmersdelight:rich_soil
          block: farmersdelight:brown_mushroom
        - target: farmersdelight:organic_compost
          block: farmersdelight:brown_mushroom
```

| 参数 | 必填 | 默认值 | 作用 |
| --- | --- | --- | --- |
| `rules` | 是 | **必填** | 规则列表。没有默认值，也没有“放行”这种兜底：不写、写成空列表、或者所有条目都因为格式不对被丢弃，都会在加载时抛错。CraftEngine 会保留这个物品，只丢掉它的这个行为。 |
| `rules[].target` | 是 | 必填 | 必须被点击的方块：CE 方块 ID，或原版的 `minecraft:<block>`。 |
| `rules[].block` | 是 | 必填 | 放置到上方一格的 CE 方块。 |

不是映射的条目、缺少任一个键的条目都会被跳过——但并不静默：这两种情况在加载时各自输出一条带物品名的警告。两条 `target` 相同的规则只有最后一条生效，这才是唯一完全没有提示的情况。规则里写了 CraftEngine 不认识的方块同样不在加载时报错：只有当有人真的点到对应目标时才会被发现，届时**每次点击**输出一条警告并放行这次点击。此外点击面不是顶面、没有规则命中、上方一格不是空气、或者被领地插件拒绝建造时，这次点击也都会原样交还给 CraftEngine 和原版。

这种“放行”正是它有用的地方：可以把这个行为挂到原版物品上而不破坏它。自带配置里，棕色蘑菇点在普通泥土上照常放置原版蘑菇，点在沃土或有机堆肥上则放置本插件的自定义蘑菇。记得让被放置方块自己的存活规则也指回同一批土壤，否则它会在下一次方块更新时脱落。

`farmersdelight:comfort` 和 `farmersdelight:nourishment` 用来施加本插件的两个食物效果。它们是**事件函数，不是战利品函数**——应当写在事件列表里，例如食物的 `on: consume`；写进战利品表的 `functions` 列表是解析不出来的：

```yaml
farmersdelight:beef_stew:
  events:
    - on: consume
      functions:
        - type: farmersdelight:nourishment
          duration: 180
          level: 1
```

| 参数 | 必填 | 默认值 | 作用 |
| --- | --- | --- | --- |
| `duration` | 否 | `90` | 效果持续时间，单位是**秒**不是 tick。可写 CE number provider。使用时夹到至少 1。 |
| `level` | 否 | `1` | 效果等级，从 1 开始计。可写 CE number provider。使用时夹到至少 1。 |
| `condition` / `conditions` | 否 | 无 | 标准 CE 谓词。这是少数同时接受两种写法的参数之一。 |

新的一份效果与身上已有的按原版药水规则叠加：更强的替换并刷新时长，等强的延长到较长的剩余时间，更弱的被忽略。

这两个函数会绕过 `config.yml` 里的单项效果开关。`buff.comfort.enabled` 和 `buff.nourishment.enabled` 只管第 13 节描述的那套配置驱动的食物列表；带 `type: farmersdelight:comfort` 的食物即使在 `buff.comfort.enabled: false` 下也照样给予舒适效果。唯一能让它们失效的开关是 `buff.enabled: false`。另外注意 `buff.comfort.heal-interval-ticks: 0` 会保留效果显示但停掉回血，效果看起来生效了却什么都不做。

本插件自己的资源没有使用这两个函数——自带食物是通过 `config.yml` 接的。它们是留给资源包作者和附属插件用的。

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

工作站成品经验奖励（顶层：厨锅与所有通过 API 结算经验的附属工作站共用）：

```yaml
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

- `drops.mob-extra.<entity>.normal`
- `drops.mob-extra.<entity>.burning`
- `drops.straw.<type>.drop`
- `container-returns.<item>`
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

小刀识别（顶层：切菜板、煎锅、蘑菇簇、水稻收割、草秆掉落与配方浏览器读的是同一份列表）：

```yaml
knife-items:
  tags:
    - farmersdelight:knives
  items:
    - farmersdelight:flint_knife
    - farmersdelight:iron_knife
    - farmersdelight:golden_knife
    - farmersdelight:diamond_knife
    - farmersdelight:netherite_knife
```

用 `drops.mob-extra-tools` 里配置的工具击杀生物时的额外掉落：

```yaml
drops:
  mob-extra-tools:
    tags:
      - farmersdelight:knives
    items:
      - farmersdelight:flint_knife
  mob-extra:
    pig:
      normal: farmersdelight:ham
      burning: farmersdelight:smoked_ham
      chance: 0.5
      looting-multiplier: 0.1
```

`drops.mob-extra-tools` 只是这类掉落的默认触发工具列表，不会让这些物品在别处也被当成小刀。单条规则可以用 `tool-item` / `tool-items` / `tool-tags` 覆盖它。

草秆掉落：

```yaml
drops:
  straw:
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

营养效果（吃下后清除疲劳值、防止掉饿）：吃 `buff.nourishment.foods` 列表里的食物时生效。`duration` 单位为秒，默认按原版模组分档（30 / 60 / 180 / 300）。舒适效果（周期回血）在原版模组里已弃用，因此 `buff.comfort` 默认关闭、列表留空；机制代码保留，需要可自行启用并添加食物。两者都通过 `buff.display` 配置的 buff 显示来呈现自身状态。

```yaml
buff:
  comfort:
    enabled: false
    heal-interval-ticks: 80
    heal-amount: 1.0
    foods: {}
  nourishment:
    enabled: true
    foods:
      farmersdelight:cooked_rice:
        duration: 30        # 30/60/180/300 秒分档
      farmersdelight:beef_stew:
        duration: 180
      farmersdelight:noodle_soup:
        duration: 300
```

容器返还（顶层：厨锅与所有通过 API 返还容器的附属工作站共用）：

```yaml
container-returns:
  farmersdelight:milk_bottle: minecraft:glass_bottle
```

## 14. 世界数据：堆肥、熔炉燃料与交易

`world-data` 负责把本插件的物品接进 CraftEngine 自己够不到的原版世界机制：堆肥桶、熔炉燃料槽，以及两个商人交易池。它下面有 `composting`、`furnace-fuel`、`trades` 三个小节，`trades` 又分成 `villager` 和 `wandering-trader`，因此一共是四个可独立开关的功能：

```yaml
world-data:
  composting:
    enabled: true
    items:
      farmersdelight:straw: 0.3
      farmersdelight:cabbage: 0.65
      farmersdelight:apple_pie: 1.0
  furnace-fuel:
    enabled: true
    items:
      farmersdelight:straw: 100
      farmersdelight:tree_bark: 200
      farmersdelight:straw_bale: 1000
  trades:
    villager:
      enabled: true
      trades:
        - profession: farmer
          level: 1
          ingredient: farmersdelight:onion
          ingredient-amount: 26
          result: minecraft:emerald
          result-amount: 1
          max-uses: 16
          villager-xp: 2
          price-multiplier: 0.05
          chance: 0.14285714285714285
    wandering-trader:
      enabled: true
      generic-trade-count: 5
      trades:
        - ingredient: minecraft:emerald
          ingredient-amount: 1
          result: farmersdelight:cabbage_seeds
          result-amount: 1
          max-uses: 1
          villager-xp: 12
          price-multiplier: 0.05
          chance: 0.014705882352941176
```

每个 `enabled` 默认都是 `true`。写成 `false` 会关掉整个功能但把列表原样留在文件里，这是可逆的关法；删掉列表里的某一条则是关掉那一个物品或那一条交易（见下面的“删掉条目就是禁用它”）。

### 14.1 堆肥

堆肥桶自己不会接受本插件的物品——CraftEngine 物品底层是普通原版材质，而原版只认原版可堆肥物，所以投料由插件代为完成。

`items` 以物品 ID 为键，值是 `0.0` 到 `1.0` 之间的概率，表示一个该物品让堆肥桶上升一层的机率，含义与原版一致。原版“空堆肥桶一定接受第一个物品”的规则在这里同样成立。键不是合法物品 ID，或值不在 `0.0`–`1.0` 之间时，该条目会被跳过并在控制台输出 warning，列表其余部分照常加载。

### 14.2 熔炉燃料

`items` 以物品 ID 为键，值是燃烧时间（tick），必须为正数。烧炼一个物品需要 200 tick，因此 `200` 恰好够烧一个。值不是正数或物品 ID 不合法时，该条目会被跳过并输出 warning。

这些燃烧时间同时会写进 CraftEngine 的物品定义——正是这一步才让这些物品能被放进熔炉燃料槽。`/fd reload config` 会重新写入，所以改完列表不必等 CraftEngine 重载就能生效。

### 14.3 村民与流浪商人交易

原版商人的交易池无法从插件侧加入，因此这里的每条交易是按“本插件的条目本来会被抽中的概率”去顶替一条抽出的原版交易。`chance` 就是这个概率，也就是本插件条目在该池中所占的份额。数据包如果改动了原版交易池，份额随之改变，此时需要自行重新调整 `chance`。

`trades` 是一个**交易列表**，不是按 ID 索引的映射。每一条可写：

| 键 | 默认值 | 作用 |
| --- | --- | --- |
| `profession` | 村民交易必填 | 村民职业 ID 路径，例如 `farmer`。流浪商人没有职业，不使用该键。 |
| `level` | `1` | 条目加入哪一级村民的交易池。流浪商人不使用该键。 |
| `ingredient` | 必填 | 玩家交出的物品 ID。 |
| `ingredient-amount` | `1` | 交出数量。小于 `1` 会被抬到 `1`。 |
| `result` | 必填 | 玩家获得的物品 ID。 |
| `result-amount` | `1` | 获得数量。小于 `1` 会被抬到 `1`。 |
| `max-uses` | `16` | 补货前该交易可用次数。小于 `1` 会被抬到 `1`。 |
| `villager-xp` | `1` | 该交易给予商人的经验。 |
| `price-multiplier` | `0.05` | 原版需求与声望价格倍率。 |
| `chance` | `1.0` | 该条目赢下一次抽取的概率。大于 `1.0` 会被夹到 `1.0`；写 `0` 或负数则该条目被丢弃。 |

村民列表里缺 `profession` 的条目，或 `ingredient` / `result` 不是合法物品 ID 的条目，会被跳过并输出 warning。

`wandering-trader` 另有 `generic-trade-count`，默认 `5`：流浪商人有多少个交易是从这些条目所竞争的通用池中抽取的。它剩下的那个交易来自另一个稀有池，本插件不往里加东西。

### 14.4 删掉条目就是禁用它

`world-data` 里的每个列表都自带一份与原模组数据完全一致的默认值，所以在这些小节出现之前生成的 `config.yml` 上功能照样能用。一旦你的文件里带上了某个列表，该列表就整体替换内置那份：只有你文件里还留着的条目会生效。

因此删掉某一条就是禁用它——把 `farmersdelight:straw` 从 `composting.items` 里删掉，草秆就不再能堆肥；把某条交易从 `trades.villager.trades` 里删掉，就再没有农民会挂出它。更新插件也不会把它加回来：配置更新器把这四个列表当作内容注册表处理，只有当你的文件里完全没有该小节时才整段创建，绝不会往你已有的小节里逐条补内容。

`/fd reload config` 会整体重建 `world-data`。

## 15. 命令与权限

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

## 16. 重载建议

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

## 17. 常见问题

CE 物品名称显示成 ID：检查 CE 物品的显示名、语言文件和资源包是否加载。本插件配方页会优先使用物品本身的名称。

配方详情里原料展示不好看：先确认 CE 侧物品名是否正常，再检查 `gui.yml` 的详情页布局和 `show-ingredient-ids`。

漏斗没有按预期工作：先确认 `hopper-interactions.enabled` 是否开启。该功能默认关闭；开启后再确认方向，厨锅上方塞原料，下方抽成品，侧面塞容器。煎锅默认不接受漏斗导热。

修改配置后没变化：先 `/fd reload`。如果动了 CE 资源或资源包，完整重启。

## 18. 编译

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

混淆构建（ProGuard）会保留 Bukkit 主类的生命周期方法、`@EventHandler` 方法（保留但允许改名）、枚举入口和整个公开 API 树 `com.huidu.farmersdelight.api.**`（事件、门面、注册表、快照——与 api-only jar 打包的范围一致）；其余类全部重命名并 repackage 到 `fd`。CraftEngine 方块行为按字符串键 + 工厂引用注册，不依赖类名反射，因此可安全混淆。混淆后插件自有代码只有公开 API 包保持可读。`build/reports/proguard/*-mapping.txt` 仅作内部排查留档（用于反查混淆堆栈），`build/` 已被 git 忽略，不要提交到公开仓库。

调试工具（`/fd debugtools …` 性能测试命令）默认不打进 jar，需要时用 `-PdebugTools=true` 构建：

```powershell
.\gradlew.bat clean shadowJar -PdebugTools=true
```

Folia 环境建议在测试服验证区域调度、容器交互、显示同步和区块卸载保存。
