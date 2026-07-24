---
description: 包名：com.huidu.farmersdelight.api.buff
icon: droplet
---

# 自定义 buff

所谓自定义 buff，指的是附属自己维护、不走原版 `PotionEffect` 的玩家状态——FarmersDelight 自带的 Comfort 与 Nourishment，Brewin' And Chewin' 的 Tipsy / Sweet Heart / Raging / Intoxication 都属于这类。这些状态存在附属自己 的 map 里，原版看不见：喝牛奶清不掉，重新登录带不回来，HUD 插件也读不到。

这个包里的三个类型就是为解决这些问题而存在的。你实现 `CustomBuff`，把它交给 `CustomBuffRegistry`， FarmersDelight 会接手所有需要统一管理的部分（牛奶解除、进出服持久化、`/fd buff` 管理指令、PlaceholderAPI 输出、 状态变化事件）。`BuffBossbar` 是独立且可选的一层：它负责把 buff 画到屏幕上，具体画在哪个通道由服主决定。

| 类型                   | 形态                                      | 你的用法 |
| -------------------- | --------------------------------------- | ---- |
| `CustomBuff`         | 接口，`@ApiStatus.OverrideOnly`            | 实现它  |
| `CustomBuffRegistry` | final 类，静态方法，`@ApiStatus.NonExtendable` | 调用它  |
| `BuffBossbar`        | final 类，静态方法，`@ApiStatus.NonExtendable` | 调用它  |

`CustomBuff` 上的 `@ApiStatus.OverrideOnly` 的含义是：你可以实现这个接口，但不要直接去调别的附属的 `CustomBuff` 方法——统一走注册表，注册表会隔离异常，也会让变化事件的差分缓存保持正确。另外两个类的 `@ApiStatus.NonExtendable` 在语言层面已经强制了（都是 `final` 且构造器私有）。

## 实现 CustomBuff

只有三个方法是抽象的，其余都带有能安全降级的默认实现。

```java
String id();                                       // 稳定的带命名空间 id："myaddon:example_buff"
boolean isActive(Player player);
void remove(Player player);

default boolean apply(Player player, int level, int durationSeconds) { return false; }
default boolean isLowPriority()                    { return false; }
default int level(Player player)                   { return isActive(player) ? 1 : 0; }
default int remainingSeconds(Player player)        { return 0; }
default String nameKey()                           { return ""; }
default void saveState(Player player)              { }
default void restoreState(Player player)           { }
```

实现必须遵守的几条约定：

* **等级从 1 开始。** `1` 是基础强度（对应药水 amplifier 0），`2` 对应 amplifier 1，`0` 表示未生效。注册表就是 靠 `level()` 返回 `0` 来判断"玩家没有这个 buff"。
* **所有操作必须幂等。** 对没有该 buff 的玩家调 `remove()` 是空操作；`isActive()` 在首次授予前和移除后都返回 `false`。
* **`apply()` 的返回值表示是否真的授予成功。** 默认的 `false` 意味着"这个 buff 无法通过程序授予"， `/fd buff give` 会据此回报给管理员。想让管理指令对你的 buff 生效，就实现它。
* **`restoreState()` 必须是补空式的。** FarmersDelight 在玩家进服时会调用两次（见[持久化](buffs.md#持久化)），所以当 buff 已经生效时它必须原样返回、什么都不动。模板的实现第一行就是这个守卫。
* **`saveState()` 在 buff 未生效时要清掉自己的键**，否则玩家 PDC 里会留下过期条目，下一次进服又被还原回来。

线程：所有方法都在目标玩家所属的区域线程上被调用。在 Paper 上就是主线程，在 Folia 上是拥有该玩家的区域线程。 即便如此，每玩家状态仍建议用 `ConcurrentHashMap`——你自己的计时器会从别处读它。

持久化时请存**剩余秒数**，绝不要存绝对 tick 或时间戳，这样重启才不会把时长凭空拉长或清零。下面是模板中 `ExampleCustomBuff` 的精简版：

```java
public final class ExampleCustomBuff implements CustomBuff {

    public static final String ID = "myaddon:example_buff";

    private static final NamespacedKey PDC_LEVEL =
            Objects.requireNonNull(NamespacedKey.fromString("myaddon:example_buff_level"));
    private static final NamespacedKey PDC_REMAINING =
            Objects.requireNonNull(NamespacedKey.fromString("myaddon:example_buff_remaining"));

    private record State(int level, long expiryEpochSeconds) { }

    private final Map<UUID, State> states = new ConcurrentHashMap<>();

    @Override
    public String id() {
        return ID;
    }

    @Override
    public boolean apply(Player player, int level, int durationSeconds) {
        if (player == null || level <= 0 || durationSeconds <= 0) {
            return false;
        }
        states.put(player.getUniqueId(), new State(level, nowSeconds() + durationSeconds));
        return true;
    }

    @Override
    public boolean isActive(Player player) {
        return remainingSeconds(player) > 0;
    }

    @Override
    public void remove(Player player) {
        states.remove(player.getUniqueId());
    }

    @Override
    public int level(Player player) {
        State state = states.get(player.getUniqueId());
        return state != null && state.expiryEpochSeconds() > nowSeconds() ? state.level() : 0;
    }

    @Override
    public int remainingSeconds(Player player) {
        State state = states.get(player.getUniqueId());
        if (state == null) {
            return 0;
        }
        long remaining = state.expiryEpochSeconds() - nowSeconds();
        return remaining > 0 ? (int) remaining : 0;
    }

    @Override
    public String nameKey() {
        return "buff.myaddon.example";
    }

    @Override
    public void saveState(Player player) {
        PersistentDataContainer pdc = player.getPersistentDataContainer();
        int remaining = remainingSeconds(player);
        if (remaining <= 0) {
            pdc.remove(PDC_LEVEL);
            pdc.remove(PDC_REMAINING);
            return;
        }
        pdc.set(PDC_LEVEL, PersistentDataType.INTEGER, level(player));
        pdc.set(PDC_REMAINING, PersistentDataType.INTEGER, remaining);
    }

    @Override
    public void restoreState(Player player) {
        if (isActive(player)) {
            return; // 补空式：绝不覆盖已经存在的状态
        }
        PersistentDataContainer pdc = player.getPersistentDataContainer();
        Integer level = pdc.get(PDC_LEVEL, PersistentDataType.INTEGER);
        Integer remaining = pdc.get(PDC_REMAINING, PersistentDataType.INTEGER);
        if (level != null && remaining != null && level > 0 && remaining > 0) {
            apply(player, level, remaining);
        }
    }

    private static long nowSeconds() {
        return System.currentTimeMillis() / 1000L;
    }
}
```

Brewin' And Chewin' 则把四个 buff 写成匿名实现，直接转发到自己已有的 manager——当状态本来就存在别处时，这是 更合适的写法：

```java
CustomBuffRegistry.register(new CustomBuff() {
    @Override public String id() { return "brewinandchewin:tipsy"; }
    @Override public boolean isActive(Player p) {
        return tipsyManager != null && tipsyManager.level(p.getUniqueId()) > 0;
    }
    @Override public void remove(Player p) {
        if (tipsyManager != null) tipsyManager.clearPlayer(p.getUniqueId());
    }
    @Override public boolean apply(Player p, int level, int durationSeconds) {
        return tipsyManager != null && tipsyManager.applyLevel(p, level, durationSeconds);
    }
    @Override public boolean isLowPriority() { return true; }
    @Override public int level(Player p) {
        return tipsyManager == null ? 0 : tipsyManager.level(p.getUniqueId());
    }
    @Override public int remainingSeconds(Player p) {
        return tipsyManager == null ? 0 : tipsyManager.remainingSeconds(p.getUniqueId());
    }
    @Override public String nameKey() { return "buff.brewinandchewin.tipsy"; }
    @Override public void saveState(Player p) {
        if (tipsyManager != null) tipsyManager.saveToPdc(p);
    }
    @Override public void restoreState(Player p) {
        if (tipsyManager != null) tipsyManager.restoreFromPdc(p);
    }
});
```

{% hint style="warning" %}
注意对 manager 的判空：注册项的生命周期可能长于 manager（关服流程里 manager 先被停掉），而 buff 方法抛出的异常 会被吞掉、不会有任何报错。
{% endhint %}

## 注册与注销

```java
public final class MyAddon extends JavaPlugin {

    private final ExampleCustomBuff exampleBuff = new ExampleCustomBuff();

    @Override
    public void onEnable() {
        if (!FarmersDelightApi.get().isAvailable()) {
            getLogger().warning("FarmersDelight not available; addon features disabled.");
            return;
        }
        CustomBuffRegistry.register(exampleBuff);
    }

    @Override
    public void onDisable() {
        if (FarmersDelightApi.get().isAvailable()) {
            CustomBuffRegistry.unregister(exampleBuff);
        }
    }
}
```

注册以 `id()` 为键且幂等：用已存在的 id 再注册一次会替换掉旧条目，这正是热重载插件时自然会发生的行为。 注销有两个重载——`unregister(CustomBuff)`（按 id 匹配，所以传一个 id 相同的实例也行）和 `unregister(String id)`。Brewin' And Chewin' 在关闭时用的是字符串形式，因为它的 buff 是匿名类、没有留引用。

{% hint style="warning" %}
即使 FarmersDelight 配置里把 buff 系统整体关掉，注册照样成功，只是在管理员重新打开之前不会有任何授予发生。 不要把注册这一步用 `isSystemEnabled()` 包起来。
{% endhint %}

## 注册表的完整接口

以下方法都是 `CustomBuffRegistry` 上的静态方法。

| 方法                                                         | 返回                 | 说明                                                    |
| ---------------------------------------------------------- | ------------------ | ----------------------------------------------------- |
| `register(CustomBuff buff)`                                | `void`             | buff 为 null 或 `id()` 为 null 时抛 `NullPointerException` |
| `unregister(CustomBuff buff)`                              | `void`             | 不存在或为 null 时空操作                                       |
| `unregister(String id)`                                    | `void`             | 不存在或为 null 时空操作                                       |
| `all()`                                                    | `List<CustomBuff>` | 底层写时复制列表的不可变视图；每次调用不分配对象，可在并发注册时安全遍历                  |
| `byId(String id)`                                          | `CustomBuff`       | 未注册返回 `null`；O(1)                                     |
| `isSystemEnabled()`                                        | `boolean`          | 对应配置项 `buff.enabled`                                  |
| `apply(Player, String id, int level, int durationSeconds)` | `boolean`          | 系统关闭、id 未知、buff 拒绝授予或 buff 抛异常时都返回 `false`            |
| `clearAll(Player)`                                         | `int`              | 实际移除的数量                                               |
| `clearOne(Player)`                                         | `CustomBuff`       | 只移除一个，优先非低优先级；没有任何生效项时返回 `null`                       |
| `activeBuffs(Player)`                                      | `List<CustomBuff>` | 新建列表，两种优先级都包含                                         |
| `syncState(Player, String buffId)`                         | `boolean`          | 检测到跃迁并触发事件时返回 `true`                                  |
| `syncState(Player, CustomBuff)`                            | `boolean`          | 同上，用于你手上已有实例的情况                                       |
| `syncAll(Player)`                                          | `void`             | 同步所有已注册 buff                                          |
| `saveAll(Player)`                                          | `void`             | 逐个调用 `saveState`；系统关闭时空操作                             |
| `restoreAll(Player)`                                       | `void`             | 逐个调用 `restoreState` 并同步；系统关闭时空操作                      |
| `forget(Player)`                                           | `void`             | 丢弃该玩家的差分缓存条目                                          |

有两个成员标注了 `@ApiStatus.Internal`，附属不得调用：`setSystemEnabled(boolean)`（由 FarmersDelight 的配置加载 写入）和 `syncTrackedPlayers()`（由 FarmersDelight 自己的 20 tick 巡回任务驱动）。

所有会回调 `CustomBuff` 的方法都做了逐 buff 的异常包裹并吞掉 `RuntimeException`，这样一个坏掉的附属不会连累 其他附属的牛奶清除、保存或还原。发生这种情况时**不会有任何日志**——如果某个 buff 莫名其妙毫无反应，先往这里查。

## FarmersDelight 替你驱动的部分

### 牛奶

FarmersDelight 以 `MONITOR` 优先级、`ignoreCancelled = true` 监听 `PlayerItemConsumeEvent`，运行在饮用玩家的 区域线程上：

* **`minecraft:milk_bucket`** 调用 `clearAll(player)`——把原版"牛奶清空一切"的语义扩展到自定义状态。每次移除 后都会跟一次 `syncState`，所以变化事件会立即触发。
* **任何带有 CraftEngine 标签 `farmersdelight:milk` 的自定义物品**（即 FarmersDelight 的 `milk_bottle`）只解除 **一项**，从"该玩家全部生效中的原版药水效果 + 全部生效中的非低优先级自定义 buff"这个池子里等概率随机抽取。 低优先级 buff 组成后备池，只有在主池为空时才动用。

有两点是在实际调用点核实过的，与类文档的措辞并不完全一致：

* milk\_bottle 这条路径**并不**调用 `CustomBuffRegistry.clearOne`。它通过 `activeBuffs(player)` 自建池子，好让 原版药水效果与自定义 buff 等权重竞争——单靠 `clearOne` 是永远不会考虑原版效果的。`clearOne` 提供给附属使用， 但 FarmersDelight 内部没有任何地方在用它。
* 由于该路径是直接调 `buff.remove(player)`，它**不会**同步这次跃迁。对应的 `FarmersDelightBuffChangeEvent` 要 等下一次周期同步才会送达，最多晚 20 tick。不要指望牛奶瓶解除能在同一 tick 内被上报。

`isLowPriority()` 返回 `true` 就是把 buff 放进后备池的开关。Brewin' And Chewin' 给 Tipsy 设了这个标志，让牛奶瓶 优先解除真正的负面效果、把"醒酒"留到最后，对应原模组的 `brewinandchewin:low_priority/milk_bottle` 效果标签。

### 持久化

持久化是集中管理的，你完全不需要为 buff 自己注册进服/退服监听器。

| 时机                                              | 执行内容                                      |
| ----------------------------------------------- | ----------------------------------------- |
| `PlayerJoinEvent`（`MONITOR`）                    | `restoreAll(player)`                      |
| `buff.persistence.restore-retry-delay-ticks` 之后 | 若玩家仍在线，在其实体调度器上再执行一次 `restoreAll(player)` |
| `PlayerQuitEvent`（`LOWEST`）                     | `saveAll(player)`                         |
| `PlayerQuitEvent`（`MONITOR`）                    | `forget(player)`                          |
| FarmersDelight 关闭时                              | 在注销 buff 之前对所有在线玩家执行 `saveAll`            |

重试是为整档同步类插件（HuskSync、MySQLPlayerDataBridge 等）准备的：它们会在玩家进服**之后**一小会儿才把 同步来的 PDC 写上去。默认 40 tick，设为 `0` 即关闭重试。这也正是 `restoreState` 必须补空式的原因——第二次调用 是无条件发生的。

退服保存刻意放在 `LOWEST`，好让你的 PDC 写入发生在同步插件给玩家做快照之前。由于状态就存在玩家 PDC 里，这样 持久化的 buff 能免费搭上整档同步、跨群组服务器带走。

Brewin' And Chewin' 还在自己的 `onDisable` 里对所有在线玩家额外调了一次 `saveAll`，位置在停掉那些持有实时数值 的 manager 之前。运行期热卸载（插件管理器、看门狗级联）不会触发退服事件，没有这一段的话在线玩家的状态就全丢了。 如果你的 buff 值得持久化，照抄这个写法：

```java
for (Player player : getServer().getOnlinePlayers()) {
    try {
        CustomBuffRegistry.saveAll(player);
    } catch (Throwable ignored) {
        // 逐玩家隔离；级联关闭时 FarmersDelight 可能已经停了
    }
}
```

### 变化事件

当某个已注册 buff 在玩家身上的等级**真的**发生变化时——获得（`0` 到 `N`）、失去（`N` 到 `0`）、或在两个非零 等级之间移动——会触发 `com.huidu.farmersdelight.api.event.FarmersDelightBuffChangeEvent`。单纯的续时间**不会** 触发，而续时间恰恰是最频繁的更新。

跃迁是在注册表内部对着一张"上次已知等级"表比对出来的，这正是"想推多勤都行"这个约定得以成立的原因。 `syncState` 发现等级与上次相同时什么都不做——不发事件，除了一次查表之外不分配任何对象。

```java
// 在你自己的代码改动 buff 状态之后
CustomBuffRegistry.syncState(player, ExampleCustomBuff.ID);
```

`CustomBuffRegistry.apply`、`clearAll`、`clearOne`、`restoreAll` 这几条路径会自己同步，调用后**不需要**再手动 同步。

而 buff 自然到期是在持有计时器的那一方内部发生的，这些路径都不经过注册表。为此 FarmersDelight 内部有一个每 **20 tick** 跑一次的巡回任务，把差分缓存里还留有等级的玩家逐个重新同步，每个玩家都在自己的调度器上执行。跃迁 的"失去"那一半就是靠它上报的。该任务只在 buff 系统开启时才会启动，且在无人有 buff 时读第一下就返回。

事件**不可取消**（它继承 `Event` 而非 `Cancellable`）——事件触发时变化早已发生。派发线程：如果跃迁是在主线程上 检测到的，事件就地同步派发；否则会把构造好的事件交给全局区域调度器再派发，因为 Bukkit 会拒绝在非 tick 线程上 派发同步事件。因此在 Folia 上，监听器可能是在全局区域线程上收到这个事件的，不能假定自己持有该玩家的区域。监听 器抛出的异常会被吞掉。

{% hint style="info" %}
字段：`getPlayerId()`、`getPlayerName()`（可能为 `null`）、`getBuffId()`、`getPreviousLevel()`、`getNewLevel()`、 `getRemainingSeconds()`，外加两个便捷判断 `isGained()` 与 `isLost()`。注意它携带的是 `UUID` 而不是 `Player`。
{% endhint %}

### 管理指令与占位符

`/fd buff give <buff> [level] [seconds] [player]` 走的就是 `CustomBuffRegistry.apply`，所以实现 `apply()` 才能让 你的 buff 变得可授予。buff 参数先按完整带命名空间 id 匹配，匹配不到再按短后缀匹配（`tipsy` 能找到 `brewinandchewin:tipsy`）。`/fd buff clear` 走 `clearAll`，或直接移除指定的那一个 buff。

装了 PlaceholderAPI 时，所有已注册 buff 都会暴露在 `farmersdelight` 标识符下：

```
%farmersdelight_buff_<ns>_<id>_active%     1 / 0
%farmersdelight_buff_<ns>_<id>_level%      当前等级，未生效为 0
%farmersdelight_buff_<ns>_<id>_time%       剩余秒数
%farmersdelight_buff_<ns>_<id>_time_fmt%   m:ss，超过一小时为 h:mm:ss
%farmersdelight_buff_<ns>_<id>_name%       翻译后的显示名（服务端语言）
%farmersdelight_buff_count%                该玩家身上生效中的 buff 数量
```

`<ns>_<id>` 就是把你的 buff id 里的冒号换成下划线，所以 `brewinandchewin:sweet_heart` 写作 `brewinandchewin_sweet_heart`。这些占位符读的是 `level()`、`remainingSeconds()` 和 `nameKey()`——这三个默认实现 存在的意义就在这里，不实现的话 HUD 只能拿到 `0` 和你的原始 id。这里是热路径（HUD 插件按玩家按 tick 求值），所以 这三个方法要保持在"一次 map 读取"的量级。

### 总开关

FarmersDelight `config.yml` 里的 `buff.enabled` 会在每次加载和重载时镜像进 `CustomBuffRegistry.isSystemEnabled()`。当它为 `false` 时：

* `register` / `unregister` 一切照常；
* `apply` 返回 `false`，`saveAll` 与 `restoreAll` 变成空操作；
* FarmersDelight 自己的效果 ticker 和那个 20 tick 同步任务都不会启动；
* 所有 `BuffBossbar` 调用都是空操作。

`saveAll` 被拦是刻意为之：关掉系统会清空实时状态，若不拦截，玩家下次退服时就会把这份"空"写进存档、永久毁掉他 原有的 buff。跳过这次写入，已存档的数据原样保留，等系统重新打开即可复用。

用 `isSystemEnabled()` 可以整段跳过你自己的每 tick 开销：

```java
if (!CustomBuffRegistry.isSystemEnabled()) {
    return;
}
```

## BuffBossbar

`BuffBossbar` 负责按玩家渲染 buff 状态。你以一个稳定的 `NamespacedKey` 为键推送标题、进度和样式；至于画在哪个 通道、什么布局，由**服主**在 FarmersDelight 的 `config.yml` 里决定。附属永远不选通道。

```java
public static boolean isEnabled();

public static void update(Plugin owner, Player player, NamespacedKey key,
                          Component title, float progress,
                          BossBar.Color color, BossBar.Overlay overlay);

public static void hide(Plugin owner, Player player, NamespacedKey key);

public static void hideAll(Plugin owner, Player player);

public static BossBar.Color parseColor(String raw, BossBar.Color fallback);
public static BossBar.Overlay parseOverlay(String raw, BossBar.Overlay fallback);
```

当配置为 `buff.enabled: false`、或 `buff.display.enabled: false`、或管理器尚未运行（FarmersDelight 启动之前、 停止之后）时，`isEnabled()` 返回 `false`。在这些窗口里其余调用本身也都是安全的空操作，所以这个判断属于优化而非 正确性要求。

`update` 对同一组 `(player, key)` 是幂等的：首次调用创建 bar，之后的调用就地修改。想推多勤就推多勤，把当前状态 推过去即可。它本身设计得很便宜——管理器会记住上次推送的元组快照，内容没变时直接返回、不碰 Adventure，并且会先把 `progress` 量化到 1/128 的网格上，让小于一像素的移动不会变成一个数据包。`progress` 会被钳制到 `[0,1]`（`NaN` 钳为 `0`）。标题为 `null` 视为空，颜色为 `null` 视为 `WHITE`，overlay 为 `null` 视为 `PROGRESS`。玩家不在线时 什么都不会发生。

颜色可选 `PINK, BLUE, RED, GREEN, YELLOW, PURPLE, WHITE`；overlay 可选 `PROGRESS, NOTCHED_6, NOTCHED_10, NOTCHED_12, NOTCHED_20`。`parseColor` / `parseOverlay` 接受这些名字的大小写任意、连字符或下划线任意的写法，遇到 拼写错误时返回兜底值而不抛异常——所以可以放心把服主手写的 YAML 字符串直接喂给它们。

标题请用 `FarmersDelightText.translatable(key, args...)`，这样每位玩家的客户端会各自按自己的语言渲染，同时带有 服务端解析出的 fallback，资源包缺条目时也不会露出原始键。`FarmersDelightText.formatDuration(int)` 给出 FarmersDelight 自家 bar 所用的原版风格 `m:ss`。

下面是模板中推送任务的精简版：

```java
private static final NamespacedKey KEY =
        Objects.requireNonNull(NamespacedKey.fromString("myaddon:example_buff"));
private static final float FULL_SECONDS = 60f;

public void tick() {
    if (!BuffBossbar.isEnabled()) {
        return;
    }
    for (Player player : Bukkit.getOnlinePlayers()) {
        int remaining = buff.remainingSeconds(player);
        if (remaining <= 0) {
            BuffBossbar.hide(plugin, player, KEY);
            continue;
        }
        Component title = FarmersDelightText.translatable(buff.nameKey(),
                FarmersDelightText.formatDuration(remaining));
        float progress = Math.max(0f, Math.min(1f, remaining / FULL_SECONDS));
        BuffBossbar.update(plugin, player, KEY, title, progress,
                BossBar.Color.YELLOW, BossBar.Overlay.PROGRESS);
    }
}
```

用一个重复任务来驱动它——`FarmersDelightApi.get().runRepeating(feed::tick, 20L, 10L)` 返回一个 `ApiTask`，在 `onDisable` 里取消掉。10 tick（半秒）是模板和 Brewin' And Chewin' 共同采用的频率：手感与原版药水条一致，又不至于 为一个几乎不动的数值做每 tick 的开销。

{% hint style="warning" %}
几个需要留意的坑：

* **`hideAll(owner, player)` 移除的是该玩家身上的全部 bar，而不只是你的。** 当前实现根本没有使用 `owner` 这个 参数，管理器直接把该玩家的整张 bar 表丢掉。除非你真的想"把这个玩家的 bar 全清掉"，否则请逐 key 调 `hide`。 `owner` 参数是为将来的按插件区分功能和报错预留的，今天它不构成任何作用域。
* **逐 key 隐藏是便宜路径。** 对从未显示过的 key 调 `hide` 会立刻返回。Brewin' And Chewin' 正是靠这一点，在每次 推送 tick 里对所有未生效的 buff 都调一次 `hide`。
* **你停止推送的 bar 不会自己消失。** 没有过期机制，必须显式调 `hide`。

退服时不需要你清理——`PlayerQuitEvent` 和 FarmersDelight 自身关闭时都会冲刷全部 bar。但仍然**建议**在你自己的 `onDisable` 里隐藏你的 bar（Brewin' And Chewin' 就是这么做的），免得单独热重载你的插件时留下残留的 bar；如果你 希望死亡时立刻消失、而不是等下一次推送 tick 追上，也可以在 `PlayerDeathEvent` 里隐藏。
{% endhint %}

线程：管理器按玩家加锁，所以两个附属对同一玩家推送不会互相冲突，不同玩家之间仍然并行。它不读取任何世界状态， 因此用一个全局重复任务驱动完全没问题——你喂进去的数值本来就是你自己的。

服主侧的配置项，供你写自己的文档时参照：`buff.display.channels` 可任意组合 `bossbar`、`actionbar`、`tab_footer`； `buff.display.layout-mode` 可选 `stacked`（全部同时显示）或 `rotating`（一次只显示一个，每 `rotation-interval-ticks` 轮换）。至于"显示 Raging 但隐藏 Tipsy"这类逐 buff 开关，那是你附属自己的事， FarmersDelight 不管。Brewin' And Chewin' 把它们放在自己 `config.yml` 的 `bossbar:` 段里，逐 buff 的 `color` / `overlay` 也在那里、通过 `parseColor` 与 `parseOverlay` 读取。

## 相关页面

* [文本与消息](text-and-messages.md)
* [调度](scheduling.md)
* [事件](events.md)
* [配置更新](config-updates.md)
