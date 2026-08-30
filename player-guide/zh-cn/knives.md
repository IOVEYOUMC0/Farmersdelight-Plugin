
[English](../en/knives.md)
# 小刀

小刀是农夫乐事的标志性工具。它既是**厨房工具**（用于切菜板，并被多种收获机制读取），也是一件**轻型武器**——出手快、伤害中等——而且用它击杀动物时能让对方额外掉落食材。

## 五种小刀

本移植版提供五个小刀档次，每种材质一把：

| 小刀 | 攻击伤害 | 攻击速度 |
|------|----------|----------|
| `farmersdelight:flint_knife` | 2.5 | 2.0 |
| `farmersdelight:golden_knife` | 1.5 | 2.0 |
| `farmersdelight:iron_knife` | 3.5 | 2.0 |
| `farmersdelight:diamond_knife` | 4.5 | 2.0 |
| `farmersdelight:netherite_knife` | 5.5 | 2.0 |

所有小刀的攻击速度都是 2.0——比剑快、又比空手慢——以生伤害换取更快的恢复。伤害随材质递增，所以燧石小刀是你的第一把武器，下界合金小刀则是顶级档次。

## 合成

其中四把小刀用单一材料压在木棍上、以竖直图案合成：

- `farmersdelight:flint_knife`——燧石 + 木棍
- `farmersdelight:iron_knife`——铁锭 + 木棍
- `farmersdelight:golden_knife`——金锭 + 木棍
- `farmersdelight:diamond_knife`——钻石 + 木棍

下界合金小刀无法直接合成。要在锻造台上，用下界合金升级模板和一块下界合金锭升级一把 `farmersdelight:diamond_knife`，与升级下界合金工具的做法完全相同。（铁小刀和金小刀也能在熔炉或高炉里熔回一颗粒。）

## 使用小刀

除了当武器，小刀还是驱动农夫乐事各项收获与备料机制的工具：

- **切菜板**——把可切割的物品放到切菜板上，再用小刀对它使用即可加工（例如把肉和鱼切成切件，或切分农产品）。这是获取「切件」类食物的主要方式。
- **煎锅**——在煎锅上烹饪用的是同一套小刀判定。
- **收获**——收获蘑菇簇和稻米都要用小刀。
- **稻草**——用小刀破坏草、高草、成熟小麦或成熟稻米，会产出 `farmersdelight:straw`（稻草），一种前期合成材料。

五把小刀中的任意一把在以上所有场合都算数——服务器把它们作为一组来追踪（`farmersdelight:tools/knives` 标签），所以燧石小刀能用的地方，下界合金小刀也一样能用。

## 小刀的生物掉落

用小刀击杀**成年**动物，会在该生物正常战利品之外额外产出一份掉落物。完整列表由服务器设定，本移植版的默认值为：

| 生物 | 额外掉落 | 概率 |
|------|----------|------|
| 猪 | `farmersdelight:ham`（着火时为 `farmersdelight:smoked_ham`） | 50%（每级抢夺 +10%） |
| 疣猪兽 | `farmersdelight:ham`（着火时为 `farmersdelight:smoked_ham`） | 100% |
| 牛、哞菇 | `minecraft:leather` | 100% |
| 马、驴、骡、羊驼、行商羊驼 | `minecraft:leather` | 100% |
| 鸡 | `minecraft:feather` | 100% |
| 蜘蛛、洞穴蜘蛛 | `minecraft:string` | 100% |
| 兔子 | `minecraft:rabbit_hide` | 100% |
| 潜影贝 | `minecraft:shulker_shell` | 100% |

火腿是其中的重头戏：小刀是获取 `farmersdelight:ham` 的途径，你再把它烹成极度顶饿的 `farmersdelight:smoked_ham`——或者，如果你在猪着火时击杀它，它会直接掉落烟熏火腿。

服主可以添加其他工具作为掉落触发器，也可以重新调整或禁用其中任意一项掉落；如果你的服务器改过配置，实际掉落可能有所不同。
