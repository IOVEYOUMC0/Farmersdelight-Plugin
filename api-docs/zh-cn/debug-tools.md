---
icon: bug
---

# 调试工具：DebugToolExtension 与 DebugToolRegistry

FarmersDelight 有一条管理员命令 `/fd debugtools`，用于压力测试：批量放置方块、批量激活让它们真的开始 tick、 打印性能快照、撤销放置。`com.huidu.farmersdelight.api.util` 里的这两个类型让附属把自己的方块挂进这条命令， 而不必自己再做一套调试 CLI。

对应的 feature id 是 `debug-tools`。

## 前提：只有 debug 构建才会真的跑

`/fd debugtools` 来自一个独立的源集，只有在用 `-PdebugTools=true` 构建 FarmersDelight 时才会被编入；否则 命令类根本不在 jar 里，该命令也不会注册。在正常的发布构建上，注册表大部分处于**休眠**状态：`register` 照常成功， 你的 `place`、`activate`、`cleanupBeforeUndo` 永远不会被调用。`status` 是例外——主插件常驻的 `/fd stats addon <name>`（见下文 `status` 小节）在普通构建里同样会调用它。

这就是为什么无条件注册是安全的，也是 FDAddonTemplate 和 BrewinAndChewin 都在 `onEnable` 里直接注册、不做 任何探测的原因。

## `DebugToolRegistry`

```java
package com.huidu.farmersdelight.api.util;

@ApiStatus.NonExtendable
public final class DebugToolRegistry {

    public static void register(DebugToolExtension extension);
    public static void unregister(String name);
    @Nullable public static DebugToolExtension find(String name);
    public static Collection<String> registeredNames();
    public static Collection<DebugToolExtension> all();
}
```

一个静态、进程级的 `ConcurrentHashMap`，键是小写后的扩展名。几点说明：

* `register` 幂等——同名重复注册会替换掉前一个。`extension` 为 `null`、或其 `name()` 为 `null` 时忽略。
* `unregister(name)` 必须在你的 `onDisable` 里调用。这张表是静态的，活得比你的插件类加载器久；留下陈旧 条目就等于让一个已经拆掉的 manager 一直可达，并在下一次 `/fd debugtools` 时被调用。
* `find` 对未知或 `null` 的名字返回 `null`。`registeredNames()` 与 `all()` 返回不可修改视图；命令的 Tab 补全用的就是 `registeredNames()`。

```java
@Override
public void onEnable() {
    // ...
    DebugToolRegistry.register(new ExampleDebugExtension(this));
}

@Override
public void onDisable() {
    DebugToolRegistry.unregister("example_block");
}
```

## `DebugToolExtension`

```java
package com.huidu.farmersdelight.api.util;

@ApiStatus.OverrideOnly
public interface DebugToolExtension {

    String name();

    int place(Player player, Location origin, int count, int spacing, int layers, UndoSink undo);

    @FunctionalInterface
    interface UndoSink {
        void capture(Location loc);
    }

    default int activate(Player player) { return 0; }

    default List<String> status(Player player) { return List.of(); }

    default void cleanupBeforeUndo(Location location) { }
}
```

`@ApiStatus.OverrideOnly` 与 `NonExtendable` 正好相反：你**应该**实现这个接口，但不应该自己去调用它的方法 ——唯一的调用方是 FarmersDelight。把实现当成回调面来写。

### `String name()`

小写的目标关键词，也就是管理员输入的那个词：

```
/fd debugtools place keg 64 2 3
```

它同时用于 Tab 补全，以及作为 `/fd stats addon <name>` 里的扩展名。`DebugToolRegistry` 注册时会把它转小写，所以直接返回小写形式 以免混淆。

### `int place(Player player, Location origin, int count, int spacing, int layers, UndoSink undo)`

当 `/fd debugtools place <name> [count] [spacing] [layers]` 中的 `<name>` 不是 FarmersDelight 的内置目标时 被调用。返回实际放进世界的方块数。

在你拿到参数之前，命令已经做过这些处理：

* `count` 是 `max(1, 请求值)`，默认 64。
* `spacing` 被夹在 1..16，默认 1。
* `layers` 是 `max(1, 请求值)`，默认 1。
* `origin` 是玩家坐标，已规整到方块坐标。
* 撤销批次已经开启，你之后每次 `undo.capture(...)` 都会并入这一批。

**要注意：** FarmersDelight 自己的 `max-place-count` 上限只作用于内置目标；扩展这条路径把夹过的 `count`、 `spacing`、`layers` 直接传给你，**不会**替你套上限。请自己给放置量兜底，否则管理员输多大就放多大。

排布方式由你决定。两个现成实现都用 `grid = ceil(sqrt(count))`、单元间距 `spacing`、沿 Y 叠 `layers` 层， 和内置目标的做法一致。

在改动每个目标方块**之前**调用 `undo.capture(loc)`。`UndoSink` 是只有一个 `capture(Location)` 的函数式 接口；FarmersDelight 会把该坐标的方块状态快照进当前撤销批次，供 `/fd debugtools undo` 还原。最终没有改变 状态的快照，在撤销时会被静默跳过。

BrewinAndChewin 酒桶实现的精简版：

```java
@Override
public int place(Player player, Location origin, int count, int spacing, int layers, UndoSink undo) {
    if (player == null || origin == null || origin.getWorld() == null) return 0;
    BlockDefinition kegBlock = CraftEngineBlocks.byId(KEG_BLOCK);
    if (kegBlock == null) {
        player.sendMessage("§c[BAC] keg block not registered with CraftEngine.");
        return 0;
    }

    int grid = Math.max(1, (int) Math.ceil(Math.sqrt(count)));
    int total = count * Math.max(1, layers);
    int placed = 0;
    for (int i = 0; i < total; i++) {
        int layer = i / count;
        int layerIndex = i % count;
        Location loc = new Location(
                origin.getWorld(),
                origin.getBlockX() + (layerIndex % grid) * spacing,
                origin.getBlockY() + 1 + layer,
                origin.getBlockZ() + (layerIndex / grid) * spacing);
        Block target = loc.getBlock();
        if (target.getType() != Material.AIR
                && !BlockStateUtils.isReplaceable(BlockStateUtils.getBlockState(target))) {
            continue;
        }
        if (undo != null) undo.capture(loc);
        if (CraftEngineBlocks.place(loc, kegBlock.defaultState(), false)) {
            placed++;
        }
    }
    return placed;
}
```

注意那句 `undo != null` 的防御性判断——目前命令总是会传入 sink。

### `int activate(Player player)`

可选，默认返回 0。在 `/fd debugtools activate <name>` 时被调用；管理员执行 `/fd debugtools activate all` 时，**每一个已注册扩展**的该方法也都会被调用。把你放下的方块填上样本状态， 让它们真的开始 tick、发酵、烹饪——凡是能让它们在性能分析中承压的状态都行——并返回进入活动状态的方块数。 这个数会计入命令的总计。

FDAddonTemplate 自己维护了一个 `ConcurrentHashMap.newKeySet()` 记录放置坐标，因为它的示例方块没有 manager。 如果你的插件本来就有跟踪方块的 manager（比如 BrewinAndChewin 的 `KegManager`），直接遍历那个 manager， 不要重复造一份跟踪。

### `List<String> status(Player player)`

可选，默认返回空列表。由主插件**常驻**的子命令 `/fd stats addon <name>` 触发（`/fd stats` 总览会把每个已注册扩展显示为可点击的名字，点进去即查看对应状态行），因此普通构建也会被调用，无需 `-PdebugTools=true`。每条返回一行，通过把 `{line}` 占位填进翻译键 `command.stats_addon_line` 发送给玩家（`{name}` 为扩展名），自身不带前缀。

```java
@Override
public List<String> status(Player player) {
    return List.of("tracked example_blocks (debug-placed): " + placed.size());
}
```

发送时这一行会作为 `{line}` 替换进 `command.stats_addon_line` 模板，所以不要在行里放字面的 `{line}` 或 `{name}`——它们会被当作替换占位；想带前缀/颜色就写在这一行里，或改那个翻译键。

返回 `null` 是被容忍的（等同于"没有行"），但文档化的退出方式是返回空列表。

### `void cleanupBeforeUndo(Location location)`

可选，默认空实现。在 `/fd debugtools undo` 期间对**每一个被还原的坐标**调用，且发生在方块数据被还原 **之前**。用它释放属于本扩展的内存跟踪、清掉方块实体 NBT，免得被撤销的放置留下幽灵状态。

调用点有两个性质值得知道：

* 它会对**所有已注册扩展**调用，而不只是放下这个方块的那一个。所以坐标不属于你时，你的实现必须以很低的 代价直接返回。
* 异常会被捕获并吞掉（`catch (Throwable ignored)`），所以这里的 bug 是静默失败，不会中断撤销。想看到错误 就自己打日志。

```java
@Override
public void cleanupBeforeUndo(Location location) {
    if (location == null) return;
    Iterator<Location> it = placed.iterator();
    while (it.hasNext()) {
        Location loc = it.next();
        if (loc.getBlockX() == location.getBlockX()
                && loc.getBlockY() == location.getBlockY()
                && loc.getBlockZ() == location.getBlockZ()
                && loc.getWorld() != null && loc.getWorld().equals(location.getWorld())) {
            it.remove();
            return;
        }
    }
}
```

## 线程

四个回调都是从命令处理器同步调用的，跑在执行 `/fd debugtools` 的那个线程上。在 Paper 上就是主线程；在 Folia 上是命令分派所在的线程，它拥有的是**命令发起者**的区域——不见得是你即将在几百格外放置方块的那个区域。

FarmersDelight 内置的激活路径是显式做了 Folia 适配的：它先收集待激活项，再逐个重新调度到各自坐标所属的 区域上。扩展不会自动得到这种待遇。如果你的扩展在 Folia 上要碰发起者区域之外的方块，就自己用 `FarmersDelightApi.get().runAtLocation(loc, ...)` 切过去——同时注意 `place` 必须在延后的工作跑完之前就返回 计数，而 `undo.capture(loc)` 仍应在命令线程上提前完成，这样快照才会并入当前的撤销批次。

回调之间共享的状态要用并发结构：`place` 可能与一次耗时的 `activate` 同时在动同一个集合。FDAddonTemplate 用 `ConcurrentHashMap.newKeySet()`，并遍历 `new ArrayList<>(placed)` 的防御性副本，正是出于这个原因。

## 相关页面

* [版本兼容工具](compat-utilities.md)
* [快速上手](getting-started.md)
* [FarmersDelightApi 入口](farmersdelight-api.md)
