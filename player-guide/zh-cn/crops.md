# 作物

农夫乐事在原版作物之外新增了一批作物。它们都以**野生植物**的形态自然生长在世界中，你采集它获得种子后，就能像种小麦、胡萝卜一样正常种植。

本页讲解每种作物如何起步、能种在什么方块上、以及如何收获。让作物长得更快的特殊土壤见 [沃土与堆肥](soil.md)；番茄爬绳的玩法见 [绳索](rope.md)。

## 起步：野生植物

想种新作物，先得在世界里找到它的野生形态。不同野生植物偏好不同的生物群系：

| 野生植物 | 生长地点 | 破坏后获得 |
| --- | --- | --- |
| `farmersdelight:wild_tomatoes` | 恶地、沙漠、丛林、热带草原、沼泽、暖水海洋等干热群系 | `farmersdelight:tomato_seeds`（另有 20% 概率掉落 `farmersdelight:tomato`） |
| `farmersdelight:wild_cabbages` | 沙滩、积雪沙滩 | `farmersdelight:cabbage_seeds`（另有 20% 概率掉落 `farmersdelight:cabbage`） |
| `farmersdelight:wild_onions` | 森林、针叶林、平原、热带草原、草甸、樱花林、丛林、沼泽等 | `farmersdelight:onion` |
| `farmersdelight:wild_rice` | 沼泽、红树林沼泽、丛林、河流（生长在浅水中） | `farmersdelight:rice` |

{% hint style="info" %}
**用剪刀**破坏野生植物，收回的是那株装饰性的野生植物方块本身，而不是种子。用手破坏（或在[切菜板](cutting-board.md)上用小刀切）才能拿到种子和产物。
{% endhint %}

在切菜板上用小刀处理野生植物收益最高，还可能额外产出产物或染料：

- **野生番茄** → `farmersdelight:tomato_seeds` + 20% 概率一个番茄 + 10% 概率绿色染料
- **野生卷心菜** → `farmersdelight:cabbage_seeds` + 50% 概率 2 个黄色染料
- **野生洋葱** → 洋葱（及其额外掉落）

---

## 番茄

番茄是本整合包里最讲究的作物：它分三个阶段生长，还能顺着绳索向上攀爬以获得更大产量。

**种子：** `farmersdelight:tomato_seeds`。可从野生番茄获得（见上），也可将单个 `farmersdelight:rotten_tomato` 放入合成格制成。

**种植：** 把番茄种子种在 `minecraft:farmland` 或 `farmersdelight:rich_soil_farmland` 上。生长需要**光照等级 9** 及以上。

**成熟过程：**

1. 种子先长成**待生番茄**（`farmersdelight:budding_tomatoes`），共 4 个阶段（age 0–3）。
2. 完全长成且光照充足后，待生番茄会变成**番茄丛**（`farmersdelight:tomatoes`）。
3. 成熟的番茄丛（age 3）可**反复收获**。

**收获番茄丛：** 右键点击已成熟的番茄丛，采下 **1–2 个番茄**（另有 5% 小概率得到 `farmersdelight:rotten_tomato`）。番茄丛随即回到未熟阶段并重新生长，无需补种。若直接破坏番茄丛，则掉落番茄外加一颗种子。

**骨粉：** 对番茄丛有效，可加速其进入下一次可收获状态。

**攀爬绳索：** 若在成熟番茄丛正上方放置[绳索](rope.md)，藤蔓会顺绳向上攀爬，形成悬挂的 **`farmersdelight:tomato_crop_on_rope`** 方块。每个悬挂番茄成熟后右键收获，与番茄丛相同。完整搭建见 [绳索 → 用绳索种番茄](rope.md#用绳索种番茄)。

---

## 卷心菜

**种子：** `farmersdelight:cabbage_seeds`，来自野生卷心菜或在切菜板上切割。收获成熟卷心菜时也会返还种子。

**种植：** 种在 `minecraft:farmland` 或 `farmersdelight:rich_soil_farmland` 上，光照等级 9 及以上。

**成熟：** 卷心菜共 **8 个阶段**（age 0–7）。骨粉一次可推进多个阶段。

**收获：** 破坏完全成熟（age 7）的卷心菜，获得 **1 个 `farmersdelight:cabbage`** 外加一个或多个种子。未熟就破坏则只能拿回种子，所以请等它熟透。

---

## 洋葱

**种子：** 洋葱没有单独的种子物品——`farmersdelight:onion` 本身既是食物也是可种植物。首批洋葱从野生洋葱采集。

**种植：** 把洋葱种在 `minecraft:farmland` 或 `farmersdelight:rich_soil_farmland` 上，光照等级 9 及以上。

**成熟：** 洋葱共 4 个阶段（age 0–3），骨粉可推进。

**收获：** 破坏完全成熟（age 3）的洋葱，获得一个洋葱外加受时运加成的额外掉落。未熟破坏则得到单个洋葱。

---

## 稻米

稻米是**水生作物**，由上下两截堆叠而成，需用小刀收获。

**种植物品：** `farmersdelight:rice`（收获得到的 `farmersdelight:rice_panicle` 也可用于种植）。

**生长条件：** 稻米必须种在**浸在水中或紧邻水**的泥土类方块或草方块上——把它当作甘蔗对水的需求来对待。它可在较暗的环境下生长（光照等级 6 及以上）。

**成熟：** 下截稻秆逐阶段生长（age 0–4）；到达支撑阶段后，会在顶部长出第二截**稻穗**方块，稻穗再逐渐成熟（age 0–3）。骨粉可助其生长。

**收获：** 顶部稻穗完全成熟后，**用小刀**（任意属于 `#farmersdelight:tools/knives` 标签的物品）收获。你会得到：

- 用小刀割下成熟顶部时得 `farmersdelight:rice`（若以其他方式破坏则得 `farmersdelight:rice_panicle`）；
- 下截稻秆掉落 `farmersdelight:rice`；
- 以及一份 `farmersdelight:straw`（稻草）（用小刀收获时会掉落一个稻草）。

{% hint style="info" %}
**稻米 ↔ 稻穗。** 一个 `farmersdelight:rice_panicle` 可合成为 1 个 `farmersdelight:rice`，或在切菜板上切成稻米 + 稻草。`farmersdelight:rice` 与 `farmersdelight:rice_panicle` 都能压缩成储物块——见 [储物与板条箱](storage.md)。
{% endhint %}

---

## 种子与稻草的来源

有两种材料支撑着整包的农业与合成：

- 新作物的**种子**来自各自的野生植物（用手破坏，或在切菜板上切割）。番茄种子还能用一个腐烂番茄合成。
- **稻草**（`farmersdelight:straw`）是绳索、帆布和堆肥的基础。获取方式：
  - 在切菜板上切割稻穗或野生稻米；
  - 收获成熟稻米；
  - 或**用小刀破坏矮草、高草或完全成熟的小麦**（草有 20% 概率，成熟小麦必定掉落稻草）。

稻草直接用于 [绳索](rope.md)（2 稻草 → 4 绳索）和 [有机堆肥](soil.md#有机堆肥)。
