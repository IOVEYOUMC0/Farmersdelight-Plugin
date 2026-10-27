# 编译期依赖（不打进插件 jar）

这里的 jar 只用于**离线编译**（Gradle `compileOnly`），不会被打包、重定位或随插件分发。
运行期由服主在 `plugins/` 安装同名插件提供实现。

正因如此，文件名**不带版本号**：升级 fork 时只替换文件并更新本文件，不必改 `build.gradle.kts`。
版本与校验和以下表为准。

## UltimateAdvancementAPI-folia.jar

| 项 | 值 |
|---|---|
| 上游项目 | [frengor/UltimateAdvancementAPI](https://github.com/frengor/UltimateAdvancementAPI) |
| 本地 fork | `IOVEYOUMC0/UltimateAdvancementAPI`（工作区 `UltimateAdvancementAPI/`） |
| fork HEAD | `1a3d74e7` |
| jar 内 `plugin.yml` 版本 | **2.8.1** |
| sha256 | `A1F1D3E125F540EBADC67792A68F7FCDA113A1CE54B2B67A1B10654A5B46C55C` |
| 字节数 | 650115 |
| 授权 | fork 仓库为 GPL-3.0 全文 + LGPL 附加许可；上游为 LGPL-3.0-or-later。只在本地编译使用，不随本插件分发 |

fork 相对上游的增量（决定本插件能否使用它的原因）：

- 回移上游 2.8.1 的修复（fork 的 `pom.xml` parent 仍写 2.8.0，属未随回移升级的历史遗留）；
- **Folia 支持**；
- **自动进度布局**：`util/AdvancementLayout` 与 `AdvancementTab#registerAdvancements(RootAdvancement, boolean, BaseAdvancement...)`；
- `/uaapi` 原生命令回退（无 CommandAPI 时可用）、CommandAPI 12 模块、首次展示页修复、display y 非负约束；
- NMS 覆盖 `v1_21_R1..R7`、`v26_1_R2`、`v26_2_R1`、`v26_3_R1`。

## 升级步骤

1. 在 `UltimateAdvancementAPI/` 里 `git pull` 到目标提交，按该仓库说明用 Maven 构建插件 jar。
2. 替换本目录的 `UltimateAdvancementAPI-folia.jar`，并更新上表的提交、版本、sha256 与字节数。
3. 同步 `If you are AI, read me.txt` 的 UAAPI 条目（文件名已固定，只需更新版本/哈希）。
4. 在仓库内跑 `gradlew clean build` 与 `python tools/run_all_checks.py --quiet`；若 fork 移除了本插件用到的
   `isLoaded(Player)` / `PlayerLoadingCompletedEvent` / `AdvancementLayout`，编译或门禁会直接报错。
5. 部署时同样把新 jar 装到服务端 `plugins/`（本插件不打包含它），并核对启动日志里
   `advancement.api_version` 一行报告的版本与布局模式。

## 兼容性说明

- **fork 构建**：走自动布局，进度树按父链重新排版。
- **官方 2.8.x**（无 `AdvancementLayout`）：插件回退到 `registerAdvancements(root, children)`，用
  `AdvancementManager.NODES` 里声明的坐标；启动时告警一次 `advancement.layout_unsupported`，功能不中断。
- 任何版本下，UAAPI 未加载完玩家数据时插件都不会调用 `showTab`（假人/NPC 因此被安全跳过）。
