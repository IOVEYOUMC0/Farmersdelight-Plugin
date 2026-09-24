# FarmersDelight 插件

[English](README.md) | **中文**

FarmersDelight 是基于 CraftEngine 的 Farmer's Delight Paper/Folia 移植插件，提供作物、沃土、烹饪工作站、小刀、食物、配方发现、进度以及供附属使用的公共 API。

## 项目内容

- CraftEngine 物品、方块、模型、资源包和战利品整合。
- 厨锅、砧板、炉灶和煎锅玩法。
- 可配置作物、耕地、绳索、蘑菇群落和储物方块。
- 营养、舒适、食物效果和配方书。
- Folia 安全调度，以及稳定的 `com.huidu.farmersdelight.api` 附属 API。
- 支持 Brewin' And Chewin'、End's Delight、Expanded Delight、Crabber's Delight、Barbeque's Delight 和 Villagers' Delight 等附属。

## 运行要求

- Paper 或 Folia 1.21.4 及以上
- Java 21
- CraftEngine 26.9.1

先安装 CraftEngine，再将 FarmersDelight 放入 `plugins/`。修改插件配置后使用 `/fd reload`，修改 CraftEngine 资源后使用 `/ce reload`。

## 文档

完整的玩家指南、服主指南、附属指南和 API 文档已迁移到 [FarmersdelightPluginWiKi](https://github.com/IOVEYOUMC0/FarmersdelightPluginWiKi)，该仓库可以通过 GitHub 集成连接到 GitBook。

## 构建

```text
./gradlew build
```

构建使用官方 Maven 的 CraftEngine 26.9.1 API。附属通过生成的 FarmersDelight API-only JAR 编译，并要求与本仓库处于同级目录。
