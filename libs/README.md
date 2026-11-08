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
| fork HEAD | `1a3d74e7`；下表所有增量都在**工作树未提交**，按本文件重新构建前必须先用当前工作树，`git pull` 到 HEAD 会丢掉它们 |
| jar 内 `plugin.yml` 版本 | **2.8.1** |
| sha256 | `37EDC961B5F44A5F2CC9C99A0210E3F1093008F9CFA22F6F37B09B0B87C94F96` |
| 字节数 | 652966 |
| 授权 | fork 仓库为 GPL-3.0 全文 + LGPL 附加许可；上游为 LGPL-3.0-or-later。只在本地编译使用，不随本插件分发 |

fork 相对上游的增量（决定本插件能否使用它的原因）：

- 回移上游 2.8.1 的修复（fork 的 `pom.xml` parent 仍写 2.8.0，属未随回移升级的历史遗留）；
- **Folia 支持**；
- **自动进度布局**：`util/AdvancementLayout` 与 `AdvancementTab#registerAdvancements(RootAdvancement, boolean, BaseAdvancement...)`；
- **组件标题与描述**：`AdvancementDisplay` 新增接收 `BaseComponent` 标题/描述的构造器，`usesComponentDisplay()` 随之返回 true，界面、toast 与公告都按客户端语言渲染；组件自身没设颜色时补上边框色（与文本构造器的 `defaultColor` 一致，进度界面标题因此随稀有度上色）；文本构造器行为不变；
- **加载完成事件竞态**：玩家在加载延迟内退出或重进时 `TeamProgression` 已失效，`DatabaseManager` 不再构造
  `PlayerLoadingCompletedEvent`（延迟任务里也不再抛 “Player is null”），未领取奖励的重放在失效时直接跳过；
- **同步上游 2.8.2 的 26.3 NMS 修复**：`CraftItemStack.asCraftMirror/asBukkitMirror` 用反射兜底（Paper 改名时不再 `NoSuchMethodError`）；无效背景贴图返回一个无效贴图而不是 `null`（否则客户端不显示标签页）。上游 2.8.2 的 vanilla 进度禁用修复没回移（本服 `disable-vanilla-advancements: false`），3.0 开发线的数据库重构不动；
- **奖励线程归位**：`Advancement#onGrant` 经 `FoliaCompatibility.isOwnedByCurrentRegion` 判断，只在领取者自己的区域线程调 `giveReward`，否则投递到该玩家的实体调度器（未领取补发时领取者可能不是完成线程）；`TaskAdvancement` 同样处理；
- **进度经验**：没有新增 API，FD 在进度对象上覆写上游就有的 `Advancement#giveReward(Player)` 发经验（多任务只在集齐时一次，未领取的完成在补发时同样给），对应原模组的 `rewards.experience`；
- **公告改用原版键**：`Advancement#getAnnounceMessage` 统一按原版 `chat.type.advancement.<task|goal|challenge>` 组装（客户端本地化，方括号取边框色），不再使用 `AdvancementFrameType#getChatText()` 的英文串；悬停文本对文本构造器的显示直接用其`getChatDescription()`（它本身已含标题），不再重复标题行；
- `/uaapi` 原生命令回退（无 CommandAPI 时可用）、CommandAPI 12 模块、首次展示页修复、display y 非负约束；
- NMS 覆盖 `v1_21_R1..R7`、`v26_1_R2`、`v26_2_R1`、`v26_3_R1`。

## 升级步骤

1. 在 `UltimateAdvancementAPI/` 里 `git pull` 到目标提交，用 Maven 构建插件 jar。整仓 `mvn install` 在本机
   会因 `NMS/Common` 的 Javadoc 和 1.21.1 模块编译失败，只重建改动的模块：
   `mvn -o -DskipTests -Dmaven.javadoc.skip=true -pl Common,Distribution/Shadeable install`，再
   `-pl Plugin package`；产物是 `Plugin/target/UltimateAdvancementAPI-Plugin-<版本>.jar`（本机脚本里
   `-D` 参数要加引号，否则 PowerShell 会把 `.` 之后的片段当成生命周期阶段）。
2. 替换本目录的 `UltimateAdvancementAPI-folia.jar`，并更新上表的提交、版本、sha256 与字节数。
   NMS 模块要用 JDK 25 构建（`JAVA_HOME` 指向 JDK 25）：m2 里 Mojang-mapped 的 `spigot-26.3` 是 class 版本 69，
   JDK 17 的 javac 会报「类文件具有错误的版本 69.0，应为 61.0」；改完 `NMS/26_3_R1` 后依次
   `-pl NMS/26_3_R1 install`、`-pl NMS/Distribution,NMS/DistributionMojangMapped install`，再按上面的
   Common/Shadeable、Plugin 步骤打包。
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
