---
icon: puzzle-piece
---

# 附属指南

[English](README.md)

FarmersDelight 的附属是独立加载的插件。除 Villagers' Delight 外，它们都依赖 FarmersDelight 与 CraftEngine，并通过
CraftEngine 资源提供物品、方块、配方和标签，通过 FarmersDelight API 注册工作站配方、进度及需要保存状态的玩法。

## 共同加载规则

1. 先安装 CraftEngine 与 FarmersDelight，再安装附属。
2. 修改附属 CE 资源后执行 `/ce reload all`；修改附属 `config.yml` 后执行 `/fd reload all` 或使用该附属自己的重载命令。
3. CE 配置是无注释的纯数据。方块列表、CE 标签和行为字段的写法见[方块行为配置](../server-guide/zh-cn/block-behaviors.md)。
4. 附属物品可加入 FarmersDelight 的标签来复用通用规则，例如 `farmersdelight:milk`、`farmersdelight:compost_accelerant`。

## 内容概览

| 附属 | 主题与核心内容 | 主要配置 |
| --- | --- | --- |
| Brewin' And Chewin' | 酒桶发酵、奶酪熟成、饮酒效果、冰箱 | `keg`、`booze-effects`、`temperature` |
| End's Delight | 末地食物、末地炉灶、龙腿、末地小刀 | `mob-drops`、`dragon-tooth-knife` |
| Crabber's Delight | 捕蟹笼、蚯蚓箱、钓具、椰子、瓶装便条 | `config.yml` 中的 `crab-trap`、`notes`、`tackle-box`；交易见 `trades.yml` |
| Barbeque's Delight | 烤架、食材盆、烤串调味、托盘 | `grill`、`ingredients-basin`、`seasonings` |
| Villagers' Delight | 让农民村民识别和种植 CE 作物 | `crops`、`extra-soils`、`harvest-drops` |

Expanded Delight 当前未完成，不在本指南的维护范围内。

Crabber's Delight 的村民与流浪商人交易单独写在插件目录的 `trades.yml` 中。该附属尚未发布，因此不提供旧版配置迁移或备份。

## 配方与标签

附属的普通原版配方写在各自 CE 资源中；烹饪锅、砧板和仅用于解释机制的卡片由附属在 FarmersDelight API 中注册。
标签解析同时识别原版标签、CE 物品/方块标签和嵌套标签，因此将附属成员加入一个既有 FD 标签后，配方编辑器预览、配方匹配和行为判定会使用相同结果。
