---
icon: weight-scale
---

# FarmersDelight 管理员 Wiki

[English](README.md)

FarmersDelight 是基于 CraftEngine 的 Farmer's Delight Paper/Folia 移植。它提供作物、土壤、烹饪工作站、绳索、
蘑菇簇、配方书、进度和与附属共享的 API；CraftEngine 负责资源包、物品、方块、模型、战利品和原版配方数据。

## 从哪里开始

- [服主指南（中文）](server-guide/zh-cn/README.md)：安装、资源包、首次配置与排错。
- [方块行为配置](server-guide/zh-cn/block-behaviors.md)：CE 标签、方块列表和所有可配置的 FD 行为模式。
- [玩家指南（中文）](player-guide/zh-cn/README.md)：游戏内各工作站、作物与物品。
- [开发者 API（中文）](api-docs/zh-cn/README.md)：编写附属插件时使用的 Java API。
- [附属指南（中文）](addon-guide/README.zh-cn.md)：各附属提供的内容与配置入口。

## 配置边界

`plugins/FarmersDelight/config.yml` 放运行时开关、性能、权限和显示参数；
`plugins/CraftEngine/resources/farmersdelight/configuration/*.yml` 放 CE 物品、方块、标签、战利品和配方。

修改前者后执行 `/fd reload config`，修改后者后执行 `/ce reload all`。不要执行 Bukkit `/reload`。

CE 配置保持无注释。字段解释、示例和标签写法均维护在[方块行为配置](server-guide/zh-cn/block-behaviors.md)中，避免注释在用户更新资源时漂移。

## 附属

Brewin' And Chewin'、End's Delight、Crabber's Delight、Barbeque's Delight 与 Villagers' Delight 都可选装。
前四者依赖 FarmersDelight 与 CraftEngine；Villagers' Delight 为独立的村民 AI 插件。它们的方块和物品标签会通过
FarmersDelight 的统一标签解析链路参与配方和行为判定。
