---
icon: arrow-progress
---

# 事件

`com.huidu.farmersdelight.api.event` 下的所有类都是普通的 Bukkit `org.bukkit.event.Event`。用常规 `@EventHandler` 方法订阅，在自己的 `onEnable` 里注册监听器即可：

```java
getServer().getPluginManager().registerEvents(new MyFarmersDelightListener(), this);
```

> **关于本页代码片段的约定。** 这些片段展示的是 api 调用**在上下文中的样子**，不是可直接编译的完整文件。方法 签名、事件类型名、getter 名和返回类型都是精确的，可以照着用。而一眼就属于你自己的标识符—— `MyFarmersDelightListener`、传给 `player.getScheduler().run` 的那个 `myPlugin` 字段、`recordHarvest(...)`、 `plugin.reloadAddon()` 之类——是占位符，代表你自己附属里的字段和方法；它们在片段里没有任何声明，背后也没有 对应的 api 成员。除非某个片段的目的就是说明类型来自哪个包，否则 import 一律省略。请把它们换成你自己的引用，并补上周边样板代码。

**这 11 个事件全部不可取消。** 没有任何一个实现 `org.bukkit.event.Cancellable`，因此既没有 `setCancelled` 可 调，触发点也不存在"尊重取消"这回事。它们是通知钩子与累加钩子。想影响 FarmersDelight 的行为，只有三条路： `FarmersDelightCleanupEvent` / `FarmersDelightMigrateEvent` 上的计数累加器，以及 `FarmersDelightCollectLiveDisplaysEvent` 上的保护集合。

其中 10 个标注了 `@ApiStatus.NonExtendable`：不要继承，也不要为了伪造 FarmersDelight 的行为而自行构造。但为 **你自己的**工作站上报产出是另一回事，这是明确支持的用法——`FarmersDelightProduceEvent` 与 `ProfessionCookingExperienceEvent` 就是这么设计的，Brewin' And Chewin' 的酒桶正是靠它上报产出。 `FarmersDelightRecipeDiscoveryEvent` 没有任何 `@ApiStatus` 注解，但请同样当作封闭类型对待，插件内部没有任何 地方预期会收到外部子类。

## 总览

| 事件                                       | 触发时机                                        | 可取消         |
| ---------------------------------------- | ------------------------------------------- | ----------- |
| `FarmersDelightBuffChangeEvent`          | 玩家身上某个已注册自定义 buff 的等级真正发生变化（获得 / 失去 / 等级移动） | 否           |
| `FarmersDelightCleanupEvent`             | `/fd cleanup`，在 FarmersDelight 清完自己的孤儿实体之后  | 否（累加计数）     |
| `FarmersDelightCollectLiveDisplaysEvent` | `/fd cleanup`，在孤儿显示实体清扫**之前**               | 否（累加受保护 id） |
| `FarmersDelightCookStartEvent`           | 炖锅从"无匹配配方"变为"有匹配配方"                         | 否           |
| `FarmersDelightHarvestEvent`             | 蘑菇簇被剪刀 / 小刀采收，或成熟稻谷被采收                      | 否           |
| `FarmersDelightMigrateEvent`             | **从不触发——代码库中不存在触发点**                        | 否（累加计数）     |
| `FarmersDelightProduceEvent`             | 工作站把产出物交给玩家                                 | 否           |
| `FarmersDelightRecipeDiscoveryEvent`     | 玩家的配方发现状态真正变化（解锁 / 重新锁定）                    | 否           |
| `FarmersDelightReloadEvent`              | FarmersDelight 一次重载完成                       | 否           |
| `FarmersDelightWarmupEvent`              | CraftEngine 物品就绪且 FarmersDelight 已预热完自己的缓存  | 否           |
| `ProfessionCookingExperienceEvent`       | 工作站给玩家结算烹饪经验                                | 否           |

***

## FarmersDelightBuffChangeEvent

`com.huidu.farmersdelight.api.event.FarmersDelightBuffChangeEvent` — `@ApiStatus.NonExtendable`

### 触发时机

唯一触发点是 `CustomBuffRegistry.syncState(Player, CustomBuff)`。它拿当前等级与"上次已知等级"缓存做差，只有 数值真正移动了才触发——获得（0 到 N）、失去（N 到 0）、或在两个非零等级之间变化。仅仅刷新持续时间而等级不 变，**什么都不触发**。正是这个差分让你可以在每次状态变更后（哪怕每 tick）都调用 `syncState`，而不会把事件变 成刷屏洪水。

注册表自己的变更路径（`apply`、`clearAll`、`clearOne`、`restoreAll`，以及管理员发放、牛奶桶与牛奶瓶清除）都会 自行同步。如果你在注册表之外维护自己的 buff 状态，变更后必须调用 `CustomBuffRegistry.syncState(player, buffId)`，否则这次变化永远不会被上报。

### 携带的数据

`getPlayerId()` `UUID`、`getPlayerName()` `String`（可能为 null）、`getBuffId()` `String`、 `getPreviousLevel()` `int`、`getNewLevel()` `int`、`getRemainingSeconds()` `int`，外加两个便捷判定 `isGained()` 与 `isLost()`。等级从 1 起算：等级 1 是基准强度，等价于药水 amplifier 0。buff 失去、或本身不报告 持续时间时，`getRemainingSeconds()` 为 0。

### 取消与线程

不可取消——事件构造出来时 buff 早已变更完毕。

线程这一段值得细看，因为触发点做的事比类注释描述的更多。`CustomBuffRegistry.fireChange` 的逻辑是：

* 若 `Bukkit.isPrimaryThread()` 为真，**在调用线程上原地派发**。
* 否则交给 FarmersDelight 的调度器（`plugin.scheduler().run(...)`），在 Folia 上落到**全局 region**，而不是 玩家所在的 region。这么做是因为 Bukkit 在非 tick 线程上同步派发事件会抛异常，而此时差分缓存已经消费掉了这 次转换，丢掉事件就是永久丢失。
* 插件已关闭时没有调度器可交，只能兜底原地派发。

由此有两个结论。其一，不要假设自己身处该玩家的 region 线程；请从 `getPlayerId()` 解析玩家，凡是与 region 相 关的操作一律走 `player.getScheduler()`。其二，**监听器抛出的异常会被吞掉**——`callChange` 捕获 `RuntimeException`，以免一个坏掉的监听器回滚一次已经发生的 buff 变更。也就是说你的处理器会静默失败，请自行 打日志。

### 示例

```java
import com.huidu.farmersdelight.api.event.FarmersDelightBuffChangeEvent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

public final class BuffWatcher implements Listener {

    @EventHandler
    public void onBuffChange(FarmersDelightBuffChangeEvent event) {
        if (!"brewinandchewin:tipsy".equals(event.getBuffId())) {
            return;
        }
        Player player = Bukkit.getPlayer(event.getPlayerId());
        if (player == null) {
            return; // 玩家可能离线，也可能当前身处外部线程
        }
        if (event.isGained()) {
            player.getScheduler().run(myPlugin, task ->
                    player.sendMessage("You feel tipsy."), null);
        } else if (event.isLost()) {
            player.getScheduler().run(myPlugin, task ->
                    player.sendMessage("You have sobered up."), null);
        }
    }
}
```

FarmersDelight、Brewin' And Chewin' 与 ExpandedDelight 目前都没有监听该事件，它纯粹是留给第三方的。

***

## FarmersDelightCleanupEvent

`com.huidu.farmersdelight.api.event.FarmersDelightCleanupEvent` — `@ApiStatus.NonExtendable`

### 触发时机

唯一触发点是 `/fd cleanup` 的处理方法 `FarmersDelightCommand.executeCleanup`，且位于**最后一步**： FarmersDelight 先清完自己的孤儿代理物品显示实体与失效自动托盘，才轮到你的监听器运行，此时主插件那边的数字 已经定死。

### 携带的数据

只有一个累加器。`addRemoved(int count)` 累加到内部的 `AtomicInteger`（`<= 0` 的值被忽略），`getRemoved()` 读 取总和。命令在派发之后立刻读 `getRemoved()`，把结果填进回复消息的 `addon` 与 `count` 占位符。

### 取消与线程

不可取消。事件到达的是命令线程，即执行 `/fd cleanup` 的那条线程；对控制台与玩家命令来说就是主线程 / 全局线 程。

请把它当作一次性的管理信号，绝不是周期性 tick。在 Folia 上允许做尽力而为的分区调度：契约明确允许上报的计数 含义是"已排入清理队列"而非"本方法返回前已清完"，因为命令是同步读取总数的。

### 示例

仿照附属模板中的 `ExampleFarmersDelightEventsListener`：

```java
import com.huidu.farmersdelight.api.event.FarmersDelightCleanupEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

public final class MyCleanupListener implements Listener {

    @EventHandler
    public void onCleanup(FarmersDelightCleanupEvent event) {
        int removed = 0;
        // 在这里清扫你自己的孤儿实体或失效记录，每清一个就自增
        event.addRemoved(removed);
    }
}
```

***

## FarmersDelightCollectLiveDisplaysEvent

`com.huidu.farmersdelight.api.event.FarmersDelightCollectLiveDisplaysEvent` — `@ApiStatus.NonExtendable`

### 触发时机

唯一触发点同样在 `FarmersDelightCommand.executeCleanup`，但位于清扫**之前**而非之后。命令先用 `plugin.collectLiveDisplayIds()` 建好存活 id 集合，触发本事件让附属补充自己的 id，然后才调用 `displayManager.cleanupOrphans(liveIds)`。它是 `FarmersDelightCleanupEvent` 的保护型对偶：那个上报删除量，这 个阻止删除。

两个事件都发生在同一次 `/fd cleanup` 调用中，本事件严格在前。

### 携带的数据

`addLiveId(int entityId)` 与 `addLiveIds(Collection<Integer> entityIds)`。事件直接包装 FarmersDelight 自己的 存活 id 集合，**不是副本**。**只能往里加。** 类里没有提供移除方法，通过任何其他途径清空或改动这个集合，都会 让 FarmersDelight 删掉自己正在使用的显示实体。

任何通过 `FarmersDelightApi.createItemDisplay` 创建了发包物品显示的附属**必须**监听此事件。不监听， `/fd cleanup` 就会把你的显示实体当孤儿清掉。

### 取消与线程

不可取消。命令线程，与 `FarmersDelightCleanupEvent` 相同。

### 示例

以下是 Brewin' And Chewin' 的真实监听器（`CoasterLifecycleListener`），保护杯垫上展示的物品显示实体：

```java
import com.huidu.farmersdelight.api.event.FarmersDelightCollectLiveDisplaysEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

public final class CoasterLifecycleListener implements Listener {

    @EventHandler
    public void onCollectLiveDisplays(FarmersDelightCollectLiveDisplaysEvent event) {
        CoasterManager manager = manager();
        if (manager != null) {
            event.addLiveIds(manager.liveDisplayHandles());
        }
    }
}
```

附属模板在它的显示管理器里做法相同：`event.addLiveIds(List.copyOf(handles.values()))`。

***

## FarmersDelightCookStartEvent

`com.huidu.farmersdelight.api.event.FarmersDelightCookStartEvent` — `@ApiStatus.NonExtendable`

### 触发时机

派发点只有 `CookingPotBlockEntity.firePendingCookStart` 一处，由两个调用方进入：`canCook()` 与 `finishCooking(World, Location)`。两者都在持锁期间记录匹配结果，释放全部锁之后才调用派发方法。一个 pending 槽位（`AtomicReference.getAndSet(null)`）保证即使两条路径在同一 tick 内都跑到，配方也只会被宣告一次。

这是**边沿事件，不是状态事件**，只在"空闲→开始烹饪"的跃迁上触发：

* 一口锅对着同一批料咕嘟一千 tick，也只产生一个事件。
* 从一个有效配方直接换料到另一个有效配方，属于配&#x65B9;_&#x53D8;&#x66F4;_&#x800C;&#x975E;_&#x5F00;始_，不会上报。锅必须先落回空闲（无匹配）， 下一次匹配才算一次开始。
* 完成一批会清掉匹配，因此料够第二批的锅会为那一批再次触发。

插件被禁用时（`FarmersDelightPlugin.isEnabled0()` 为假）整个派发直接跳过。

一批料另一端的对偶事件是 `FarmersDelightProduceEvent`，在玩家取走成品时触发。

### 携带的数据

`getLocation()` `Location`（方块对齐的角点，每次调用返回新克隆；仅当锅还没有世界时为 null）、`getRecipeId()` `String`、`getResult()` `ItemStack`（克隆，可能为 null）、`getCookTimeTicks()` `int`。

`getCookTimeTicks()` 是配方从零进度开始所需的 tick 数。锅只在被加热时推进进度，所以实际耗时至少是这个数，通 常更多。

### 取消与线程

不可取消——这次跃迁可被观察到时，锅早已完成匹配。

事件在拥有该锅的 region 线程上触发，并且——这点很关键——**在锅的方块实体锁之外**。这是刻意为之：监听器可以放 心读取甚至修改这口锅而不会死锁。请走 `com.huidu.farmersdelight.api.block.FarmersDelightBlocks.cookingPot`，不要伸手进内部实现。

### 示例

```java
import com.huidu.farmersdelight.api.event.FarmersDelightCookStartEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

public final class CookStartLogger implements Listener {

    @EventHandler
    public void onCookStart(FarmersDelightCookStartEvent event) {
        if (event.getLocation() == null) {
            return;
        }
        getLogger().fine("Pot at " + event.getLocation() + " started "
                + event.getRecipeId() + " (" + event.getCookTimeTicks() + " ticks)");
    }
}
```

***

## FarmersDelightHarvestEvent

`com.huidu.farmersdelight.api.event.FarmersDelightHarvestEvent` — `@ApiStatus.NonExtendable`

### 触发时机

两个触发点，且负载差异明显：

1. **`MushroomColonyBehavior.useOnBlock`** —— 用剪刀或小刀右键蘑菇簇。在保护检查通过之后、方块状态已递减之 后、掉落物生成**之前**触发，因此监听器看到的是唯一一次采收和最终掉落数量。`getDrops()` 含一组物品堆。
2. **`TallCropBlockBehavior.useOnBlock`** —— 采收成熟稻谷（上半部分、手持有效采收工具、方块配置了 `resetOnHarvest`）。同样在保护检查之后、任何掉落物生成之前触发。`getDrops()` 为**空**，因为战利品来自 CraftEngine 的 loot table 或配置的 break-loot 函数链，从不以列表形式经过 FarmersDelight。

两个触发点都只在 `ProtectionCompat` 放行后才执行：蘑菇处同时检查 `canUse` 与 `canBuild`（`Feature.MUSHROOM_COLONY`）， 稻谷处检查 `canBuild`（`Feature.RICE`）。

### 哪些采&#x6536;_&#x4E0D;_&#x5728;覆盖范围内

番茄不会被上报。番茄植株完全由 CraftEngine YAML 实现（`on: right_click` 函数链），没有 Java 处理器可供本事件 挂载。要观察番茄采收，请监听 CraftEngine 自己的 `CustomBlockInteractEvent` 并按方块 id 过滤—— `farmersdelight:tomatoes`、`farmersdelight:budding_tomatoes`、`farmersdelight:tomato_crop_on_rope`—— FarmersDelight 自己的 WorldGuard 集成对这个方块用的就是这套做法。

### 携带的数据

`getPlayerId()` `UUID`、`getPlayerName()` `String`（可能为 null）、`getLocation()` `Location`（克隆）、 `getBlockId()` `String`（CraftEngine 方块 id，例如 `farmersdelight:brown_mushroom_colony`）、`getTool()` `ItemStack`（克隆；空手采收时为 null）、`getDrops()` `List<ItemStack>`。

`getDrops()` 是**尽力而为且不可修改**的。空列表意味着"无法枚举"，而不是"没有掉落"。列表与其中每个物品堆都是 副本，改它们不会影响真正掉什么。

### 取消与线程

不可取消，而且这是刻意的设计决定，不是疏漏。否决一次采收属于保护层的职责：FarmersDelight 在每次采收**之前** 都会询问它的 `ProtectionCompat` 门面（WorldGuard flag 加上 AntiGriefLib 覆盖的 24 多个领地插件），本事件只在 该检查通过后才触发。想拦截采收的领地插件应当把自己接入那个门面，让交互被干净地拒绝，而不是在这里监听——在这 里状态已经改了一半。

两个触发点都运行在交互玩家的 region 线程上，处于 CraftEngine 方块行为调用内部，且两个 behavior 都不持有方块 实体锁。

### 示例

```java
import com.huidu.farmersdelight.api.event.FarmersDelightHarvestEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;

public final class HarvestStats implements Listener {

    @EventHandler
    public void onHarvest(FarmersDelightHarvestEvent event) {
        if (!event.getBlockId().startsWith("farmersdelight:")) {
            return;
        }
        int counted = 0;
        for (ItemStack drop : event.getDrops()) {
            counted += drop.getAmount(); // 稻谷处为空：那里的掉落无法枚举
        }
        recordHarvest(event.getPlayerId(), event.getBlockId(), counted);
    }
}
```

***

## FarmersDelightMigrateEvent

`com.huidu.farmersdelight.api.event.FarmersDelightMigrateEvent` — `@ApiStatus.NonExtendable`

### 现状：没有触发点

**全仓搜索找不到任何构造或派发该事件的代码。** 类本身是公开 API、能正常编译，附属模板里也带了示例监听器，但 当前没有任何 FarmersDelight 命令路径触发它——类注释里提到的 `/fd rug-migrate` 动作在命令处理器中并不存在。你 今天注册的监听器永远不会被调用。

保留它是为了给将来的迁移留一个稳定钩子。现在注册处理器无害也无成本，并且用 `migrationKey()` 做闸门可以保证 将来真有迁移上线时，你不会对不属于自己的迁移做出反应。但不要让任何东西**依赖**它触发。

### 它将会携带的数据

`migrationKey()` `String` —— 注意这个访问器**没有** `get` 前缀 —— 标识跑的是哪次迁移（类注释给的示例值是 `"rug"`）。另外是与 `FarmersDelightCleanupEvent` 相同的 `addRemoved(int)` / `getRemoved()` 累加器对，设计意 图是汇总进迁移命令的回复。

### 取消与线程

不可取消。设计上应到达命令线程，作为一次性管理信号，Folia 上有与 cleanup 事件相同的尽力而为允许。实际情况 UNKNOWN，因为没有任何代码触发它。

### 示例

摘自附属模板：

```java
import com.huidu.farmersdelight.api.event.FarmersDelightMigrateEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

public final class MyMigrateListener implements Listener {

    @EventHandler
    public void onMigrate(FarmersDelightMigrateEvent event) {
        if (!"example".equals(event.migrationKey())) {
            return; // 不是本附属负责的迁移
        }
        int migrated = 0;
        // 在这里转换你自己的旧数据，每转一条就自增
        event.addRemoved(migrated);
    }
}
```

***

## FarmersDelightProduceEvent

`com.huidu.farmersdelight.api.event.FarmersDelightProduceEvent` — `@ApiStatus.NonExtendable`

### 触发时机

这是唯一一个既预期你监听、也预期你**主动触发**的事件。目前存在三个触发点：

1. **`CookingPotGui`** —— 玩家点击输出槽取走成品。source 为 `"cooking_pot"`，location 为锅。在物品已交付、经 验奖励已结算之后触发。
2. **`KegListener`（Brewin' And Chewin'）** —— 玩家从酒桶 GUI 取走存放的产出。source 为 `"keg"`，location 为 酒桶方块中心。
3. **`KegManager`（Brewin' And Chewin'）** —— 玩家手持容器右键从酒桶倒酒。source 为 `"keg"`。

第 3 个触发点有一条值得警惕的线程注意事项：它是**持有 per-keg 监视器时派发的**（`synchronized (lockFor(key))`）。 也就是说你的处理器运行在一个外部插件的锁之下。FarmersDelight 自己的 `RecipeDiscoveryListener` 的应对方式是： 在触发线程上只做廉价的闸门判断，把真正的工作推给 `player.getScheduler()`。请照抄这个范式：**不要在 `FarmersDelightProduceEvent` 处理器里做重活、阻塞、或获取你自己的锁**，否则可能与一个你无法控制的插件形成锁 序倒置。

另外注意哪些不在覆盖范围内：切菜板的产出是以掉落物实体形式生成的，从不产生本事件。

### 携带的数据

`getPlayerId()` `UUID`（**可能为 null**，表示自动化提取，例如漏斗或附属逻辑）、`getSource()` `String`、 `getResult()` `ItemStack`（克隆，可能为 null）、`getLocation()` `Location`（克隆，可能为 null）。

供附属自行触发的构造器：

```java
public FarmersDelightProduceEvent(UUID playerId, String source, ItemStack result, Location location)
```

### 取消与线程

不可取消——物品已经产出，在现有的每个触发点上甚至已经交付。

线程取决于触发点。三处都在执行交互的玩家所在 region 线程上触发，只有倒酒那处在触发时持有锁。

### 示例：监听

Brewin' And Chewin' 的成就监听器（节选）。注意 `MONITOR` 优先级与各处 null 判断：

```java
import com.huidu.farmersdelight.api.event.FarmersDelightProduceEvent;
import com.huidu.farmersdelight.api.item.FarmersDelightItems;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;

import java.util.UUID;

public final class ProduceAdvancements implements Listener {

    @EventHandler(priority = EventPriority.MONITOR)
    public void onProduce(FarmersDelightProduceEvent event) {
        UUID playerId = event.getPlayerId();
        if (playerId == null) {
            return; // 自动化提取：没有可授予的对象
        }
        Player player = Bukkit.getPlayer(playerId);
        if (player == null) {
            return;
        }
        ItemStack result = event.getResult();
        if (result == null || result.getType().isAir()) {
            return;
        }
        String id = FarmersDelightItems.customIdOf(result);
        if (id == null) {
            return;
        }
        award(player, id);
    }
}
```

### 示例：触发自己的产出

像 Brewin' And Chewin' 的酒桶那样上报自己工作站的产出，好让 FarmersDelight 的配方发现与其他附属的统计追踪都 能看到：

```java
Bukkit.getPluginManager().callEvent(new FarmersDelightProduceEvent(
        player.getUniqueId(), "keg", taken,
        kegLoc == null ? null : kegLoc.clone().add(0.5, 0.5, 0.5)));
```

基于上面说明的原因，尽量在释放自己的锁之后再派发。

***

## FarmersDelightRecipeDiscoveryEvent

`com.huidu.farmersdelight.api.event.FarmersDelightRecipeDiscoveryEvent` —— 没有 `@ApiStatus` 注解，但请当作封 闭类型对待。

### 触发时机

派发点只有 `RecipeDiscoveryManager.fireChanged` 一处，由管理器的 `unlock` 与 `lock` 路径进入。只在**真实跃迁** 上触发：解锁一个已解锁的、或锁定一个已锁定的，都不触发任何事件，因此监听器对每次状态变化恰好看到一个事件。 `lockAllOfType` 这类批量操作，按真正发生移动的配方逐条触发，而不是一次调用一个事件。

### 携带的数据

`getPlayerId()` `UUID`、`getTypeId()` `String`（例如 `farmersdelight:cooking_pot`，或附属自己的 `RecipeType` id）、`getRecipeId()` `String`、`getAction()` `Action`、`getSource()` `Source`。

两个内嵌枚举：

* `Action` —— `UNLOCK`、`LOCK`。
* `Source` —— `OBTAIN`（获得触发器：玩家拾取了、或被工作站交付了某个产物或精确原料）、`API`（某插件调用了 `FarmersDelightRecipeDiscovery` API）、`COMMAND`（管理员执行了配方发现管理命令）。

所有字段都是不可变类型，因此访问器直接返回字段本身而非防御性副本；不存在监听器能顺藤摸瓜改到的可变状态。

**事件指名的玩家可能不在线。** 命令路径可以修改一个不在服务器上的玩家的存档状态，因此务必对 `Bukkit.getPlayer(event.getPlayerId())` 做 null 判断。

### 取消与线程

不可取消——监听器运行前状态已提交。否决属于上游，应当发生在请求解锁之前。

派发时不持有配方发现管理器的任何锁。与 buff 变更事件一样，触发点会分支：主线程上原地派发，否则把构造好的事 件交给 `plugin.scheduler().run(...)`，落到全局 region。这个分支存在的原因是：并发 map 的状态变更本身任何线程 都可以做，而 `callEvent` 会拒绝来自非 tick 线程的同步派发——原地派发会让一次异步解锁在改完 map 之后半提交地 抛出异常。

API 调用方仍必须从服务器线程发起解锁请求。监听器应当把该事件视为 region 局部的：只对指名的玩家动手，不要碰 无关的世界状态。

### 示例

```java
import com.huidu.farmersdelight.api.event.FarmersDelightRecipeDiscoveryEvent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

public final class DiscoveryToast implements Listener {

    @EventHandler
    public void onDiscovery(FarmersDelightRecipeDiscoveryEvent event) {
        if (event.getAction() != FarmersDelightRecipeDiscoveryEvent.Action.UNLOCK) {
            return;
        }
        if (event.getSource() == FarmersDelightRecipeDiscoveryEvent.Source.COMMAND) {
            return; // 管理员直接发放的，不必庆祝
        }
        Player player = Bukkit.getPlayer(event.getPlayerId());
        if (player == null) {
            return; // 命令路径可以改动离线玩家的状态
        }
        player.getScheduler().run(myPlugin, task -> player.sendMessage(
                "Recipe unlocked: " + event.getRecipeId()), null);
    }
}
```

***

## FarmersDelightReloadEvent

`com.huidu.farmersdelight.api.event.FarmersDelightReloadEvent` — `@ApiStatus.NonExtendable`

### 触发时机

两个触发点，且 reason 字符串不同：

1. **`FarmersDelightPlugin.reloadAll()`** —— 在配置、配方、语言文件全部重读完成后触发，reason 为 `"reloadAll"`。
2. **`FarmersDelightCommand.executeReload`** —— 为 `all` 之外的每个 `/fd reload <target>` 触发，reason 是规范 化后的 target：`"config"`、`"gui"`、`"lang"`、`"language"`、`"languages"`、`"recipes"`、`"recipe"`、 `"advancements"`、`"advancement"`。这里之所以跳过 `all`，正是因为 `reloadAll()` 已经触发过了。

所以：局部重载给你一个窄的 reason，全量重载给你 `"reloadAll"`，永远不会是 `"all"`。

### 携带的数据

`getReason()` `String`，文档标注可能为 null。现有两个触发点传的都是非 null 值。

### 取消与线程

不可取消。到达的是执行重载的那条线程：对 `/fd reload` 而言就是命令线程，也就是主线程 / 全局线程。

正是这个钩子让附属可以**完全不做自己的命令**——Brewin' And Chewin' 与 ExpandedDelight 都完全依赖它。注意：你 注册的配方在重载后会保留，但依赖 CraftEngine 物品的配方还应当在 CraftEngine 自己的 `CraftEngineReloadEvent` 上重新注册，因为 CE 物品只有在 CE 加载之后才能解析。

### 示例

Brewin' And Chewin' 的真实监听器全文：

```java
import com.huidu.farmersdelight.api.event.FarmersDelightReloadEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

public final class BrewinReloadListener implements Listener {

    @EventHandler
    public void onReload(FarmersDelightReloadEvent event) {
        BrewinChewinPlugin plugin = BrewinChewinPlugin.getInstance();
        if (plugin != null) {
            plugin.reloadAddon();
        }
    }
}
```

如果你需要读到另一个处理器重载后的值，请注册为 `MONITOR`——Brewin' And Chewin' 的成就监听器就是在 `MONITOR` 上重建冷源集合，正是为了排在上面那个 `NORMAL` 优先级的重载处理器之后。

***

## FarmersDelightWarmupEvent

`com.huidu.farmersdelight.api.event.FarmersDelightWarmupEvent` — `@ApiStatus.NonExtendable`

### 触发时机

唯一触发点在 `FarmersDelightPlugin.warmUp(String)` 的末尾，而该方法只在 CraftEngine 物品确实加载完成后才运行 （`warmUpWhenReady` 以 `areCraftEngineItemsReady()` 做闸门）。进入它的路径有两条：

* 插件启用，reason `"enable"` —— 当 FarmersDelight 在 CraftEngine **之后**启用时走这条。
* CraftEngine 重载处理器，reason `"reload"` —— 每次 CE 重载后走这条；当 CraftEngine 在 FarmersDelight **之后** 加载时，启动阶段也走这条。就绪闸门让两条路径互斥，因此每个就绪时点你只会收到一次预热，不会收到两次。

它在 FarmersDelight 预热完自己的物品、GUI 与 behavior 缓存之后触发，所以相对于主插件的顺序是确定的。

### 携带的数据

`getReason()` `String` —— `"enable"` 或 `"reload"`，文档标注可能为 null。

### 取消与线程

不可取消。

**处理器必须是纯计算。** 它们运行在全局 / 主线程上，不得触碰世界、实体、region 或真实方块状态。只能构建物品 堆、预热自己的缓存，别的什么都不要做。这是类契约中的硬约束，不是建议。

### 示例

Brewin' And Chewin' 的真实监听器全文——预先构建自己命名空间下的每个 CraftEngine 物品，让首次游戏内交互不必支 付冷构建成本：

```java
import com.huidu.farmersdelight.api.event.FarmersDelightWarmupEvent;
import net.momirealms.craftengine.bukkit.api.CraftEngineItems;
import net.momirealms.craftengine.core.util.Key;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

public final class BrewinWarmupListener implements Listener {

    private static final String NAMESPACE = "brewinandchewin";

    @EventHandler
    public void onWarmup(FarmersDelightWarmupEvent event) {
        BrewinItems.clearCache();
        for (Key key : CraftEngineItems.loadedItems().keySet()) {
            if (NAMESPACE.equals(key.namespace())) {
                BrewinItems.createCached(key.toString());
            }
        }
    }
}
```

先清缓存这一步很重要：走 `"reload"` 路径时，你之前构建的物品堆已经是陈旧的了。

***

## ProfessionCookingExperienceEvent

`com.huidu.farmersdelight.api.event.ProfessionCookingExperienceEvent` — `@ApiStatus.NonExtendable`

注意名字：只有这一个没有 `FarmersDelight` 前缀。

### 触发时机

五个触发点，每个工作站一个，外加附属入口。它们的负载差异值得了解：

| 触发点                                                  | `getSource()`     | `getLocation()` | 说明                                                                    |
| ---------------------------------------------------- | ----------------- | --------------- | --------------------------------------------------------------------- |
| `FarmersDelightPlugin.callCookingPotExperienceEvent` | `"cooking_pot"`   | **恒为 null**     | 使用五参构造器。由 `CookingPotBlockBehavior`（从方块取餐）与 `CookingPotGui`（从输出槽取餐）进入 |
| `StoveManager.finishCooking`                         | `"stove"`         | 炉灶方块            | 仅当匹配到营火配方**且**该槽位记录了归属者时                                              |
| `SkilletManager.finishCooking`                       | `"skillet"`       | 煎锅方块            | 仅当煎锅记录了归属者时                                                           |
| `CuttingBoardBlockBehavior.processCutting`           | `"cutting_board"` | 切菜板             | `getBaseExperience()` **硬编码为 0.0f**——切菜板不结算经验                         |
| `FarmersDelightApi.awardCraftingExperience`          | 调用方传入的值           | 调用方传入的位置        | 附属应走的路径                                                               |

炖锅那处 location 为 null 是真实缺口，不是本文档的疏漏：插件在那里仍使用五参兼容构造器，而该构造器以 `null` 委托。请始终对 `getLocation()` 做 null 判断。

切菜板那处对锁很讲究：事件在方块实体监视器**内部构造**，以便快照到一致的状态，但被交回外层、在释放监视器 **之后**才派发，因此没有任何第三方监听器运行在那把锁之下。

### 携带的数据

`getPlayerId()` `UUID`、`getPlayerName()` `String`（可能为 null）、`getSource()` `String`、`getResult()` `ItemStack`（克隆，可能为 null）、`getBaseExperience()` `float`、`getLocation()` `Location`（克隆，可能为 null）。

`getBaseExperience()` 是工作站结算的经验值，**在任何配置倍率之前**。事件是只读的，你无法改变实际发放量。

两个公开构造器：

```java
// 五参：为了让针对旧版本编译的调用方继续链接得上；location 为 null
public ProfessionCookingExperienceEvent(UUID playerId, String playerName, String source,
                                        ItemStack result, float baseExperience)

// 推荐
public ProfessionCookingExperienceEvent(UUID playerId, String playerName, String source,
                                        ItemStack result, float baseExperience, Location location)
```

### 从自己的工作站触发

不要直接构造。改为调用 API，它还会顺带掉落原版经验球（受炖锅经验配置控制）、发放 AuraSkills 经验，然后替你 触发事件：

```java
FarmersDelightApi.get().awardCraftingExperience(player, dropLocation, resultItem, xp, "keg");
```

签名为 `void awardCraftingExperience(Player player, Location location, ItemStack result, double baseExperience, String source)`。它是 Folia 安全的——整个方法体跑在 `plugin.scheduler().runAt(location, ...)` 内，因此事件到达的是拥有该位置的 region，而不是你的调用线程。

### 取消与线程

不可取消——监听器运行时经验已经决定好了，在 API 那个触发点上甚至已经发放。

线程随触发点而异：工作站的几处在拥有该工作站方块的 region 线程上触发；API 那处在你传入位置所属的 region 上 触发。

### 示例

摘自附属模板：

```java
import com.huidu.farmersdelight.api.event.ProfessionCookingExperienceEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;

public final class CookingExperienceListener implements Listener {

    @EventHandler
    public void onCookingExperience(ProfessionCookingExperienceEvent event) {
        ItemStack result = event.getResult();
        if (result == null) {
            return; // getResult() 是防御性克隆，可能为 null
        }
        logger.fine(event.getPlayerName() + " earned " + event.getBaseExperience()
                + " XP from " + event.getSource() + " -> " + result.getType());
    }
}
```

## 相关页面

* [FarmersDelightApi 入口](farmersdelight-api.md)
* [方块与工作站](blocks-and-stations.md)
* [自定义 buff 与 Bossbar](buffs.md)
* [配方发现](recipe-discovery.md)
