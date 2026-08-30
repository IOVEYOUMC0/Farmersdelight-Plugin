
[English](../en/food.md)
# 食物

农夫乐事为游戏带来了整整一间厨房的新食物——上百种物品，从你种植或切割出的生食材，到亲手烹调的成品菜肴。本页讲解这些食物的运作方式，好让你知道该吃什么、为什么吃。它并不逐一罗列每样物品，而是把它们归成几大类，并给出经过核实的示例。

## 饱食度与饱和度如何运作

每样食物入口时都会恢复两样东西：

- **饱食度（Nutrition）**——补满你的饥饿条。每 1 点相当于半个鸡腿，所以饱食度为 `6` 的食物能填满三个完整鸡腿。
- **饱和度（Saturation）**——一份隐藏储备，它会在你可见的饥饿条之前被优先消耗。食物的饱和度越高，你在重新变饿之前能奔跑、跳跃、战斗的时间就越长。这正是一顿像样的熟食能让你保持饱腹远超其饥饿条数值所暗示时长的原因。

一份省事的小零食只补一点饥饿、几乎不补饱和度；一顿正经熟食则两者都补得足。举例：

| 食物 | 饱食度 | 饱和度 |
|------|--------|--------|
| `farmersdelight:tomato` | 1 | 0.6 |
| `farmersdelight:fried_egg` | 4 | 3.2 |
| `farmersdelight:hamburger` | 11 | 17.6 |
| `farmersdelight:beef_stew` | 12 | 19.2 |

多数食物只有在饥饿条未满时才能食用，与原版完全一致。少数标注为「随时可食」的食物即使饥饿条已满也能吃下——例如 `farmersdelight:melon_popsicle` 和 `farmersdelight:glow_berry_custard`，以及所有饮料。

## 食物分类

### 生食材与原料

你种植或采集来的作物，可直接生吃，也可用于配方。单吃饱食度都不高——它们真正的价值要在烹调后才显现。示例：`farmersdelight:cabbage`（2 / 1.6）、`farmersdelight:tomato`（1 / 0.6）、`farmersdelight:onion`（2 / 1.6），以及 `rice`、`rice_panicle` 和各类种子物品。

### 小刀切件

在**切菜板**上用小刀处理肉类或农产品，会把它切成更小的份——你能从一份掉落物里多做几顿饭，代价是每块的数值更低。示例：`farmersdelight:minced_beef`（2 / 1.2）、`farmersdelight:chicken_cuts`（1 / 0.6）、`farmersdelight:bacon`（2 / 1.2）、`farmersdelight:cod_slice`（1 / 0.2）、`farmersdelight:salmon_slice`、`farmersdelight:mutton_chops`、`farmersdelight:cabbage_leaf`。

生鸡肉切件与生鸡肉本身风险相同：生吃 `farmersdelight:chicken_cuts` 有几率让你陷入饥饿效果。请先把切件煮熟。

### 熟制主食

把切件放进熔炉、烟熏炉、篝火或**煎锅**里烹熟，就成了顶饿的主食：`farmersdelight:cooked_bacon`（4 / 6.4）、`farmersdelight:beef_patty`（4 / 6.4）、`farmersdelight:cooked_chicken_cuts`（3 / 3.6）、`farmersdelight:cooked_cod_slice`、`farmersdelight:cooked_salmon_slice`、`farmersdelight:cooked_mutton_chops`、`farmersdelight:fried_egg`（4 / 3.2）。火腿是用小刀击杀猪和疣猪兽的掉落物：`farmersdelight:ham`（5 / 3）烹熟后变为 `farmersdelight:smoked_ham`（10 / 16）。

### 手持餐与三明治

在工作台上组装的快捷高价值餐点，是绝佳的旅行口粮。`farmersdelight:hamburger`（11 / 17.6）、`farmersdelight:chicken_sandwich`（10 / 16）、`farmersdelight:bacon_sandwich`（10 / 16）、`farmersdelight:egg_sandwich`（8 / 12.8）、`farmersdelight:mutton_wrap`（10 / 16）、`farmersdelight:dumplings`（8 / 12.8）、`farmersdelight:barbecue_stick`（8 / 14.4，吃完返还一根木棍）、`farmersdelight:kelp_roll`（12 / 12）。

### 汤、炖菜与熟制餐

这是整个整合包的核心，在**炖锅**中烹制、用碗盛装（吃完后碗会返还给你）。它们是游戏里最顶饿的食物，且大多数会授予**营养（Nourishment）**效果——详见 [效果](effects.md) 页。示例：`farmersdelight:beef_stew`（12 / 19.2）、`farmersdelight:vegetable_soup`（12 / 19.2）、`farmersdelight:noodle_soup`（14 / 21）、`farmersdelight:mushroom_rice`、`farmersdelight:cooked_rice`（6 / 4.8）。

### 沙拉

带附加效果的碗装餐点。`farmersdelight:mixed_salad` 和 `farmersdelight:fruit_salad`（均为 6 / 7.2）会给予短暂的生命恢复。`farmersdelight:nether_salad`（5 / 4）可食用，但有几率让你反胃（Nausea）。

### 甜点与烘焙

派、蛋糕和曲奇。`farmersdelight:sweet_berry_cookie` 和 `farmersdelight:honey_cookie`（均为 2 / 0.4，一次合成 8 个）、`farmersdelight:cake_slice`（2 / 0.4，给予短暂速度提升）、`farmersdelight:pie_crust`（2 / 0.8，各类派的基底）。

### 饮料

瓶装饮料，像喝药水一样饮用，可叠加至 16 个，且随时都能饮用：

- `farmersdelight:apple_cider`——给予伤害吸收。
- `farmersdelight:melon_juice`——立即恢复少量生命。
- `farmersdelight:hot_cocoa`——移除多种负面效果（中毒、虚弱、缓慢、挖掘疲劳、反胃、失明、饥饿、凋零）。
- `farmersdelight:glow_berry_custard`——顶饿（7 / 8.4），并给予短暂的发光效果。
- `farmersdelight:milk_bottle`——更轻量的牛奶。随机移除一个状态效果（见 [效果](effects.md)），饮后留下一个空玻璃瓶。

### 动物饲料

用在动物身上而非自己食用的特殊食物。`farmersdelight:dog_food` 喂给已驯服的狼，会给它速度和力量；`farmersdelight:horse_feed` 用在马、驴或骡身上，会给予速度和跳跃提升，并能吸引它们跟随你。

## 可放置的宴席食物

有些菜肴分量足够大，可以作为共享的**宴席**方块放到世界中，供多名玩家取用。对放置好的方块潜行右键或空手右键（盛炖菜时手持一个碗）即可取走一份；每份宴席在用尽前可供取用四次。

宴席方块包括：

- `farmersdelight:roast_chicken_block`
- `farmersdelight:stuffed_pumpkin_block`
- `farmersdelight:honey_glazed_ham_block`
- `farmersdelight:shepherds_pie_block`
- `farmersdelight:gleaming_salad_block`
- `farmersdelight:rice_roll_medley_block`

派同样可以放置，并会被切成小块：`farmersdelight:apple_pie`、`farmersdelight:sweet_berry_cheesecake`、`farmersdelight:chocolate_pie`，以及原版的 `minecraft:pumpkin_pie`（潜行时可放置）。一整个派可切出四块；每块（例如 `farmersdelight:apple_pie_slice`）恢复 3 / 1.8，并给予短暂的速度提升。

可放置的物品会在其提示文本中显示 **Placeable** 标记。

## 食物从何而来

- **种出来**——卷心菜、番茄、洋葱和稻米都是新作物；种植与收获方式见本指南的作物章节。
- **切出来**——在切菜板上用[小刀](knives.md)把生肉和鱼切成切件。
- **煮出来**——炖锅（汤与炖菜）、煎锅（快速煎炒）和炉灶（一个可在其顶上烹饪的热源）把食材变成成品餐。
- **杀出来**——用小刀能让动物额外掉落食材，包括从猪身上得到的火腿。
- **换出来**——农民村民和流浪商人会交易新作物与种子。
