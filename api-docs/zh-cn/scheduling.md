---
icon: square-kanban
---

[English](../en/scheduling.md)

# 调度与 ApiTask

FarmersDelight 同时支持 Paper 与 Folia。它内部的调度适配层会判断当前服务端，并相应选择全局、区域或实体 调度器。`FarmersDelightApi` 上有三个方法把这套适配暴露给附属，而 `com.huidu.farmersdelight.api.scheduler.ApiTask` 是重复任务返回给你的句柄。

用这些封装，附属才能在 `plugin.yml` 里写 `folia-supported: true` 而不必自己写一套 Folia 反射。对应的 feature id 是 `scheduler`。

## 这几个调用不是「软空操作」——必须用 `isAvailable()` 把门

每个调度入口都是先取 `FarmersDelightPlugin.getInstance()`、只对**这个实例**判空，然后直接调 `plugin.scheduler()`。实例是在 FarmersDelight 的 `onLoad` 里赋上的，而 `SchedulerAdapter` 要到 `onEnable` 中段才构建，并在 `onDisable` 里被置回 `null`。只要那个字段是 `null`，`scheduler()` 就抛 `IllegalStateException("Scheduler is not available")`。

也就是说，在「插件实例已存在、但适配层还不存在」的窗口里，`runAtLocation`、`runLaterAtLocation`、 `runRepeating`、`isFolia()` 和 `awardCraftingExperience` 会**直接抛异常——既不会空操作，也不会返回兜底值**。 两个具体窗口：

* FarmersDelight 的 `onLoad` 之后、`onEnable` 里构建适配层之前——其中也包括 FarmersDelight 自己的 `/reload` 守卫在适配层构建前就中止 `onEnable` 的情况；
* FarmersDelight 的 `onDisable` 跑完之后，直到本次 JVM 会话结束。

每个方法内部那道判空只覆盖「FarmersDelight 根本没被加载」这一种情况。对附属来说，真正能堵住上面两个窗口的是 `isAvailable()`，因为它查的是 enabled 标志而不只是实例：`enabled` 是 `onDisable` 的第一条语句就置 `false` 的， 排在字段拆除之前；而在 `onLoad` 到 `onEnable` 之间的整段空档里它本来就是 `false`。下文各方法条目里写的 「FarmersDelight 未加载时是空操作」请按字面理解&#x4E3A;_&#x672A;加载_，而不&#x662F;_&#x672A;启用_。

关于 `isAvailable()` 有一点得说清楚：`enabled` 是在 FarmersDelight `onEnable` 靠前的位置置 `true` 的，比适配层 的构建还早几条语句，所以在 FarmersDelight 自己启动的过程中，存在一小段 `isAvailable()` 已经为 `true`、而 `scheduler()` 仍会抛异常的区间。声明了 `depend: [FarmersDelight]` 的附属在自己的 `onEnable` 里观察不到它—— Bukkit 会先把 FarmersDelight 的 `onEnable` 跑完。只有在 FarmersDelight enable **过程中**就会执行的代码里才够 得着，比如更早注册的某个监听器。真处在这种位置的话，把活挪到 `FarmersDelightWarmupEvent` 或 `FarmersDelightReloadEvent` 里做，别放在自己的 enable 里。

## `ApiTask`

```java
package com.huidu.farmersdelight.api.scheduler;

@ApiStatus.NonExtendable
public interface ApiTask {

    ApiTask NOOP = new ApiTask() {
        @Override
        public void cancel() {
        }

        @Override
        public boolean isCancelled() {
            return true;
        }
    };

    void cancel();

    boolean isCancelled();
}
```

整个类型就这些。它避免内部调度实现泄漏到 API 边界之外，并为附属提供可在插件生命周期内持有的稳定包装。

接口上的 `@ApiStatus.NonExtendable` 意思是：可以用，但不要实现。实例由 FarmersDelight 提供。

`ApiTask.NOOP` 是任务未能被调度时返回的空对象。它的 `isCancelled()` 返回 `true`，`cancel()` 什么也不做， 所以惯用的 `if (task != null) task.cancel()` 收尾写法在任何情况下都成立，也不会 NPE。

## `void runAtLocation(Location location, Runnable task)`

在拥有 `location` 的线程上执行 `task`。

* **Folia**：投递到 `location` 所在区块的区域调度器。
* **Paper**：如果调用线程本身就是主线程，任务会**内联同步执行，在 `runAtLocation` 返回之前就跑完**；否则 排进主线程等下一 tick。
* 若 `location` 为 `null`**或它的世界为 `null`**，任务改走全局路径（Folia 的全局区域调度器，或 Paper 上如上 所述的行为）。两处都判：`runAt(Location, ...)` 在坐标为 null 时转全局，它委托到的那个接收 world 的重载在 world 为 null 时再转一次全局。

Paper 上的内联这一点很重要：如果你的代码假定回调是延后的，那么在 Paper 上它可能在下一行语句之前就已经跑 完了。

FarmersDelight 未加载或 `task` 为 `null` 时该方法是空操作；什么情况下它反而会抛异常，见上面的可用性一节。它 没有返回值，也没有取消句柄。

```java
/** Folia-safe: touch the block from the region that owns it. */
public void doAtBlock(Location loc, Runnable task) {
    FarmersDelightApi.get().runAtLocation(loc, task);
}
```

## `void runLaterAtLocation(Location location, Runnable task, long delayTicks)`

路由规则与 `runAtLocation` 相同，只是延后 `delayTicks`。但拿不到可用坐标时的兜底路径在 Folia 上**不是**主线程 ——`SchedulerAdapter.runLaterAt` 会落到 `runLater`，而后者自身又按平台分叉：

* **Folia，且 `location` 可用**：在拥有 `location` 的区域上排延时任务。`delayTicks <= 0` 会退化成该区域上的 普通 `run`（下一次区域 tick 执行，而不是内联执行）；否则按给定延迟走 `runDelayed`。
* **Folia，且 `location` 为 `null` 或其世界为 `null`**：任务进的是**全局区域调度器**，不是什么主线程。 `delayTicks <= 0` 会退化成全局 `run`——也就是下一次全局 tick 就跑，而不是等你要的延迟；否则按给定延迟走 `runDelayed`。Folia 上根本不存在可供兜底的主线程。
* **Paper**（`location` 是什么都一样，包括 `null`）：主线程延时任务，延迟值下限夹到 0 tick。**这个 0 tick 下限只属于 Paper 这条路径。**

实际会踩的坑是：在 Folia 上传一个 `null`／没有世界的坐标，同时 `delayTicks <= 0`，还指望延迟被兑现、并且跑在 拥有坐标的线程上。你拿到的是下一 tick 的全局区域任务，在里面碰方块或实体会抛异常。

FarmersDelight 未加载或 `task` 为 `null` 时是空操作；什么情况下它反而会抛异常，见上面的可用性一节。没有返回值——也就是说这个 api 无法取消一个延时任务。 需要取消能力的话，用 `runRepeating` 并在首次执行后取消，或者让任务自己检查一个标志位。

## `ApiTask runRepeating(Runnable task, long delayTicks, long periodTicks)`

排一个重复任务并返回句柄。

* **Folia**：任务跑在**全局区域调度器**上——**不**绑定任何坐标的区域。
* **Paper**：主线程重复任务。
* `delayTicks` 与 `periodTicks` 各自的下限**在两个平台上都**被夹到 1 tick——Paper 是在 `runTaskTimer` 外面套 `Math.max(1L, ...)`，Folia 是在全局调度器的 `runAtFixedRate` 外面套同样的 `Math.max(1L, ...)`。周期传 0， 两边都等于周期 1。
* FarmersDelight 未加载或 `task` 为 `null` 时返回 `ApiTask.NOOP`（永不返回 `null`）；什么情况下它会抛异常而 不是返回 `NOOP`，见上面的可用性一节。

线程模型在这里才真正暴露出来。**Folia 的全局区域任务不能直接碰方块、方块实体或实体**——那些属于区域线程。 正确的写法是重复 tick 里按坐标跳线程：

```java
heartbeat = FarmersDelightApi.get().runRepeating(() -> {
    for (Location loc : trackedStations()) {
        FarmersDelightApi.get().runAtLocation(loc, () -> tickStation(loc));
    }
}, 20L, 20L);
```

在 Paper 上这会退化成一个普通的主线程循环，因为那里 `runAtLocation` 是内联执行的。

`runRepeating` 要在 `onEnable` 里、过了 `isAvailable()` 这道门之后再调用。FarmersDelight 的调度器访问器在 适配层尚未建立时会抛 `IllegalStateException("Scheduler is not available")`，这也是不要在 FarmersDelight enable 之前碰 api 的又一个理由。

### 保存并取消句柄

把 `ApiTask` 存进字段，在 `onDisable` 里取消。FDAddonTemplate 存了两个：

```java
private ApiTask heartbeat;
private ApiTask buffBossbarTask;

@Override
public void onEnable() {
    // ...
    heartbeat = FarmersDelightApi.get().runRepeating(this::onHeartbeat, 20L, 20L * 60L);
    buffBossbarTask = FarmersDelightApi.get().runRepeating(buffBossbar::tick, 20L, 10L);
}

@Override
public void onDisable() {
    if (heartbeat != null) {
        heartbeat.cancel();
    }
    if (buffBossbarTask != null) {
        buffBossbarTask.cancel();
    }
}
```

`isCancelled()` 反映底层任务的状态，因此插件关闭导致任务被取消后它同样会变成 `true`。

## 一个真实的封装

BrewinAndChewin 的重复任务和区域绑定任务没有在每个调用点直接用 api，而是走一个小工具类——如果你希望将来能在 一个地方换实现，这是个值得抄的模式。三个 public static 方法，外加一个私有构造：

```java
/** Folia-friendly scheduling, routed through FarmersDelight's addon API (works on Paper and Folia). */
public final class Schedulers {

    private Schedulers() {
    }

    public static ApiTask runRepeating(Plugin plugin, long periodTicks, Runnable task) {
        return FarmersDelightApi.get().runRepeating(task, 1L, periodTicks);
    }

    /** Runs on the region owning {@code location} (immediately on Paper's main thread). */
    public static void runAt(Plugin plugin, Location location, Runnable task) {
        FarmersDelightApi.get().runAtLocation(location, task);
    }

    public static void cancel(ApiTask task) {
        if (task != null) {
            task.cancel();
        }
    }
}
```

注意它**不是**该附属唯一的调度出口，别当成「一切都走这里」来抄。它覆盖的就是它包住的那两个 api 调用。 **延时**的坐标绑定任务是直接调 `FarmersDelightApi.get().runLaterAtLocation(...)` 的——`KegManager`、 `KegListener`、`BrewinAdvancementListener` 各有一处——因为这个工具类里根本没有延时方法。按实体调度的部分直接 用 Folia 自己的 `entity.getScheduler().run(...)`，因为 api 压根没有暴露实体调度封装（见下面「没有暴露的部分」）。

抄这个形状时还有一个细节：两个封装方法上的 `plugin` 参数收下之后并没有被用到。api 自己会去取 FarmersDelight 的插件实例，任务的归属方是 FarmersDelight 而不是你的插件——这也正是必须在你自己的 `onDisable` 里取消它的原因。

## 没有暴露的部分

内部调度适配层还有更多能力：带 retired 回调的实体绑定调度、异步执行、区域绑定的重复任务，以及 `isOwnedByCurrentRegion(Location)` 判定。**当前修订下这些都不在 api 接口面上。** 需要异步写文件就用 Bukkit 自己的异步调度器；Folia 上需要实体区域调度就直接用 Folia 的 `Entity#getScheduler()`。

## api 调用的线程要求，总览

* 任何读写方块、方块实体或容器的操作，在 Folia 上都必须发生在拥有它的区域线程上。用 `runAtLocation` 包起来。
* `awardCraftingExperience(...)` 已经替你处理好了：它内部会先切到传入坐标所属的区域，再掉经验球并触发事件。
* 数据包物品展示相关调用（`createItemDisplay`、`updateItemDisplay`、`removeItemDisplay`）在 Folia 上要求从 拥有该坐标的区域调用。
* 注册类调用——`registerRecipeType`、`registerCookingPotRecipe`、`registerAddonBlockNamespace`、 `DebugToolRegistry.register`、`hasFeature`、`apiVersion`——只碰并发集合，没有区域线程要求。

## 相关页面

* [FarmersDelightApi 入口](farmersdelight-api.md)
* [自定义 buff 与 Bossbar](buffs.md)
* [快速上手](getting-started.md)
