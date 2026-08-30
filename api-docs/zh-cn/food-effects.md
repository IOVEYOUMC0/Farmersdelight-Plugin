---
icon: sparkle
---

[English](../en/food-effects.md)

# 食物效果

包名：`com.huidu.farmersdelight.api.effect` 类：`FarmersDelightFoodEffects` —— `final`，私有构造。类上没有 `@ApiStatus` 注解，按普通稳定 API 对待即可。

FarmersDelight 自带两种食物效果：

* **舒适（Comfort）** —— 未饱食状态下缓慢回血。
* **营养（Nourishment）** —— 抑制饥饿消耗。

附属插件可以直接施加、查询、清除这两种效果，也可以注册自己的食物物品，让玩家吃下时触发效果。

所有签名只用 Bukkit / `java` 类型，方法体转发到混淆后的内部实现。

## 线程模型

施加 / 移除类方法会修改玩家状态并发送消息，必须在玩家所属线程上执行 —— Paper 上是主线程，Folia 上是该玩家的 区域线程。不要在异步任务里调用。

注册 / 反注册类方法只写一个 `ConcurrentHashMap`，在 `onEnable`、重载处理、`onDisable` 里调用都是安全的。

## 施加与查询

```java
public static void    applyComfort(Player player, int durationSeconds);
public static void    applyComfort(Player player, int durationSeconds, int level);
public static void    applyNourishment(Player player, int durationSeconds);
public static void    applyNourishment(Player player, int durationSeconds, int level);

public static boolean hasComfort(Player player);
public static boolean hasNourishment(Player player);

public static void    removeComfort(Player player);
public static void    removeNourishment(Player player);
```

时长单位是**秒**，不是 tick。两参数重载按等级 1 施加。

`level` 从 1 起算，内部会向下钳制到最小 1。

施加类方法在 `player` 为 `null` 或 `durationSeconds` 小于等于 0 时静默返回。查询与移除类方法对 `null` 同样安全。

### 叠加规则

新施加的一剂会按原版药水规则与当前生效的一剂叠加：

* 等级**更高**：替换当前效果并刷新时长；
* 等级**相同**：取两者剩余时间中较长的一个；
* 等级**更低**：在更强的效果生效期间直接忽略。

这两种效果不可串接，所以被压住的弱剂不会被存起来等以后续上 —— 它就是被丢弃了。

### 一个需要知道的前提

已在实现处核实：当 FarmersDelight 的自定义 buff 系统被关闭时（`CustomBuffRegistry.isSystemEnabled()` 为 false）， `applyComfort` 和 `applyNourishment` 会立即返回，调用静默无效。如果你的附属插件依赖效果确实生效，请在调用后用 `hasComfort` / `hasNourishment` 复查，不要假定施加一定成功。

```java
FarmersDelightFoodEffects.applyComfort(player, 120);          // 2 分钟，等级 1
FarmersDelightFoodEffects.applyNourishment(player, 300, 2);   // 5 分钟，等级 2

if (FarmersDelightFoodEffects.hasComfort(player)) {
    // ...
}
```

## 注册附属食物

```java
public static void registerComfortFood(String itemId, int durationSeconds);
public static void registerNourishmentFood(String itemId, int durationSeconds);
public static void unregisterComfortFood(String itemId);
public static void unregisterNourishmentFood(String itemId);
```

`itemId` 是带命名空间的 id —— CraftEngine 自定义 id 或 `minecraft:...`。吃下该物品即获得对应效果，持续 `durationSeconds` 秒。

三条特性，均在进食处理点核实过：

1. **无视配置开关。** 已注册的食物不受 FarmersDelight 配置里 `comfort-foods` / `nourishment-foods` 的 `enabled` 开关影响。
2. **优先级高于配置项。** 处理逻辑先读附属注册的映射表，只有该 id 没有注册项时才回落到配置加载的映射表。
3. **能扛过 `/fd reload`。** 配置重载只清空配置加载的映射表，附属注册的映射表原样保留。你不需要为了重新注册去监听 `FarmersDelightReloadEvent`。

用同一个 id 再次注册会覆盖之前的时长。`null` id 或非正时长是空操作。FarmersDelight 不可用时四个方法都是空操作。

请在你的插件禁用时反注册。

## 注册器模式

BrewinAndChewin 的 `FoodEffectRegistrar` 是生产环境的写法，它自己的注释就写明这是任何附属插件都可以照抄的模式： 每个插件持有一个实例，在 `onEnable` 以及自己的重载监听里调用 `apply`，在 `onDisable` 里调用 `clear`。它记录自己 注册过哪些 id，所以重载时的 `apply` 会先干净地反注册上一批，再应用新的一批。

```java
import com.huidu.farmersdelight.api.effect.FarmersDelightFoodEffects;
import org.bukkit.configuration.ConfigurationSection;

public final class FoodEffectRegistrar {

    private final Set<String> registeredNourishment = new HashSet<>();
    private final Set<String> registeredComfort = new HashSet<>();

    public void apply(ConfigurationSection root) {
        clear();
        if (root == null) {
            return;
        }
        registerSection(root.getConfigurationSection("nourishment"), true);
        registerSection(root.getConfigurationSection("comfort"), false);
    }

    public void clear() {
        for (String id : registeredNourishment) {
            FarmersDelightFoodEffects.unregisterNourishmentFood(id);
        }
        for (String id : registeredComfort) {
            FarmersDelightFoodEffects.unregisterComfortFood(id);
        }
        registeredNourishment.clear();
        registeredComfort.clear();
    }

    private void registerSection(ConfigurationSection section, boolean nourishment) {
        if (section == null) {
            return;
        }
        for (String id : section.getKeys(false)) {
            int seconds = section.getInt(id);
            if (seconds <= 0) {
                continue;
            }
            if (nourishment) {
                FarmersDelightFoodEffects.registerNourishmentFood(id, seconds);
                registeredNourishment.add(id);
            } else {
                FarmersDelightFoodEffects.registerComfortFood(id, seconds);
                registeredComfort.add(id);
            }
        }
    }
}
```

对应的配置段：

```yaml
food-effects:
  comfort:
    brewinandchewin:kombucha: 240
  nourishment:
    brewinandchewin:cheesy_pasta: 300
```

`FDAddonTemplate` 里有同一个类，名字叫 `ExampleFoodEffectRegistrar`，读的是列表 + 映射的形状 （`- id: ... / duration: ...`）而不是键值对形状。两种配置布局都行 —— API 只关心 id 和秒数。另外注意模板里的注释 写的是 "duration-ticks"；API 的参数名是 `durationSeconds`，实现里用的也是秒（内部乘 20 转成 tick）。

## 相关页面

* [物品](items.md)
* [自定义 buff 与 Bossbar](buffs.md)
* [事件](events.md)
