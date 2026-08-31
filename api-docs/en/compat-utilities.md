
[简体中文](../zh-cn/compat-utilities.md)
# Version compatibility helpers

`com.huidu.farmersdelight.api.util` contains three small helpers that exist so an addon can support Minecraft
1.21 through current builds from a single compiled jar. Two of them paper over Bukkit API changes; the third
is a CraftEngine tooltip helper.

`DebugToolExtension` and `DebugToolRegistry` also live in this package but are covered in
[Debug tools](debug-tools.md). `PluginManagerGuard` is covered in [Getting started](getting-started.md).

These are the single shared implementations. Do not keep your own copy — BAC originally did, and its local
`CompatAttributes` / `CompatItemMeta` are now thin delegating shims kept only so its existing imports still
resolve.

## CompatAttributes

`@ApiStatus.NonExtendable`, final, two public constants.

```java
public static final Attribute MAX_HEALTH;
public static final Attribute ATTACK_SPEED;
```

Minecraft renamed the attribute registry entries: older servers expose the enum constant `GENERIC_MAX_HEALTH`
under the registry key `generic.max_health`, newer ones use `MAX_HEALTH` and `max_health`. Referencing either
enum constant directly in your code makes the class fail to load on the other side of the rename.

Resolution goes through `Registry.ATTRIBUTE` by key first — modern spelling, then legacy — and falls back to a
reflective field lookup. No version-specific enum constant is ever referenced directly, so the class loads on
every supported version.

**A constant is null when the running server exposes neither spelling.** You must null-check before passing it
to `getAttribute`. This is BAC's real usage:

```java
AttributeInstance maxHealthAttr = player.getAttribute(CompatAttributes.MAX_HEALTH);
if (maxHealthAttr == null) {
    return;
}
```

## CompatItemMeta

`@ApiStatus.NonExtendable`, final, two static methods.

| Method | Behaviour |
| --- | --- |
| `isSupported()` | `true` when the running server has `ItemMeta.setItemModel`. |
| `setItemModel(ItemMeta meta, NamespacedKey key)` | Applies the `item_model` component, or does nothing. |

`ItemMeta.setItemModel` exists only on Minecraft 1.21.4 and newer. On 1.21 / 1.21.1 the method is absent, so
the call is routed through reflection resolved once into a static field and skipped when unavailable. That
lets you compile against and run on 1.21 while still honouring the component on newer servers.

`setItemModel` is a **silent no-op** in three cases: the method is unavailable, `meta` is null, or `key` is
null. It also swallows `ReflectiveOperationException`. It never throws and never reports failure, so if the
model must be visible you cannot detect the miss from the return value — there isn't one.

BAC's real call site guards only the key parsing and lets the no-op absorb the version difference, setting
custom model data independently so older servers still have something to render:

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

`isSupported()` is there for when you need to branch to a genuinely different fallback instead. No current
consumer calls it — every real call site relies on the silent no-op.

## TooltipUtils

Final, no `@ApiStatus` annotation, one static method:

```java
public static void hideDurabilityLine(Item wrapped)
```

Note the parameter type: this is CraftEngine's `net.momirealms.craftengine.core.item.Item` wrapper, **not**
Bukkit's `ItemStack`. Callers are already in CraftEngine territory when they need this, so the signature does
not pretend otherwise. Wrap a stack with `BukkitItemManager.instance().wrap(stack)` first.

The method adds `minecraft:damage` and `minecraft:max_damage` to
`minecraft:tooltip_display.hidden_components`, merging with any pre-existing hidden entries rather than
overwriting them. Use it when an item's damage value encodes something other than tool wear — a fill bar, a
serving count — so the player is not shown a meaningless "Durability: X / Y" line.

Mutation is applied to the wrapper, which writes through to the stack you wrapped. Clone first if the caller's
stack must stay untouched. FDAddonTemplate shows the whole pattern, encoding a fill level into the damage bar:

```java
ItemStack copy = stack.clone();
Item wrapped = BukkitItemManager.instance().wrap(copy);
wrapped.maxDamage(capacity);
wrapped.damage(Math.max(1, capacity - Math.min(capacity, amount)));
TooltipUtils.hideDurabilityLine(wrapped);
return copy;
```

The `Math.max(1, ...)` is load-bearing: vanilla hides the damage bar entirely at damage 0, so a completely
full meter would show no bar at all without the clamp.

## Related pages

* [Getting started](getting-started.md)
* [Debug tools](debug-tools.md)
* [Items](items.md)
