---
icon: rocket
---

# 服主指南

[English](../en/README.md)

这是把 **FarmersDelight** 跑起来的上手流程：你需要什么、怎么装、怎么确认装好了、大多数服主第一天会动的几个开关，
以及控制台出问题时怎么读日志。

本指南刻意写得很短，深入的东西一律指向 **[管理员 Wiki](/)**。管理员 Wiki 已经写全了每一节 `config.yml`、每个方块定义、
效果、命令和迁移参考，本指南**不重复**这些——它只负责把你从「下载好的 jar」带到「能跑的服务器」，跑起来之后就交给管理员
Wiki。

## 按这个顺序读

1. **[环境要求与加载顺序](requirements.md)** —— Paper/Folia 与 Java 版本，以及为什么 CraftEngine 必须先加载。
2. **[安装](install.md)** —— 放 jar、首次启动、`/ce reload all`，再确认方块和物品都在。
3. **[资源包](resource-pack.md)** —— CraftEngine 怎么发包，以及你真正会碰到的两种故障。
4. **[首要配置项](first-config.md)** —— 大多数服主第一天会改的那几个 `config.yml` 设置，细节链接到管理员 Wiki。
5. **[方块行为配置](block-behaviors.md)** —— CE 方块列表、标签及所有可配置的 FD 行为模式。
6. **[确认行为已加载](verifying.md)** —— 怎么读一条 CraftEngine 行为报错：它是「某个方块配置写错了」的信号，不是崩溃。
7. **[故障排查](troubleshooting.md)** —— 现象 → 原因 → 解决，外加日志在哪。
8. **[迁移与升级](migration.md)** —— 全新安装或更新时哪些东西会带过来，以及为什么有些随包文件永远不会被覆盖。

## 60 秒速览

- 把 **CraftEngine** 和 **FarmersDelight** 丢进 `plugins/`。CraftEngine 是硬依赖，会自动先加载。
- 启动一次服务器。FarmersDelight 会把它的 CraftEngine 资源释放到
  `plugins/CraftEngine/resources/farmersdelight/`，把自己的 `config.yml` 释放到 `plugins/FarmersDelight/`。
- 按 CraftEngine 的文档生成并托管资源包，然后执行 `/ce reload all`。
- 给自己来一个 `farmersdelight:cooking_pot`，确认物品能正常解析、放下、打开。

以上都成了，就装完了——直接跳到 [首要配置项](first-config.md)。没成的话，[故障排查](troubleshooting.md) 兜底。
