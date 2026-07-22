---
icon: book-open-lines
---

# 配方发现

配方发现是配方书的逐玩家锁定 / 解锁状态。开启后配方初始是锁定的，玩家通过获得原料或成品、管理员命令、或本 API 来 逐步解锁。它**默认关闭**；关闭状态下所有配方都读作已解锁，这里的每个调用都是无害的空操作。

锁定**只影响显示**。被锁定的配方在工作站上照样能做出来，只是书里不给看。

## 存储键 —— 发布 id 之前务必先读这段

解锁状态按玩家存成一组字符串，每一条是类型 id 和配方 id 用一个空格拼起来的：

```
farmersdelight:cooking_pot farmersdelight:beef_stew
brewinandchewin:keg strong_ale
```

文件是 `plugins/FarmersDelight/recipe-discovery.yml`，以玩家 UUID 为键，值是这些字符串的列表。

由此产生三条附属作者必须内化的后果：

1. **配方 id 就是存档键。改配方 id 会让所有玩家对它的解锁记录变成孤儿。** 旧键还留在文件里，但对不上任何东西；对 已经发现过它的玩家来说，这条配方会重新变回锁定。API 里没有任何迁移手段。请挑一个你能长期接受的 id；如果非改 不可，就要准备自己写一遍文件修补，或者接受这部分进度丢失。
2. **`RecipeType` id 同理。** 改类型 id 会让该类型下所有配方的解锁记录全部变孤儿。
3. **两个 id 都不能含空格。** 键是按第一个空格切开的，按类型查询时是拿 `"<typeId> "` 前缀去匹配的。类型 id 里带 空格，会静默毁掉它产生的每一条键。

当前未注册的类型对应的键是**有意保留**、不做清理的 —— 把某个附属拆掉重启一次，不会抹掉玩家在它那边的进度。

厨锅还有一条存储上的细节：解锁键只认 id，所以同一个厨锅配方 id 若被多个自定义配方组各自声明，它们共享同一个解锁位。

## API

`com.huidu.farmersdelight.api.recipe.FarmersDelightRecipeDiscovery` —— final，纯静态。

```java
public static final String TYPE_COOKING_POT   = "farmersdelight:cooking_pot";
public static final String TYPE_CUTTING_BOARD = "farmersdelight:cutting_board";

public static boolean isEnabled();
public static boolean isUnlocked(Player player, String typeId, String recipeId);
public static boolean unlock(Player player, String typeId, String recipeId);
public static void lock(Player player, String typeId, String recipeId);
public static int unlockAll(Player player);
public static Set<String> unlockedOf(Player player, String typeId);
public static void triggerObtain(Player player, String itemId);
```

FarmersDelight 自己的配方用那两个 `TYPE_` 常量寻址；你自己的配方用 `RecipeType.id()` 加 `ViewableRecipe.id()`。

```java
public void recipeDiscovery(Player player) {
    if (FarmersDelightRecipeDiscovery.isEnabled()) {
        FarmersDelightRecipeDiscovery.unlock(player, "fdaddon:example", "example");
        boolean known = FarmersDelightRecipeDiscovery.isUnlocked(player,
                FarmersDelightRecipeDiscovery.TYPE_COOKING_POT, "farmersdelight:beef_stew");
        getLogger().fine("beef_stew unlocked=" + known);
    }
}
```

### 各方法语义

`isEnabled()` —— 配置里是否开启了发现。为 `false` 时一切都读作已解锁。

`isUnlocked(player, typeId, recipeId)` —— 发现关闭时返回 `true`，`player` 为 `null` 时也返回 `true`。它回答的是 「这个玩家能不能看到它」，不是「存储里有没有这条记录」。

`unlock(...)` —— 只有在这条配方是**新**解锁时才返回 `true`。重复解锁返回 `false`，不改状态、不发事件。

`lock(...)` —— 重新锁定。这里返回 `void`；重复锁定同样是空操作。

`unlockAll(player)` —— 解锁所有已注册类型的全部已知配方（含 FarmersDelight 自己的），返回新解锁的条数。

`unlockedOf(player, typeId)` —— 该类型下这个玩家存储中已解锁的配方 id。注意：它直接读存储，**不**看 `isEnabled()`。发现关闭时，`isUnlocked` 对一切返回 `true`，而 `unlockedOf` 可能返回空集合。不要拿 `unlockedOf(...).contains(...)` 当作 `isUnlocked` 的替代品。

`triggerObtain(player, itemId)` —— 把该物品 id 当作刚刚被获得，解锁所有以它为键的配方。用于你自己的「你获得了 XX」流程。只有在发现已开启**且**配置里的获得触发也开着时才生效，否则是空操作。

获得索引由每条配方的成品和它的**精确物品**原料构建。标签原料是被刻意排除的 —— 一个标签太宽泛，不适合驱动自动解锁。 对附属类型，索引取 `ViewableRecipe.result()` 和 `ViewableRecipe.inputs()`；每当有 `RecipeType` 注册或反注册，索引 都会重建。

### 线程

状态本身是并发 map 上的操作，因此这些方法可以从异步回调里调用，照样会生效并正常返回。真正有约束的是**事件**： Bukkit 不允许从异步线程同步派发事件，所以当状态变更发生在非服务器线程上时，构造好的事件会被交给全局区域执行，而不是 就地触发。两种路径都不会出问题，只是监听器看到事件的时刻会略晚于状态变更。

## FarmersDelightRecipeDiscoveryEvent

```java
package com.huidu.farmersdelight.api.event;

public class FarmersDelightRecipeDiscoveryEvent extends Event {
    public enum Action { UNLOCK, LOCK }
    public enum Source { OBTAIN, API, COMMAND }

    public UUID getPlayerId();
    public String getTypeId();
    public String getRecipeId();
    public Action getAction();
    public Source getSource();
}
```

在玩家的发现状态**真正**发生变化时触发。每一次真实状态变更都会触发它 —— 无论来自本 API、获得触发，还是管理员命令 —— 因此附属能对不是自己发起的发现做出反应。

* **不可取消。** 它继承 `Event`，没有实现 `Cancellable`；既没有 `setCancelled` 可调，也没有任何调用点会理会它。 监听器运行时状态早已提交。要否决，应该在请求解锁之前的上游做。
* **每次状态跃迁恰好一个事件。** 重复解锁和重复锁定都不触发。一次性解锁 / 锁定整个类型时，真正发生变化的每条配方 各触发一次。
* **线程。** 在执行这次变更的那个线程上派发，且不持有发现管理器的任何锁：获得触发是持有该玩家的区域线程，管理员 命令是命令线程，API 则是调用方线程。请把它当作区域内本地事件来处理 —— 只操作事件里指名的那个玩家，别去动无关的 世界状态。
* **玩家可能不在线。** 事件携带的是 `UUID` 而不是 `Player`，因为命令路径可以改一个不在服上的玩家的存储状态。请始终 对 `Bukkit.getPlayer(uuid)` 判空。

所有访问器返回的都是不可变值（`UUID`、`String`、枚举），监听器没有任何可以顺藤摸瓜改掉的可变状态。

## 发现在配方书里的表现

显示模式由服务端配置决定，你的 `RecipeType` 会承受它的后果：

* **占位模式** —— 被锁定的配方仍占着列表里的位置，但显示为配置的锁定图标；点击它会给玩家发一条「尚未解锁」的提示， 而不是打开详情页。
* **隐藏模式** —— 被锁定的配方被整个从列表里移除，因此分页会移位：45 条配方里锁了 40 条，你的六行书就塌成一页。

这两种模式都由 FarmersDelight 处理。你的 `ViewableRecipe` 不需要知道任何锁定状态，也不该自己去按它过滤。
