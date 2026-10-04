<h1 align="center">FarmersDelight 插件</h1>

<p align="center">
  <img src="https://img.shields.io/badge/Minecraft-1.21.5%2B-3fb950" alt="Minecraft 1.21.5+">
  <img src="https://img.shields.io/badge/CraftEngine-26.8.2%2B-5865F2" alt="CraftEngine 26.8.2+">
  <img src="https://img.shields.io/badge/Java-21-orange" alt="Java 21">
  <img src="https://img.shields.io/badge/Folia-supported-blueviolet" alt="Folia supported">
  <img src="https://img.shields.io/badge/API-com.huidu.farmersdelight.api-blue" alt="附属 API">
</p>

<p align="center">
  <a href="README.md">English</a> | <b>简体中文</b>
</p>

<p align="center"><i>基于 CraftEngine 的 Farmer's Delight 模组 Paper/Folia 移植。</i></p>

---

## 关于

FarmersDelight 是基于 CraftEngine 的 Farmer's Delight Paper/Folia 移植插件，提供作物、沃土、烹饪工作站、小刀、食物、配方发现、进度以及供附属使用的公共 API。

## 项目内容

- CraftEngine 物品、方块、模型、资源包和战利品整合。
- 厨锅、砧板、炉灶和煎锅玩法。
- 可配置作物、耕地、绳索、蘑菇群落和储物方块。
- 营养、舒适、食物效果和配方书。
- Folia 安全调度，以及稳定的 `com.huidu.farmersdelight.api` 附属 API。
- 支持 Brewin' And Chewin'、End's Delight、Expanded Delight、Crabber's Delight、Barbeque's Delight 和 Villagers' Delight 等附属。

## 运行要求

- Paper 或 Folia 1.21.5 及以上
- Java 21
- CraftEngine 26.8.2 及以上（用 26.9.1 编译；26.8.2 / 26.9 / 26.9.1 已实机核对）

先安装 CraftEngine，再将 FarmersDelight 放入 `plugins/`。修改插件配置后使用 `/fd reload`，修改 CraftEngine 资源后使用 `/ce reload`。

## 文档

完整的玩家指南、服主指南、附属指南和 API 文档已迁移到 [FarmersdelightPluginWiKi](https://github.com/IOVEYOUMC0/FarmersdelightPluginWiKi)，该仓库可以通过 GitHub 集成连接到 GitBook。

## 构建

```text
./gradlew build
```

构建使用官方 Maven 的 CraftEngine 26.9.1 API，加 `-PceVersion=<版本>` 可改为对着别的版本编译。

`build` 会在 `build/libs/` 下生成两个产物：可运行的插件本体（`farmersdelight-<版本>.jar`，即 shadow jar）和给附属用的 API-only jar（`farmersdelight-plugin-<版本>-api.jar`，其内容与模块的默认 `farmersdelight-plugin-<版本>.jar` 一致），后者只含 `com.huidu.farmersdelight.api.**`。该 API 以坐标 `com.huidu.farmersdelight:farmersdelight-plugin:<版本>` 发布，附属通过两条通道之一消费：

* **本地检出（首选）**：把本仓库检出在附属旁边（`../FarmersDelight`）时，附属构建是 Gradle 复合构建，会构建本仓库的 `:apiJar` 并替换该坐标，无需拉取、可离线构建。
* **无本地检出**：否则附属通过 Gradle source dependency 从本 git 仓库按固定版本拉取并就地构建——该通道需要网络。

## 致谢

本插件移植的上游模组是 **Farmer's Delight**，作者 **vectorwing**，以 **MIT** 许可分发：

- Modrinth：https://modrinth.com/mod/farmers-delight
- 源码：https://github.com/vectorwing/FarmersDelight

搬运的贴图、配方、数值与游戏行为仍归原作者版权所有，按上游的 MIT 许可分发；随 jar 一起发布的
MIT 声明在 `src/main/resources/NOTICE.txt`，本仓库自身的归属表见 [NOTICE.md](NOTICE.md)。

本插件是该模组独立的 Paper/Folia 移植，与上游作者无隶属关系，也未获其背书或维护。

## 授权

GNU Affero General Public License v3.0 only，全文见 [LICENSE](LICENSE)。

本插件**完整开源**：配方编辑器、配方关联跳转、手持煎锅烹饪都在本仓库里。允许使用、修改、再分发
（收费分发也可以），前提是满足 AGPL-3.0：保留版权与授权声明、随分发提供完整对应源码、修改版同样以
AGPL-3.0 授权；**如果把修改版作为网络服务提供给他人使用，还要向该服务的使用者提供源码**。

第三方内容（搬运的 Farmer's Delight 素材、shade 进来的库，均为 MIT）保留各自的声明，见
[NOTICE.md](NOTICE.md)。

本仓库是本插件唯一的源码来源。其他人发布的构建产物（无论有没有加过代码）都与作者无关。
