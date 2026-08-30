---
icon: circle-play
---

[English](../en/getting-started.md)

# 快速上手

FarmersDelight 是基于 CraftEngine 的 Paper / Folia 插件。附属（addon）是**一个独立的 Bukkit 插件**，与 FarmersDelight 跑在同一个 JVM 里，硬依赖 FarmersDelight，并在进程内直接调用它的 Java API。这里没有任何 网络协议，也没有命令桥接——你编译时链接一个 jar，运行时直接调方法。

本页讲的是构建配置、plugin.yml 的写法、`onEnable` 里该做什么，以及每个附属都应当装上的生命周期保护。

## 只有 `com.huidu.farmersdelight.api.**` 是稳定接口

这个包以外的内容都是内部实现，可能在版本更新时变化且不提供兼容保证。`api` 包就是全部契约：
它的签名只用 Bukkit 类型、JDK 类型、Adventure 类型和其它 `api` 类型，不会把内部实现泄漏到参数或返回值中。

反过来说，编译期你只需要这一个 api jar。

## 获取 api jar

FarmersDelight 的构建里有一个 `apiJar` 任务，只打包 `com/huidu/farmersdelight/api/**`，不含任何内部实现， 也不是一个能运行的插件：

```bash
# 在 FarmersDelight 仓库里执行
./gradlew apiJar
# -> build/libs/farmersdelight-1.0.2-api.jar
```

现有的附属用了两种接法。

### 方案 A：直接放 jar（FDAddonTemplate）

模板把 jar 放在 `libs/` 下，以 `compileOnly` 引入：

```kotlin
plugins {
    id("java")
    // Shadow bundles your code into one jar. FarmersDelight + CraftEngine are NOT bundled (compileOnly).
    id("io.github.goooler.shadow") version "8.1.7"
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.momirealms.net/releases/") // CraftEngine
    mavenLocal()
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.1-R0.1-SNAPSHOT")
    compileOnly("org.jetbrains:annotations:26.1.0")
    compileOnly("net.momirealms:craft-engine-core:26.8")
    compileOnly("net.momirealms:craft-engine-bukkit:26.8")
    compileOnly(files("libs/farmersdelight-api-1.0.0.jar"))
}

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(21)) }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(21)
}
```

必须是 `compileOnly`：运行时由真正的 FarmersDelight 插件提供实现。把 api jar shade 进自己的插件，只会多出 一份永远不会被用到的死类。

### 方案 B：composite build（BrewinAndChewin、ExpandedDelight）

两个正式附属都放在 FarmersDelight 检出目录旁边，让 Gradle 自动重建并同步 api jar，附属永远不会对着过期的 接口编译：

```kotlin
// settings.gradle.kts
rootProject.name = "brewinandchewin"

includeBuild("../plugin") {
    name = "farmersdelight-plugin"
}
```

```kotlin
// build.gradle.kts
val syncFarmersDelightApi by tasks.registering(Copy::class) {
    group = "build"
    description = "Builds farmersdelight :apiJar via composite build and stages it into libs/."
    dependsOn(gradle.includedBuild("farmersdelight-plugin").task(":apiJar"))
    from(file("../plugin/build/libs")) {
        include("farmersdelight-plugin-*-api.jar")
        rename { "farmersdelight-1.0.0.jar" }
    }
    into("libs")
}

tasks.compileJava { dependsOn(syncFarmersDelightApi) }

dependencies {
    compileOnly(files("libs/farmersdelight-1.0.0.jar"))
}
```

在本仓库的布局里，`../plugin` 是指向 `FarmersDelight` 模块的目录联接（junction）。

两个附属都用 Java 21，且 `options.release.set(21)`。

## plugin.yml

要写 `depend`，不要写 `softdepend`。你的 `onEnable` 跑起来时，CraftEngine 必须已经定义好物品和方块， FarmersDelight 也必须已经建好 api 背后的各项服务：

```yaml
name: FDAddonTemplate
version: '${version}'
main: com.example.fdaddon.FDAddonTemplate
api-version: '1.21'
folia-supported: true
description: Example addon template for the FarmersDelight (CraftEngine) plugin.
authors:
  - YourName
depend: [CraftEngine, FarmersDelight]
```

关于这份文件，几点都来自正在跑的附属：

* `folia-supported: true`：api 的调度封装本身是 Folia 感知的，所以把世界访问都走这些封装的附属，可以名正 言顺地声明支持 Folia。
* FDAddonTemplate 和 BrewinAndChewin 都**没有自己的命令**。重载由 FarmersDelight 驱动（`/fd reload all`）， 配方浏览也由它负责（`/fd recipe book`）。
* 可选联动写进 `softdepend`（BrewinAndChewin 在这里写了 `BreweryX`）。

## onLoad

有两件事必须放在 `onLoad`，赶在 CraftEngine 解析配置之前：

```java
@Override
public void onLoad() {
    // CraftEngine's own api — FarmersDelight does not bridge block-behavior registration.
    if (BuiltInRegistries.BLOCK_BEHAVIOR_TYPE.getValue(Key.of(NS + ":example_block")) == null) {
        BlockBehaviors.register(Key.of(NS + ":example_block"), ExampleBlockBehavior.FACTORY);
    }
    // Copy bundled CraftEngine resources into plugins/CraftEngine/resources/<namespace>/.
    AddonResources.release(this);
}
```

自定义方块行为必须在 `blocks.yml` 被解析之前注册；你内置的 CraftEngine 资源也必须在 CraftEngine 扫描之前 落到磁盘上。这两件事都不走 FarmersDelight 的 api。

## onEnable

下面是 FDAddonTemplate 的 `onEnable`，只保留与生命周期相关的部分。

```java
@Override
public void onEnable() {
    saveDefaultConfig();

    // ALWAYS guard api use with isAvailable(): true only when FarmersDelight is present AND enabled.
    if (!FarmersDelightApi.get().isAvailable()) {
        getLogger().warning("FarmersDelight not available; addon features disabled.");
        return;
    }

    // Count this addon's CraftEngine blocks in FarmersDelight's block-state usage report.
    FarmersDelightApi.get().registerAddonBlockNamespace("fdaddon");

    // Recipe types, recipes, buffs, listeners ... (see the other pages)
    FarmersDelightApi.get().registerRecipeType(new ExampleRecipeType());
    getServer().getPluginManager().registerEvents(new ExampleReloadListener(this), this);
    registerRecipes();

    // Folia-safe repeating task; keep the handle so onDisable can cancel it.
    heartbeat = FarmersDelightApi.get().runRepeating(this::onHeartbeat, 20L, 20L * 60L);

    // /fd debugtools integration. Safe even on a non-debug FarmersDelight build.
    DebugToolRegistry.register(new ExampleDebugExtension(this));

    // Refuse PlugMan-style runtime management of this addon.
    getServer().getPluginManager().registerEvents(new PluginManagerGuard(getName()), this);
}
```

### 配方要等 CraftEngine 的物品就绪

CraftEngine 的物品是延迟加载的，在那一轮加载跑完之前，`FarmersDelightItems.create(...)` 会返回 `null`， 所以配方注册不能只在 enable 时跑一次。两个正式附属都注册两处：`onEnable` 里跑一次（覆盖 CraftEngine 先 加载完的情况），再由监听器在 CraftEngine 的 `CraftEngineReloadEvent` 里跑一次。同一个监听器也顺手接上 FarmersDelight 的 `FarmersDelightReloadEvent`，这样一条 `/fd reload all` 就能把附属一起同步：

```java
public final class ExampleReloadListener implements Listener {

    private final FDAddonTemplate plugin;

    public ExampleReloadListener(FDAddonTemplate plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onFarmersDelightReload(FarmersDelightReloadEvent event) {
        plugin.reloadAddon(event.getReason());
    }

    @EventHandler
    public void onCraftEngineReload(CraftEngineReloadEvent event) {
        plugin.registerRecipes();
    }
}
```

通过 api 注册的配方能扛过 `/fd reload`：FarmersDelight 重载自己的配方文件时会保留外部注册的配方。

## onDisable

把注册过的东西撤掉，api 调用同样用 `isAvailable()` 把门，并取消所有任务句柄：

```java
@Override
public void onDisable() {
    if (heartbeat != null) {
        heartbeat.cancel();
    }
    if (FarmersDelightApi.get().isAvailable()) {
        FarmersDelightApi.get().unregisterRecipeType(NS + ":example");
        FarmersDelightApi.get().unregisterCookingPotRecipe(NS + ":example_stew");
        FarmersDelightApi.get().unregisterCuttingBoardRecipe(NS + ":example_cut");
        CustomBuffRegistry.unregister(exampleBuff);
    }
    DebugToolRegistry.unregister("example_block");
}
```

`DebugToolRegistry` 是静态注册表，活得比你的插件类加载器久，所以反注册不是可选项——留一条陈旧条目，就等于 让一个已经拆掉的 manager 一直可达。

## 重载与热卸载的三道防线

FarmersDelight、CraftEngine 以及建立在它们之上的附属，都会在自己的插件类加载器里留下大量后期绑定的引用： CraftEngine 方块行为、逐区块的方块实体、调度任务、事件 lambda。一旦 `/reload` 或者插件管理器关掉了类加载 器，这些引用就会在无法预测的时间点抛 `NoClassDefFoundError`。这件事没有办法做安全，所以项目的答案是**大声 拒绝**。一共三道机制，附属三道都应该装上。

### 1. JVM 级别的重载守卫

系统属性能挺过插件类加载器的重建，所以同一个 JVM 里的第二次 `onEnable` 是可以被识别并拒绝的。 FarmersDelight 用的是 `farmersdelight.enabled.in.this.jvm`，BrewinAndChewin 照抄了同样的做法：

```java
private static final String RELOAD_GUARD_PROPERTY = "brewinandchewin.enabled.in.this.jvm";

@Override
public void onEnable() {
    if (System.getProperty(RELOAD_GUARD_PROPERTY) != null) {
        getLogger().severe("PLEASE DO NOT /reload OR HOT-DISABLE Brewin' and Chewin'.");
        getLogger().severe("To apply config changes: /stop then start the server again.");
        getServer().getPluginManager().disablePlugin(this);
        return;
    }
    System.setProperty(RELOAD_GUARD_PROPERTY, "1");
    // ... real startup
}
```

属性名自己起。原版 `/reload` 是核心命令，监听器拦不住——这正是除了下面那道守卫之外还需要这一道的原因。

### 2. `PluginManagerGuard`：拒绝 PlugMan 一类的命令

`com.huidu.farmersdelight.api.util.PluginManagerGuard` 是一个开箱即用的 `Listener`，用你自己的插件名 （写在 `plugin.yml` 里的那个）构造。它的公开接口面就这些：

```java
public final class PluginManagerGuard implements Listener {

    public PluginManagerGuard(String pluginName);

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPlayerCommand(PlayerCommandPreprocessEvent event);

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onServerCommand(ServerCommandEvent event);
}
```

你唯一会碰的成员是构造器——那两个 handler 之所以是 public，是 Bukkit 事件系统的要求，不是让你去调的。其余 一切（命令名集合、动作词集合、匹配逻辑、拒绝提示）都是 private。类是 `final`，并且**不带任何** `@ApiStatus` 注解——这点和 `FarmersDelightApi`、`ApiTask`、`DebugToolRegistry` 不同；反正它既没有可继承的东西，也没有可 实现的东西。

`pluginName` 原样存一份用于拒绝提示，另外用 `Locale.ROOT` 转一次小写用于匹配。它没有判空——传 `null` 会在 构造器里抛 `NullPointerException`。老实用 `getName()`。

```java
getServer().getPluginManager().registerEvents(new PluginManagerGuard(getName()), this);
```

FDAddonTemplate 和 BrewinAndChewin 都装了它（ExpandedDelight 没装）。它以 `EventPriority.LOWEST` 且 `ignoreCancelled = true` 监听 `PlayerCommandPreprocessEvent` 和 `ServerCommandEvent`，当下面三条同时成立时 取消命令：

* 命令名（剥掉可能存在的 `namespace:` 前缀之后）属于 `plugman`、`plm`、`pluginmanager`、`plugmanx`、 `plmx`、`pluginsmanager`、`pl`；
* 第一个参数属于 `unload`、`reload`、`disable`、`enable`、`load`、`restart`、`stop`、`start`；
* 后面某个参数与你的插件名大小写不敏感地相等。

`info`、`list` 这类只读动作会被放行。命令发起者会收到一条提示，告诉他改用 `/stop` 重启。原版 `/reload` 不在覆盖范围内——那是上一道守卫的活。

### 3. `RequiredPluginWatchdogListener`：让运行时禁用逐级传导

FarmersDelight 自己装了 `CraftEngineWatchdogListener`：服务器还在跑的时候 CraftEngine 被禁用，它就把自己 也关掉，而不是让每一个绑在 CraftEngine 上的任务狂抛异常。你的附属应该在下一层做同样的事。这是 BrewinAndChewin 的完整实现：

```java
public final class RequiredPluginWatchdogListener implements Listener {

    private static final Set<String> REQUIRED = Set.of("FarmersDelight", "CraftEngine");

    private final BrewinChewinPlugin plugin;

    public RequiredPluginWatchdogListener(BrewinChewinPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPluginDisable(PluginDisableEvent event) {
        if (!REQUIRED.contains(event.getPlugin().getName())) {
            return;
        }
        if (plugin.getServer().isStopping() || !plugin.isEnabled()) {
            return; // normal shutdown, or we are already going down
        }
        plugin.getLogger().severe(event.getPlugin().getName() + " was disabled while the server is running.");
        plugin.getLogger().severe("Restart the server (/stop) to bring both back up.");
        plugin.getServer().getPluginManager().disablePlugin(plugin);
    }
}
```

那两个提前 return 很关键：没有它们，正常关服时这个监听器就会误触发，而那时候所有插件本来就在关。用 `MONITOR` 优先级是因为它只观察和响应，从不修改事件。

### 关于持久化状态的顺序问题

你的附属依赖 FarmersDelight，所以它**先于** FarmersDelight 和 CraftEngine 被禁用。如果你的附属要持久化 方块实体状态，就得在 `onDisable` 的最开头、类加载器还开着的时候把它冲刷或预序列化掉。BrewinAndChewin 的 `onDisable` 第一件事就是调用 `KegChunkListener.passivateLoadedChunks()`，原因正是 CraftEngine 序列化区块 发生在 BAC 已经消失之后，那时再进入酒桶的保存代码会以 "zip file closed" 失败。

## 接下来

* 入口类本身（`apiVersion`、`hasFeature`、`isAvailable`、`isFolia`、`consoleMessage`、`isDebugEnabled`、 `registerAddonBlockNamespace`）见 FarmersDelightApi 一页。
* 调度（`runAtLocation`、`runLaterAtLocation`、`runRepeating`、`ApiTask`）单独成页，包含 Folia 下每个回调 落在哪个线程。
* `/fd debugtools` 接入见调试工具一页。
* 配方、物品、方块、buff、事件、文本、配置辅助各有自己的章节。

## 相关页面

* [FarmersDelightApi 入口](farmersdelight-api.md)
* [事件](events.md)
* [配方包总览](recipes-overview.md)
* [调度](scheduling.md)
