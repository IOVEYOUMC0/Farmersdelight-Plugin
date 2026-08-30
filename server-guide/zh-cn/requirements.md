---
icon: list-check
---

[English](../en/requirements.md)

# 环境要求与加载顺序

## 服务端软件

FarmersDelight 支持 **Paper** 和 **Folia**。Folia 是完整支持的——插件声明了 `folia-supported: true`，调度全部走
Folia 感知的封装。

| 要求 | 取值 |
| --- | --- |
| 服务端 | Paper 或 Folia |
| Minecraft | **1.21.1**（1.21.x 线） |
| `api-version` | `1.21` |
| Java | **21 或更高** |

插件针对 **Paper 1.21.1** 构建与测试，声明的 `api-version` 是 `1.21`，所以 1.21 / 1.21.1 是下限。更新的 1.21.x
构建没问题；低于 1.21 不支持。

Java 21 是硬要求——jar 编译到 Java 21 字节码级别，旧版 JRE 加载不了。用 CraftEngine 和现代 Paper 本来就需要的那套
Java 21+ 运行时即可。

## CraftEngine 是硬依赖

FarmersDelight 是一个 **CraftEngine 移植**。它提供的每个方块、物品、配方、模型都是以 CraftEngine 内容形式定义的。
`plugin.yml` 的 `depend:` 里列了 CraftEngine，这意味着：

- 必须安装 CraftEngine，否则 FarmersDelight 根本不会启用；
- 服务端会**自动**在 FarmersDelight 之前加载 CraftEngine——加载顺序不需要你操心；
- 启动时 FarmersDelight 会等 CraftEngine 把物品和方块解析完，再去注册配方和内容。

装一个与你服务端兼容的 CraftEngine 26.8 构建。当前 FarmersDelight 使用仓库内固定的 **CraftEngine 26.8** API，不能用 26.7.x 构建替代。

## 可选集成（软依赖）

以下这些都**不是必须的**。FarmersDelight 在运行时检测它们，只有插件在场时才点亮对应功能。没有它们一切照常。

- **PlaceholderAPI** —— 暴露 `%farmersdelight_buff_...%` 占位符（见管理员 Wiki 的 *Buff bossbar API + PAPI
  placeholders* 一节）。
- **AuraSkills** —— 让烹饪经验以技能形式结算，而不是原版经验球（`config.yml` 里的 `experience-reward.mode`）。
- **UltimateAdvancementAPI** —— 驱动进度页面 UI。
- **领地 / 圈地插件** —— WorldGuard、GriefPrevention、Lands、Towny、Residence 等一大批插件通过随包的保护层被识别，
  于是工作站使用和采收会尊重领地。除了装上圈地插件本身，不需要额外配置。

以上都不改变安装步骤。一个都没有，直接看 [安装](install.md)。

## 不要热重载插件

FarmersDelight、CraftEngine 以及任何附属都持有指向自身类加载器的活引用（方块行为、逐区块状态、调度任务）。
一次 `/reload` 或 PlugMan 式的卸载会在半途拆掉这些引用，进而报错。要应用改动，请**停服再开**（`/stop`），或用下文
[首要配置项](first-config.md) 里说的定向命令 `/fd reload` / `/ce reload`。插件会主动拒绝对自身的热管理，以保护你的
存档数据。

## 下一步

[安装 →](install.md)
