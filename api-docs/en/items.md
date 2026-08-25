# Items

Package: `com.huidu.farmersdelight.api.item`
Class: `FarmersDelightItems` — `final`, private constructor, `@ApiStatus.NonExtendable`.

Item helpers that resolve and match stacks across CraftEngine custom items, vanilla materials, and item
tags. Signatures use only Bukkit / Adventure / `java` types, so an addon does not need CraftEngine on its
compile classpath to use them.

This class lives under `com.huidu.farmersdelight.api.**`, the only package kept name-stable through
obfuscation, so addons may call it directly.

## Identity

```java
public static String       idOf(ItemStack item);
public static ItemStack    create(String itemId);
public static boolean      matchesId(ItemStack item, String itemId);
public static Set<String>  idsOf(ItemStack item);
public static boolean      isCustomItem(ItemStack item);
public static String       customIdOf(ItemStack item);
```

`idOf` — the CraftEngine custom id if the stack has one, else `minecraft:<material>` lowercased, else
`null` for a null/air stack.

`create` — builds a stack from a namespaced id. Tries CraftEngine's custom item registry first, then the
Bukkit material registry. Returns `null` when the id resolves to neither.

`matchesId` — true when `item` resolves to `itemId`. Case-insensitive on the id. Important semantic: a
CraftEngine custom item is identified **only** by its custom id, never by its base vanilla material, so
a custom item whose base is `minecraft:bowl` does *not* match `"minecraft:bowl"`.

`idsOf` — every namespaced id the stack resolves to: the custom id and/or the vanilla material id. An
empty set for null/air.

`isCustomItem` / `customIdOf` — whether the stack is a CraftEngine custom item, and its custom id
(`null` for a plain vanilla material or an empty stack).

This is how the real addons wrap it. BrewinAndChewin's `BrewinItems` is a thin delegation layer plus a
cache:

```java
import com.huidu.farmersdelight.api.item.FarmersDelightItems;

public static String idOf(ItemStack item) {
    return FarmersDelightItems.idOf(item);
}

public static ItemStack create(String itemId) {
    return FarmersDelightItems.create(itemId);
}
```

Building a CraftEngine item re-parses its MiniMessage name, which is measurable on a per-tick path.
BrewinAndChewin memoizes the built stack by id and hands out clones, clearing the cache on reload so
edited CraftEngine definitions are picked up:

```java
private static final Map<String, ItemStack> CACHE = new ConcurrentHashMap<>();

public static ItemStack createCached(String itemId) {
    if (itemId == null) {
        return null;
    }
    ItemStack base = CACHE.get(itemId);
    if (base == null) {
        base = FarmersDelightItems.create(itemId);
        if (base == null) {
            return null;
        }
        CACHE.put(itemId, base);
    }
    return base.clone();
}
```

Copy that pattern only for genuinely hot paths; `create` is fine for one-off calls.

## Tags

```java
public static boolean      matchesTag(ItemStack item, String tagId);
public static Set<String>  tagIdsOf(ItemStack item);
```

`matchesTag` — true when `item` carries the given tag. Accepts either `#ns:tag` or `ns:tag`; a leading
`#` is stripped. It checks **both** CraftEngine custom item tags and vanilla item tags.

`tagIdsOf` — verified at the implementation (`ItemUtils.getItemTagIds`): this returns **only the
CraftEngine custom tags** the item carries. Vanilla tags are not included, despite what a casual reading
of the name suggests. If you need a vanilla-tag decision, use `matchesTag`, which does consult vanilla
tags. Returns an empty set for null/air.

```java
if (FarmersDelightItems.matchesTag(tool, "#farmersdelight:tools/knives")) {
    // works for both a CraftEngine custom tag and a vanilla tag of that id
}
```

## Crafting remainder

```java
public static ItemStack craftingRemainderOf(ItemStack item);
```

The remainder left by a single `item` — a bucket from a milk bucket, a glass bottle from a honey bottle,
or a CraftEngine-configured container return. `null` when the item leaves none.

The resolution order is: CraftEngine container-return mapping for custom items, then the vanilla
`Material.getCraftingRemainingItem()`, then the milk/water/lava bucket and honey-bottle special cases.
This mirrors what FarmersDelight's own stations do, so addon recipes return containers consistently with
the cooking pot.

## Display names

Four different renderings, and choosing wrong is a real bug — a name frozen to one player's locale
persisted onto an item everyone can see.

```java
public static Component displayNameOf(ItemStack item, Player player);
public static Component translatableDisplayNameOf(ItemStack item);
public static Component translatableDisplayNameOfNoAnvilOf(ItemStack item);
public static Component serverDisplayNameOf(ItemStack item);
```

| Method | Resolved | Anvil rename | Use for |
| --- | --- | --- | --- |
| `displayNameOf(item, player)` | for that one player's locale (`player` may be null) | honoured | text sent to a single viewer right now |
| `translatableDisplayNameOf(item)` | `Component.translatable(key)` with a server-resolved `.fallback(...)` | honoured as-is | lore persisted on a stack, or a broadcast to many viewers |
| `translatableDisplayNameOfNoAnvilOf(item)` | same, but ignores anvil renames | ignored | persisted lore that must follow each viewer's language and must not freeze to a player's anvil typo |
| `serverDisplayNameOf(item)` | fully baked on the server, in the server's default locale, never translatable | ignored | text that must render identically for every viewer regardless of client locale or pack state |

The `.fallback(...)` on the two translatable variants matters: a client whose resource pack lacks the
lang entry sees readable text in the server's default locale rather than the raw `item.ns.id` key.

Note the method name `translatableDisplayNameOfNoAnvilOf` — the doubled `Of` suffix is the actual name
in the source, not a typo in this document.

## Rendering names and lore onto a stack

```java
public static void      applyDisplay(ItemStack item, String nameTemplate, List<String> loreTemplates,
                                     Player viewer, Map<String, String> placeholders);
public static ItemStack buildIcon(String itemId, String nameTemplate, List<String> loreTemplates,
                                  Player viewer, Map<String, String> placeholders);
```

`applyDisplay` sets the display name and lore from templates rendered through `FarmersDelightText` —
string and `Component` placeholders, `<l10n:>` tags, CraftEngine glyphs, MiniMessage plus legacy colour
codes — and strips the vanilla lore italic from the name. A `null` `nameTemplate` or `loreTemplates`
leaves that part untouched. A `null` `item`, or a stack with no `ItemMeta`, is a silent no-op.

Use it so your addon's tooltips and GUI icons share FarmersDelight's rendering rather than diverging.

`buildIcon` is `create(itemId)` followed by `applyDisplay`, returning `null` when `itemId` cannot be
resolved.

```java
ItemStack icon = FarmersDelightItems.buildIcon(
        "farmersdelight:cooking_pot",
        "<l10n:myaddon.gui.pot_title>",
        List.of("<l10n:myaddon.gui.pot_lore>"),
        viewer,
        Map.of("count", String.valueOf(count)));
```

Threading: `applyDisplay` and `buildIcon` only touch the `ItemStack` you hand them, but they read
FarmersDelight's translation state. Call them on the thread you will use the resulting stack from; do
not race a `/fd reload` from an async task.

## Related pages

* [Text and messages](text-and-messages.md)
* [Blocks and stations](blocks-and-stations.md)
* [Version compatibility helpers](compat-utilities.md)
