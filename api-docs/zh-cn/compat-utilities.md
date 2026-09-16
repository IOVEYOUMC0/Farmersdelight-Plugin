---
icon: code-branch
---

[English](../en/compat-utilities.md)

# 版本兼容工具

`com.huidu.farmersdelight.api.util` 里有三个小工具类，存在的意义只有一个：让附属用同一个编译产物同时跑在 Minecraft 1.21.4 到当前版本上。其中两个抹平 Bukkit API 变更，另一个是 CraftEngine 的提示框工具。

支持下限已经是 1.21.4，所以两个兼容垫片在全部受支持的服务端上都能解析成功。保留它们的理由有二：它们是已发布的 API；它们仍然吸收属性注册表改名，以及未来 `setItemModel` 被移除的可能。

同包下的 `DebugToolExtension` 与 `DebugToolRegistry` 见[调试工具](debug-tools.md)，`PluginManagerGuard` 见 [快速上手](getting-started.md)。

这三个类是全项目唯一的实现，**不要自己再抄一份**。BAC 早期抄过，现在它自己的 `CompatAttributes` / `CompatItemMeta` 已经退化成纯转发的壳，留着只是为了让原有 import 还能编译。

## CompatAttributes

`@ApiStatus.NonExtendable`，final，两个公开常量。

```java
public static final Attribute MAX_HEALTH;
public static final Attribute ATTACK_SPEED;
```

Minecraft 改过属性注册表的命名：旧服务端的枚举常量是 `GENERIC_MAX_HEALTH`、注册表键是 `generic.max_health`，新服务端 改成了 `MAX_HEALTH` 和 `max_health`。代码里直接写死任何一边的枚举常量，都会导致这个类在另一边加载失败。

解析顺序是：先按新写法、再按旧写法查 `Registry.ATTRIBUTE`，都拿不到再退回反射取字段。全程不直接引用任何版本特定的 枚举常量，所以在所有受支持的版本上都能正常加载。

**当运行的服务端两种写法都没有时，常量为 null。** 传给 `getAttribute` 之前必须判空。BAC 的真实写法：

```java
AttributeInstance maxHealthAttr = player.getAttribute(CompatAttributes.MAX_HEALTH);
if (maxHealthAttr == null) return;
```

## CompatItemMeta

`@ApiStatus.NonExtendable`，final，两个静态方法。

| 方法                                               | 行为                                         |
| ------------------------------------------------ | ------------------------------------------ |
| `isSupported()`                                  | 当前服务端有 `ItemMeta.setItemModel` 时返回 `true`。 |
| `setItemModel(ItemMeta meta, NamespacedKey key)` | 应用 `item_model` 组件，或者什么都不做。                |

`ItemMeta.setItemModel` 只存在于 Minecraft 1.21.4 及以上，而 1.21.4 正是支持下限，所以反射查找（只解析一次，缓存在静态字段里）在全部受支持的服务端上都会成功。保留这个垫片，是为了让按旧 api jar 编译的附属继续可用，也为了在某个分支移除该方法时降级而不是抛异常。

`setItemModel` 在三种情况下会**静默地什么都不做**：方法不可用、`meta` 为 null、`key` 为 null。它还会吞掉 `ReflectiveOperationException`。它不抛异常也不报告失败，而且没有返回值，所以你无法从调用结果判断模型到底有没有设上。

BAC 的真实调用只对 key 的解析做了保护，版本差异完全交给这个静默 no-op 吸收，同时独立设置 custom model data，让旧版 服务端也有东西可渲染：

```java
if (customModelData != null) {
    meta.setCustomModelData(customModelData);
}
if (itemModel != null && !itemModel.isBlank()) {
    NamespacedKey modelKey = NamespacedKey.fromString(itemModel.toLowerCase(Locale.ROOT));
    if (modelKey != null) {
        CompatItemMeta.setItemModel(meta, modelKey);
    }
}
```

`isSupported()` 是留给"需要切到另一套完全不同的降级方案"的场景。目前没有任何调用方用它——所有真实调用点都直接依赖那个 静默 no-op。

## TooltipUtils

final，无 `@ApiStatus` 注解，一个静态方法：

```java
public static void hideDurabilityLine(Item wrapped)
```

注意参数类型：这是 CraftEngine 的 `net.momirealms.craftengine.core.item.Item` 包装类，**不是** Bukkit 的 `ItemStack`。需要用到它的调用方本来就已经在 CraftEngine 那一侧了，所以签名没有粉饰这一点。若需使用，先用 `BukkitItemManager.instance().wrap(stack)` 包装。

这个方法把 `minecraft:damage` 和 `minecraft:max_damage` 加进 `minecraft:tooltip_display.hidden_components`，并且是与已有条目**合并**而不是覆盖。当物品的耐久值编码的不是工具磨损 而是别的东西——进度条、份数——就用它，免得玩家看到一行毫无意义的 "Durability: X / Y"。

改动作用在包装类上，会写回你包装的那个 stack。调用方的原 stack 不能被动到时，先 clone。FDAddonTemplate 完整演示了把 容量编码进耐久条的整套做法：

```java
ItemStack copy = stack.clone();
Item wrapped = BukkitItemManager.instance().wrap(copy);
wrapped.maxDamage(capacity);
wrapped.damage(Math.max(1, capacity - Math.min(capacity, amount)));
TooltipUtils.hideDurabilityLine(wrapped);
return copy;
```

`Math.max(1, ...)` 是有用的：原版在 damage 为 0 时会把耐久条整个隐藏，不这样写的话，装满的容器反而没有耐久条。

## 相关页面

* [快速上手](getting-started.md)
* [调试工具](debug-tools.md)
* [物品](items.md)
