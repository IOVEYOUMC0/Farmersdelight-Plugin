---
icon: rectangle-api
---

[English](../en/farmersdelight-api.md)

# FarmersDelightApi：唯一入口

`com.huidu.farmersdelight.api.FarmersDelightApi` 是面向附属的唯一入口。它标了 `@ApiStatus.NonExtendable`，是 `final` 类、私有构造，通过静态方法取得：

```java
FarmersDelightApi api = FarmersDelightApi.get();
```

`get()` 返回一个进程级单例。它永远不会返回 `null`，在 FarmersDelight 尚未 enable 时调用也是安全的——这个 实例的存在与插件生命周期无关。**不**安全的是在那之前调用具体的服务方法，这正是 `isAvailable()` 的用途。

这个类里有两类成员：实例方法（`api.someCall(...)`）和两个静态方法 （`FarmersDelightApi.consoleMessage(...)`、`FarmersDelightApi.isDebugEnabled(...)`）。这个区分只是写法上的 差别，没有语义含义，照着源码写即可。

## 可用性

### `boolean isAvailable()`

当 FarmersDelight 插件实例存在、且它内部的 enabled 标志为真时返回 true。附属对 api 的每一次调用都应该由它 把门。FDAddonTemplate 和 ExpandedDelight 的 `onEnable` 都在 `saveDefaultConfig()` 之后紧接着做这个检查：

```java
if (!FarmersDelightApi.get().isAvailable()) {
    getLogger().warning("FarmersDelight not available; addon features disabled.");
    return;
}
```

BrewinAndChewin **没有**这么做——它的 `onEnable` 从 `/reload` 守卫经 `saveDefaultConfig()` 和自己的配置 bootstrap，直接走到 `registerAddonBlockNamespace("brewinandchewin")`，整个方法里没有任何 `isAvailable()` 关卡。它靠的是 `plugin.yml` 里的 `depend: [CraftEngine, FarmersDelight]` 来保证加载顺序，`api.isAvailable()` 只在一个运行期站点（`CoasterManager`）出现，不在启动路径上。这里请照模板抄，不要照 BrewinAndChewin 抄：硬 `depend` 只能保证 FarmersDelight **先被 enable**，保证不了它 enable **成功**。

有些 api 方法内部已经会在它为 false 时静默返回（`registerCookingPotRecipe`、`registerCuttingBoardRecipe`、 `createItemDisplay` 等自己就查），但这个检查很便宜，而静默失败比显式提前返回糟糕得多。另一些——所有调度类 入口——不是静默返回而是**抛异常**，见调度页。

### `boolean isFolia()`

当 FarmersDelight 的调度适配层判定当前是 Folia 服务端时返回 true。只有在你确实需要按线程模型分支时才用它 ——调度封装本身在两种服务端上都会做正确的事。

**它未必返回 `false`，也可能抛异常。** 实现是 `plugin != null && plugin.scheduler().isFolia()`：判空只判了 `FarmersDelightPlugin.getInstance()`，没有判 调度适配层。而 `FarmersDelightPlugin.scheduler()` 在适配层字段为 `null` 时抛 `IllegalStateException("Scheduler is not available")`。实例是在 `onLoad` 里赋的，适配层要到 `onEnable` 中段 才构建，并在 `onDisable` 里被置回 `null`——所以在这两个窗口里，`isFolia()` 抛异常而不是返回 `false`。 `runAtLocation`、`runLaterAtLocation`、`runRepeating` 和 `awardCraftingExperience` 是同一个陷阱，它们的判空 也都只判实例。先用 `isAvailable()` 把门（它查的是 enabled 标志），这几个方法就都安全。

## 给接口本身做版本

FarmersDelight 的插件版本号跟的是**内容**。**api 版本**跟的是你编译所依赖的**接口面**，两者独立演进—— 千万不要拿插件版本号来判断能不能用某个功能。

### `int apiVersion()`

返回当前运行构建的 api 接口修订号。文档化的递增策略是：单调递增；每一个**新增**面向附属接口（新的 api 类、方法、事件或 feature id）的版本 `+1`；从不回退，从不复用，纯内部重构不递增。这套方案下根本不做删除和 不兼容改动，所以按修订号 N 编译的附属，在任何大于 N 的修订上都能继续编译和链接。

当前值是 **3**。修订 3 新增了 `api.visual.DisplayGroup`（分组持有封包展示实体并自动声明存活）与 `api.block.HeatSources`（按插件记录热源声明并在重载后自动重放）。修订 1 是第一个暴露 `apiVersion()` 本身的版本。比它更老的构建上这个方法不存在，调用会抛 `NoSuchMethodError`——捕获它并当作修订 0 处理：

```java
int version;
try {
    version = FarmersDelightApi.get().apiVersion();
} catch (NoSuchMethodError pre) {
    version = 0;
}
if (version >= 1) {
    // use revision-1 capabilities
}
```

和 `isAvailable()` 不同，它报告的是编译进去的接口面，所以在插件还在 enable 的过程中它依然有意义。

### `boolean hasFeature(String feature)`

当前构建具备指定能力时返回 true。id 一律小写、连字符分隔；参数在查表前会被 trim 并转小写，`null` 和未知 id 返回 `false`。id 一旦发布就永不移除，所以在旧构建上探测新构建才引入的 id 是安全的。

当前构建应答的完整集合：

| Feature id                    | 含义                                                                                                                                     |
| ----------------------------- | -------------------------------------------------------------------------------------------------------------------------------------- |
| `recipes`                     | 运行时配方注册，以及通用配方书 / 编辑器：`registerRecipeType`、`registerCookingPotRecipe`、`registerCuttingBoardRecipe`、`openRecipeBook`、`openRecipeEditor` |
| `item-displays`               | 纯数据包物品展示：`createItemDisplay`、`updateItemDisplay`、`removeItemDisplay`                                                                   |
| `scheduler`                   | Folia 安全的调度封装：`runAtLocation`、`runLaterAtLocation`、`runRepeating`                                                                      |
| `buffs`                       | 自定义 buff 注册表与 buff boss 栏渲染通道                                                                                                          |
| `station-query`               | `com.huidu.farmersdelight.api.block`：工作站识别与只读快照                                                                                        |
| `harvest-event`               | Java 侧采收处理的 `FarmersDelightHarvestEvent`                                                                                               |
| `cook-start-event`            | 厨锅从空闲转入烹饪时的 `FarmersDelightCookStartEvent`                                                                                             |
| `buff-change-event`           | 自定义 buff 真实等级变化时的 `FarmersDelightBuffChangeEvent`                                                                                      |
| `cooking-experience-location` | `ProfessionCookingExperienceEvent` 带工作站坐标                                                                                              |
| `knife-drop-rules`            | 小刀额外掉落规则注册（`FarmersDelightKnifeDrops`）                                                                                                 |
| `compat-util`                 | `com.huidu.farmersdelight.api.util` 跨版本兼容辅助                                                                                            |
| `debug-tools`                 | `/fd debugtools` 的调试工具扩展挂点                                                                                                             |
| `villager-trades`             | 运行时村民 / 流浪商人交易注册（`FarmersDelightVillagerTrades`）                                                                                      |
| `durable-items`               | 耐久能力与剑解耦：`farmersdelight:durable` 物品设置 + `FarmersDelightItems.damage(...)`                                                            |
| `special-recipes`             | 程序化特殊配方注册（`registerSpecialRecipe` / `unregisterSpecialRecipe` / `specialRecipes`），支持逐配方展示类型                                          |
| `content-check`               | CraftEngine 内容存在性检查（`FarmersDelightContent`）                                                                                         |
| `common-tags`                 | 中心标签注册表：附属注册自己的 标签→物品 映射，整个family解析同一套标签（`registerCommonTags` / `unregisterCommonTags`）                                      |
| `advancement-triggers`        | 统一处理物品获得/制作/产出与食用触发的成就映射                                                                  |

内容加载完成后注册或移除公共标签，会自动重建依赖标签的配方和 GUI 缓存。

关心某一项能力时优先用 `hasFeature`，需要比较先后顺序时才用 `apiVersion()`。和 `apiVersion()` 一样，在 引入它之前的构建上调用 `hasFeature` 会抛 `NoSuchMethodError`，若你要兼容那种构建，第一次探测要包起来。

```java
if (FarmersDelightApi.get().hasFeature("knife-drop-rules")) {
    // register your knife drops
}
```

## `void registerAddonBlockNamespace(String namespace)`

FarmersDelight 会统计并报告 CraftEngine 方块状态的占用情况。默认这份报告只归因 FarmersDelight 自己的命名 空间；把你的命名空间注册进去，你的方块就会一并出现在报告里。BrewinAndChewin 在 `onEnable` 很早就调用了它：

```java
FarmersDelightApi.get().registerAddonBlockNamespace("brewinandchewin");
```

该调用幂等，会把参数 trim 并转小写，容忍结尾的 `:`，`null` 或处理后为空则忽略。它**没有任何玩法影响**， 纯粹是诊断用的账本。

配套的读取方法 `Set<String> addonBlockNamespaces()` 返回已注册命名空间的不可变副本（不带结尾冒号）。它是 给方块状态占用监控用的，附属基本用不到。

## 控制台消息与共享语言系统

FarmersDelight 自带 `lang/en_us.yml` 和 `lang/zh_cn.yml`，释放到 `plugins/FarmersDelight/lang/`。控制台 文案位于顶层的 `console:` 段。

### `static String consoleMessage(String key, Object... args)`

从这些语言文件里取一条控制台文案，让附属的日志输出跟随服主配置的语言，而不是写死英文。注意它是**静态** 方法，而且是**返回**字符串——它不打日志。日志器和级别由调用方决定：

```java
getLogger().info(FarmersDelightApi.consoleMessage("bac.enabled"));
getLogger().info(FarmersDelightApi.consoleMessage("bac.keg_recipes_loaded", "count", recipes.size()));
getLogger().warning(FarmersDelightApi.consoleMessage("bac.resources_release_failed", "error", e.getMessage()));
```

实现层面的解析规则：

* 键会自动加上 `console.` 前缀（除非它已经以此开头）。`"bac.enabled"` 查的是 `console.bac.enabled`。
* 查找顺序：控制台 / 默认语言，然后当前语言，然后 FarmersDelight jar 内置的对应语言文件，最后 `en_us`。
* 未知键会原样返回键本身——**包括自动加上的前缀**，所以控制台上你会看到字面量 `console.bac.enabled`。 看到这个就说明键不存在。
* `args` 是**名值成对**的：`("count", 3, "file", name)` 会替换消息里的 `{count}` 和 `{file}`。参数两两 取用，末尾多出的单个参数被忽略。值经过 `String.valueOf`，所以任何类型都行。

FarmersDelight 的 `lang/en_us.yml` 中的一段：

```yaml
console:
  bac:
    enabled: "Brewin' And Chewin' (CraftEngine addon) enabled."
    config_keys_merged: "Added {count} new setting(s) to config.yml."
```

**动手之前需要知道的限制。** 目前没有任何 api 可以注册附属自带的语言文件。BrewinAndChewin 的 `console.bac.*` 键是直接放在 FarmersDelight 的语言文件里的。第三方附属有两条诚实的路：把自己的键加进已 部署的 `plugins/FarmersDelight/lang/<locale>.yml`（放在那里的键能正常解析）并把这件事写进给服主的说明； 或者自己维护一套文案，只对确定存在的键使用 `consoleMessage`。

### `String resolveTranslations(String text, Player player)`

实例方法。把 `text` 里的 `<l10n:key>`、`<lang:key>`、`<i18n:key>` 标签解析成该玩家语言的文本（依次回退到 默认语言、再回退到原始键），其余内容——包括 MiniMessage 标记——原样保留。键会同时经过 FarmersDelight 的 语言文件和 CraftEngine 的 translations，所以附属可以在自己的 GUI 配置字符串里放本地化占位符。`player` 可为 `null`，此时使用默认语言。更完整的渲染辅助见文本一章。

## `static boolean isDebugEnabled(String category)`

当 FarmersDelight 的共享调试开关打开**且**指定分类在列表里时返回 true。按求值顺序展开的完整条件，每一条都 必须成立：

1. `FarmersDelightPlugin.getInstance() != null`。FarmersDelight 未加载时返回 `false`；与调度类调用不同，它 不会抛异常，因为它委托到的是一次普通字段读取。
2. FarmersDelight 的调试总开关是开的。它读的是 `debug` **或** `debug.enabled`——无论是老式的布尔标量 `debug: true`，还是小节写法 `debug: {enabled: true}`，任一为真即开启，两者默认都是 `false`。总开关关着 时调用会立刻返回 `false`，**根本走不到看分类那一步**——所以 `debug.categories` 里写 `*` 或 `all` 也救不 回一个关掉的总开关。
3. `category` 既不是 `null` 也不是空白串。这道检查排在总开关**之后**，且是无条件的：即使 `debug.enabled` 开着、`debug.categories` 里有 `*`，传 `null` 或纯空白的分类照样返回 `false`。不存在「不给分类就等于全部」 这种兜底。
4. 分类匹配上。`category` 先 trim 再转小写，然后 `debug.categories` 里必须含有 `*`、`all` 或这个规范化后的 串。服主那份列表在加载时也做了同样的规范化（trim、转小写、丢掉空项），所以两边的大小写和多余空格都无所谓 ——只有一个边角：列表用 `Locale.ROOT` 转小写，而你的入参用的是 JVM 默认 locale 转小写。在土耳其语默认 locale 下，含 `I` 的分类名会匹配不上。传本来就是小写的 id 就永远碰不到这个问题。

它的用意是让附属采用与 FarmersDelight 一致的控制台策略：健康启动只留一行汇总，各子系统的计数只在服主明确 要了那个分类时以 `INFO` 打出，否则走 `FINE`。两种路径都不会丢东西——把日志级别调高照样能看到。

```java
String line = FarmersDelightApi.consoleMessage("myaddon.recipes_loaded", "count", loaded);
if (FarmersDelightApi.isDebugEnabled("startup")) {
    getLogger().info(line);
} else {
    getLogger().fine(line);
}
```

启动期的普查行统一用 `startup` 分类，与 FarmersDelight 自身用法一致。分类名就是服主写在 `debug.categories` 里的字符串；FarmersDelight 内部用到的有 `startup`、`config`、`recipe` 等。

## 热源查询

两个实例方法让自定义的烹饪方块直接复用 FarmersDelight 配置好的热源规则，而不是自己写死方块清单：

* `boolean isHeatSource(Block block)`：该方块是否是配置中的热源（能加热厨锅的东西）。
* `boolean isConductor(Block block)`：该方块是否是配置中的导热方块，即能把下方热源的热量往上传。

FarmersDelight 未加载或 `block` 为 `null` 时都返回 `false`。

```java
public boolean isHeated(Block below) {
    FarmersDelightApi api = FarmersDelightApi.get();
    return api.isHeatSource(below) || api.isConductor(below);
}
```

读方块就是在碰世界，所以 Folia 上要从拥有该方块的区域线程调用——见调度一页。

## 其余接口面

`FarmersDelightApi` 上还有一些成员由其它章节负责，这里列出以便定位：

* 配方：`registerRecipeType`、`unregisterRecipeType`、`recipeTypes`、`recipeType`、 `registerCookingPotRecipe`、`unregisterCookingPotRecipe`、`registerCuttingBoardRecipe`、 `unregisterCuttingBoardRecipe`、`openRecipeBook`（三个重载）、`openRecipeEditor`。
* 数据包物品展示：`createItemDisplay`、`updateItemDisplay`、`removeItemDisplay`。
* 经验：`awardCraftingExperience`。
* 调度：`runAtLocation`、`runLaterAtLocation`、`runRepeating`——见调度一页。

## `@ApiStatus.NonExtendable` 在这里意味着什么

`FarmersDelightApi` 标了 `@ApiStatus.NonExtendable`，同时又是 `final` 加私有构造，所以这个注解更多是意图 声明而不是唯一屏障：不要继承，不要代理，不要自行构造，用 `get()` 拿单例。同样的注解也出现在 `ApiTask` 和 `DebugToolRegistry` 上；当它标在接口上（如 `ApiTask`）时，意思是你可以**使用**这个类型但不要去实现它—— 实现由 FarmersDelight 提供。

## 相关页面

* [快速上手](getting-started.md)
* [事件](events.md)
* [配方包总览](recipes-overview.md)
* [调度](scheduling.md)
