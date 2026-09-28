---
icon: arrows-rotate
---

[English](../en/migration.md)

# 迁移与升级

无论你是从原模组第一次过来，还是在升级现有的 FarmersDelight 安装，以下这些的行为都和「直接覆盖文件」不一样。方块层面的
迁移参考（旧版 canvas_rug、`boost-chance` 默认值、番茄藤和炉灶灼烧的改动）见
**[管理员 Wiki 的迁移说明](/#migration-notes)**——本页讲的是操作层面的规则。

## 从模组过来

这是一个**移植**，不是模组。预期玩法与 Farmer's Delight 一致，但你服务器上存在的只有这些 CraftEngine 配置实际提供的
内容。世界就是普通的 Paper/Folia 世界——没有模组存档导入这一步。装好插件、生成包，内容就出现了。作物、工作站、食物和配方
都在游戏内像玩家预期的那样放置和制作。

## 配方 id 就是存档键

配方解锁进度（当 `recipes.discovery.enabled: true` 时）以**配方 id** 存储。把配方 id 当成稳定的存档键：

- **重命名**一个配方 id，会让发现系统把它看作全新配方——玩家在旧 id 上的「已解锁」状态就丢了。
- 如果你用发现功能，自己编辑时请保持 id 稳定；否则就接受重命名会重置该配方的解锁状态。

## 配置自动迁移改名的键

每次启动，FarmersDelight 都会拿你的 `config.yml` 与当前运行构建随包的那份对齐，而**不改动你设过的任何值**：

- 新构建**改名**的键会移到新名下，并带着你的值；
- 新构建**弃用**的键会被删除；
- 更新**新增**的设置会被合并进来，连同它们的解释性注释。

所以插件更新后你**不需要**手动迁移 `config.yml`——你调过的值都活着，新设置以默认值出现，死键被清掉。（这过程中若备份失败，
会记录到日志里，而不是中止更新。）

村民与流浪商人交易现已放入 `world-data.yml`，生物额外掉落和稻草掉落规则现已放入 `drops.yml`，砧板的逐物品 / 逐标签显示表
现已放入 `display-overrides.yml`（`items` / `tags`）。只要旧版 `config.yml` 仍含有 `world-data:`、`drops:`、
`cutting-board.display-overrides` 或 `cutting-board.display-tag-overrides` 段，对应段就会自动移入对应文件。
`config.yml` 内被改名的设置（各工作站的 `hopper-interactions` 子开关改为 `allow-hopper`；`performance.*` 归入
`warnings` / `budgets` / `proxy-display` 三组）会就地重写。移动前相关文件都会备份；之后请编辑独立文件。

## 随包文件只在缺失时安装

升级时有三条不同的「仅当缺失才安装」规则要注意，因为它们意味着**你对随包内容的编辑会在更新后保留，但新的随包内容不一定
会自己送到**：

### CraftEngine 资源

全套内置资源只在第一次启动时释放**一次**。之后：

```yaml
craftengine-resources:
  auto-completion: true
```

`auto-completion: true`（默认）会在后续启动时**还原被删 / 缺失**的资源文件——但从不覆盖已存在的文件。如果你是故意删了
某些随包配置、不想它们被加回来，就设成 `false`。无论哪种设置，**你就地编辑过的文件永远不会被覆盖**，所以一次改动了随包
方块 / 配方文件的插件更新，**不会**落到已经有该文件的服务器上——这类改动请手动合并（或删掉该文件，让 auto-completion
还原新版本）。

### 配方文件

```yaml
recipes:
  merge-missing-bundled: false
```

配方文件**仅当完全缺失时**才写出。更新新增的配方永远不会到达已经有 `recipes/*.yml` 的服务器。启动时，存在于 jar 里但
不在硬盘上的 id 会在控制台被列出一次。想把这些新 id 拉进来，设 `merge-missing-bundled: true`——但如果你是故意删了配方，
就保持 **false**，因为合并会把每个被删配方都带回来。任一设置下，硬盘上已有的 id 都不会被覆盖。同一个开关也管
`recipes/special_recipes.yml` 里的随包卡片：开关为 false 时，从文件里删掉一张卡片就是真的删掉（禁用）。

### 附属的厨锅 / 砧板 / 特殊配方搬进了数据包

CrabbersDelight、BrewinAndChewin、BarbequesDelight 与 EndsDelight 的厨锅、砧板与特殊配方不再放在
`plugins/<附属>/recipes/*.yml`，而是随各自的数据包发布，位于
`plugins/CraftEngine/resources/<附属>/configuration/farmersdelight/`，根键分别是 `cooking_recipes`、`cutting_recipes`、
`special_recipes`。配方 id 与内容都没变，只是摆放位置和生效方式变了：

- 旧文件 `plugins/<附属>/recipes/{cooking_pot_recipes,cutting_board_recipes,special_recipes}.yml` **不再被读取**，
  可以删掉。若你在里面改过配方，先把改动搬到上面那个数据包目录，再 `/ce reload all`（或重启）。
  升级时新文件会由插件自动释放；已存在的同名文件不会被覆盖。
- 改这些配方要 `/ce reload all`（或重启），不再是 `/fd reload`：数据包内容由 CraftEngine 在加载数据包时读取。
- 各附属自己的配方（Brewin' And Chewin 的酒桶发酵与倾倒、BarbequesDelight 的烧烤与串制）同样搬进了数据包，位置是
  `plugins/CraftEngine/resources/<附属>/configuration/recipes/`。它们多了一层：`plugins/<附属>/recipes/<同名文件>.yml`
  **仍会被叠加读取**，同 id 以插件文件为准——游戏内的酒桶配方编辑器写的就是这个文件。所以旧文件可以留着当覆盖层
  （内容与数据包默认值相同，不会改变结果），也可以删掉改用数据包版本；改动数据包里的配方同样要 `/ce reload all`。

### 战利品注入数据包

首次启用时装进每个世界，之后就不再动它——你后续对数据包文件的编辑会在插件更新后保留。见 [首要配置项](first-config.md)。

## 升级清单

1. 停服（`/stop`）——不要热换 jar。
2. 替换 FarmersDelight 的 jar，以及（若有更新）资源包。
3. 启动服务器。让 `config.yml`、`world-data.yml` 和 `drops.yml` 自动迁移、CraftEngine 资源自动补全。
4. 读控制台：看 *Content ready* 行，以及那条一次性的「缺失随包配方」提示，决定你是否要这些配方（`merge-missing-bundled`）。
5. 如果这次更新改动了你曾编辑过的随包 CE 资源文件，手动合并这些改动。
6. 重新生成包（`/ce reload all`），在游戏内确认贴图。
7. 扫一眼 [管理员 Wiki 迁移说明](/#migration-notes)，看你的这次升级有没有需要执行的方块专属一次性迁移步骤。

## 回到指南

[服主指南首页 →](README.md)
