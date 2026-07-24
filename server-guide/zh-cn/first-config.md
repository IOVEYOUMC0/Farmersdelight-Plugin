---
icon: sliders
---

# 首要配置项

`plugins/FarmersDelight/config.yml` 的默认值是照着原模组调的，全新安装原样就能玩。本页只列**大多数服主第一天会改**的
那几项，细节链接到 **[管理员 Wiki](/)**。它不重复整份配置——管理员 Wiki 的 *Master config (`config.yml`)* 一节才是
参考手册。

改完用 `/fd reload config` 应用（或重启）。**不要**对整个服务器执行 `/reload`。

## 控制台 / 日志语言

```yaml
language: ''
```

留空则跟随 JVM / 系统语言。用 `zh_cn` 或 `en_us` 强制指定。这一项影响的是**服务器日志、控制台文本，以及没有玩家上下文
的消息**——各个玩家的界面和聊天仍按各自的客户端语言显示。控制台语言的解析规则见 [故障排查](troubleshooting.md)。

## Buff 显示（boss 血条）

```yaml
buff:
  enabled: true
  display:
    enabled: true
    channels:
      - bossbar
    layout-mode: stacked
    styles:
      nourishment: {color: GREEN, overlay: PROGRESS}
      comfort:     {color: BLUE,  overlay: PROGRESS}
```

控制饱足 / 舒适 buff（以及任何附属 buff）怎么显示。如果 boss 血条区域已经被别的插件占用，把 `channels` 换成
`actionbar` 或 `tab_footer`；或者设 `display.enabled: false`，保留效果但隐藏血条。详见管理员 Wiki 的 *Effects* 和
*Buff bossbar API* 两节。

## 效果：饱足 / 舒适（Nourishment / Comfort）

```yaml
buff:
  nourishment:
    enabled: true
  comfort:
    enabled: false
```

饱足（吃列表里的食物会暂停饥饿流失）默认开启；舒适默认关闭，和原模组一致。两者各有一份「食物 → 持续时间」列表。
改食物列表前，先看管理员 Wiki 的 *Effects (Comfort / Nourishment)* 一节。

## 漏斗交互

```yaml
hopper-interactions:
  enabled: true
```

漏斗 ↔ 工作站桥接的总开关（烹饪锅、砧板、煎锅）。如果漏斗和你别的插件打架，**先把这个总开关关掉**隔离问题，再重新开启
并调各工作站的 `hopper-interactions:` 子开关。细节见管理员 Wiki 的 `cutting-board` 和 `cooking-pot` 说明。

## 配方发现（锁定配方书）

```yaml
recipes:
  discovery:
    enabled: false
```

默认关闭——每个配方在书里立刻可见。开启后，配方在 FarmersDelight 自己的烹饪锅 / 砧板查看器里以**锁定**状态起步，随玩家
解锁逐人揭示。锁定只影响书的显示，从不阻止在工作站实际制作。`locked-display`、`unlock-on-obtain`、`notify` 见管理员
Wiki（以及开发者文档的 *配方发现* 页）。

> 配方的解锁进度以**配方 id** 为键。重命名配方 id 会重置它的发现进度。见 [迁移与升级](migration.md)。

## 原版战利品注入

```yaml
loot-injection:
  install-datapack: true
```

首次启用时，FarmersDelight 会安装一个数据包，把它的物品注入原版的箱子 / 生物 / 草丛战利品表。如果你自己或用别的插件
管理战利品表，设为 `false`。数据包需要重启（或对数据包执行 `/reload`）才生效，而且你后续对数据包文件的改动会在插件更新
后保留。

## 进度

```yaml
advancements:
  enabled: true
```

设 `enabled: false` 关掉整棵进度树。保持开启时，`auto-disable-missing: true` 会隐藏那些你删掉了 CraftEngine 内容的
进度，让进度树仍可完成。参考：管理员 Wiki 的命令 / 迁移说明，以及开发者文档的 *Advancements* 页。

## 更深的开关在哪

性能预算、粒子 / 音效、显示偏移、热源、生物额外掉落、宠物食物、村民 / 流浪商人交易、堆肥与熔炉燃料值，都在 `config.yml`
里带行内注释，并在管理员 Wiki 中有文档。第一天基本用不到。

## 下一步

[确认行为已加载 →](verifying.md)
