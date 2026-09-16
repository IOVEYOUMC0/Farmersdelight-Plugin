
[简体中文](../zh-cn/advancements.md)
# Advancements

Package: `com.huidu.farmersdelight.api.advancement`

| Type | Kind | `@ApiStatus` |
| --- | --- | --- |
| `FarmersDelightAdvancements` | static facade, `final`, private constructor | `@NonExtendable` |
| `AdvancementTree` | fluent builder, `final`, package-private constructor | none |

Stable, addon-facing access to FarmersDelight's UltimateAdvancementAPI (UAA) integration. Two distinct
uses:

1. Grant, revoke and check **FarmersDelight's own** built-in advancements by id.
2. Register **your own advancement tab** from plain data, then grant and check it with the
   `tabId`-prefixed overloads.

**No UAA types cross this boundary.** The builder and the definition objects reference only Bukkit and
`java` types, so your addon compiles and loads even when UAA is absent. FarmersDelight performs the
UAA-bound build later, and rebuilds it across `/fd reload`.

`AdvancementTree` has a package-private constructor — you obtain one only from
`FarmersDelightAdvancements.tree(String)`, never with `new`.

## Availability

CE packs can supply `advancements.yml` at the pack root or inside a namespace directory. When no node declares `x` / `y`, FD lays out the tree from its `parent` relationships using the patched UAA's vanilla layout algorithm. If coordinates are present, the manual layout is preserved. Java `AdvancementTree` callers retain their supplied coordinates.

```java
public static boolean isAvailable();
```

True when the UltimateAdvancementAPI plugin is installed **and enabled**, and FarmersDelight's
advancement system is enabled. Every other method on the facade is a null-safe no-op when this is false
(queries return `false`, void methods do nothing), so guarding is about logging a clear message rather
than about avoiding a crash.

Both real addons open with the same shape:

```java
if (!FarmersDelightAdvancements.isAvailable()) {
    log.info("Advancements disabled (UltimateAdvancementAPI not loaded).");
    return false;
}
```

That is a normal soft-dependency no-op, not an error.

## FarmersDelight's own tab

```java
public static void    award(Player player, String advancementId);
public static void    awardCriteria(Player player, String advancementId, String criterion);
public static void    revoke(Player player, String advancementId);
public static boolean has(Player player, String advancementId);
public static void    showFarmersDelightTab(Player player);
```

`advancementId` is the bare id, without a namespace or tab prefix. The built-in tab is all 23 nodes below —
this is the complete list, in the order `AdvancementManager` declares them:

`root`, `craft_knife`, `place_campfire`, `get_fd_seed`, `get_ham`, `harvest_straw`,
`place_organic_compost`, `use_cutting_board`, `obtain_netherite_knife`, `use_skillet`, `place_skillet`,
`place_cooking_pot`, `place_feast`, `master_chef`, `eat_nourishing_food`,
`hit_raider_with_rotten_tomato`, `get_mushroom_colony`, `plant_rice`, `plant_all_crops`,
`get_organic_compost`, `get_rich_soil`, `hoe_rich_soil`, `harvest_ropelogged_tomato`.

**Two** of them are multi-task advancements, not one:

- `master_chef` — its criteria are the 27 FarmersDelight dish item names (`mixed_salad`, `cooked_rice`,
  `beef_stew`, … `gleaming_salad`), each granted by eating that dish.
- `plant_all_crops` — its criteria are the 19 crop names (`wheat`, `beetroot`, `carrot`, `potato`, `cabbage`,
  `tomato`, `onion`, `rice`, `melon`, `pumpkin`, `sweet_berries`, `sugar_cane`, `kelp`, `cocoa`,
  `nether_wart`, `chorus_flower`, `brown_mushroom`, `red_mushroom`, `glow_berries`), each granted by planting
  it. Its vanilla subtasks are what keep it obtainable regardless of what happens to the FarmersDelight
  crops.

That matters for the two rules below: **both** of these expand to all their subtasks when awarded by id, and
`awardCriteria` is the way to grant a single one of either.

Awarding any non-root advancement auto-grants the tab root first, so a player who somehow lacks it does
not end up with an orphaned node.

Awarding a **multi-task** advancement by its id grants *every* one of its tasks. To grant a single
subtask, use `awardCriteria`.

An unknown `advancementId` is ignored; FarmersDelight logs it only when its debug mode is on.

## Building an addon tab

```java
public static AdvancementTree tree(String tabId);
public static void            unregister(String tabId);
```

`tree(tabId)` begins a new tree. Re-registering the same `tabId` replaces the previous one. `unregister`
removes the definitions and disposes the built UAA tab — call it from `onDisable`.

### The builder

```java
public AdvancementTree root(String id, ItemStack icon, String title, String description, String background);

public AdvancementTree advancement(String id, String parentId, ItemStack icon, String title,
                                   String description, String frame, float x, float y);

public AdvancementTree multiTask(String id, String parentId, ItemStack icon, String title,
                                 String description, String frame, float x, float y, List<String> criteria);

public AdvancementTree requires(String advancementId, String... craftEngineIds);
public AdvancementTree requiresCriterion(String advancementId, String criterion, String... craftEngineIds);

public boolean register();
```

* **Exactly one root.** `root` is placed at grid position 0,0 with frame `task`; `background` is a
  texture path, and `null` means a default.
* `frame` is one of `"task"`, `"goal"`, `"challenge"`.
* `x` / `y` are `float` grid coordinates — passing int literals is fine, they widen.
* `multiTask` completes when every named criterion has been granted.
* `register()` returns `false` when there is no root, when FarmersDelight is unavailable, or when the
  build fails. It returns `true` when the tree was accepted — note that "accepted" includes the case
  where the advancement system is not up yet: the definitions are stored and the tab is built once the
  system becomes ready.

### Translation keys

Titles and descriptions are client translation keys resolved from the client's resource pack, or
literal text which clients render verbatim when the key is unknown. Both addons use the convention
`<tab-id>.advancement.<id>` and `<tab-id>.advancement.<id>.desc`. Add matching entries to your lang
file or the keys render as-is.

### A real tree

Trimmed from BrewinAndChewin's `BrewinAdvancements`:

```java
import com.huidu.farmersdelight.api.advancement.AdvancementTree;
import com.huidu.farmersdelight.api.advancement.FarmersDelightAdvancements;

public static final String TAB_ID = "brewinandchewin";

AdvancementTree tree = FarmersDelightAdvancements.tree(TAB_ID)
        .root(ROOT, icon("brewinandchewin:beer", Material.HONEY_BOTTLE),
                prefix(ROOT), prefix(ROOT) + ".desc",
                "minecraft:textures/block/spruce_planks.png")
        .advancement(PLACE_KEG, ROOT,
                icon("brewinandchewin:keg", Material.BARREL),
                prefix(PLACE_KEG), prefix(PLACE_KEG) + ".desc", "task", 1, 0)
        .advancement(BREW_DRINK, PLACE_KEG,
                icon("brewinandchewin:vodka", Material.POTION),
                prefix(BREW_DRINK), prefix(BREW_DRINK) + ".desc", "task", 2, 0)
        .multiTask(CRAFTING_PROBLEM, BREW_DRINK,
                icon("brewinandchewin:steel_toe_stout", Material.POTION),
                prefix(CRAFTING_PROBLEM), prefix(CRAFTING_PROBLEM) + ".desc",
                "challenge", 3, 0, CRAFTING_PROBLEM_DRINKS);

boolean ok = tree.register();

private static String prefix(String key) {
    return "brewinandchewin.advancement." + key;
}
```

The icon helper both addons use falls back to a vanilla `Material` when the CraftEngine id will not
resolve — worth copying, since a server may have deleted the item from its pack:

```java
import com.huidu.farmersdelight.api.item.FarmersDelightItems;

private static ItemStack icon(String ceId, Material fallback) {
    ItemStack stack = ceId == null ? null : FarmersDelightItems.create(ceId);
    return stack != null && !stack.getType().isAir() ? stack : new ItemStack(fallback);
}
```

A caveat recorded in BrewinAndChewin's own source: a negative `y` (used to place a node above its
parent, matching vanilla layout) requires a UAA build that permits it — stock UltimateAdvancementAPI
rejects `y < 0` in `AdvancementDisplay`.

## Content requirements

The problem: a server owner deletes an item from the CraftEngine pack, and an advancement that could
only be earned with that item sits in the tab forever, unobtainable.

```java
public AdvancementTree requires(String advancementId, String... craftEngineIds);
public AdvancementTree requiresCriterion(String advancementId, String criterion, String... craftEngineIds);
```

`requires` declares which CraftEngine content an advancement depends on. When **every** listed id is
gone from the CraftEngine configuration, the advancement is left out of the built tab and its children
are re-attached to the nearest surviving ancestor. Any id still loaded as either an item **or** a block
satisfies the requirement.

`requiresCriterion` does the same for one criterion of a multi-task advancement: when its ids are all
gone, that criterion is dropped so the remaining ones can still complete the advancement. Without it, a
single deleted item leaves the advancement permanently one subtask short.

Rules and guardrails:

* Both are optional and additive. An advancement or criterion with no declared requirement is always
  shown, which is how everything behaves if you never call these.
* Declare only ids whose absence genuinely makes the advancement impossible. Over-declaring hides
  content that still works.
* Calling `requires` again for the same advancement **replaces** the previous list.
* The tab root is always kept and ignores any requirement.
* If every criterion of an advancement would be dropped, the full list is kept instead — an advancement
  with no criteria at all cannot be registered — and a warning is logged.
* A stray `null` or blank in the varargs is filtered out and cannot break registration.
* Server owners have the final say: `auto-disable-missing` in FarmersDelight's `config.yml` turns the
  whole mechanism off, and its `force-enable` / `force-disable` lists (entries written either as the
  advancement id or as `tabId:id`) override individual decisions.

BrewinAndChewin's declaration, showing both the "whole category" and the "one-to-one criterion" shapes:

```java
tree.requires(PLACE_KEG, BrewinConstants.BLOCK_KEG)
        .requires(BREW_DRINK, FERMENTED_DRINKS.toArray(new String[0]))
        .requires(CRAFTING_PROBLEM, namespaced(CRAFTING_PROBLEM_DRINKS));

for (String drink : CRAFTING_PROBLEM_DRINKS) {
    tree.requiresCriterion(CRAFTING_PROBLEM, drink, NAMESPACE + drink);
}
```

The category lists only disappear when the entire category is gone, while each criterion is tied to its
own single item.

## Granting on an addon tab

```java
public static void    award(String tabId, Player player, String advancementId);
public static void    awardCriteria(String tabId, Player player, String advancementId, String criterion);
public static void    revoke(String tabId, Player player, String advancementId);
public static boolean has(String tabId, Player player, String advancementId);
public static void    showTab(String tabId, Player player);
```

Same semantics as the FarmersDelight-tab methods: awarding a non-root advancement auto-grants the root
first, awarding a multi-task id grants all of its tasks, and unknown ids are ignored. `revoke` on a
multi-task id symmetrically revokes all of its tasks, and does not touch the root.

All five resolve the built tab and no-op when it is not currently loaded — which includes the window
after `register()` returned `true` but before the advancement system became ready, and the window while
the system is down. A grant issued in that window is silently lost, so drive awards from gameplay
events rather than from startup code.

Both addons wrap these so callers never repeat the tab id:

```java
public static void award(Player player, String advancementId) {
    FarmersDelightAdvancements.award(TAB_ID, player, advancementId);
}

public static void awardCriterion(Player player, String advancementId, String criterion) {
    FarmersDelightAdvancements.awardCriteria(TAB_ID, player, advancementId, criterion);
}

public static boolean has(Player player, String advancementId) {
    return FarmersDelightAdvancements.has(TAB_ID, player, advancementId);
}
```

## Lifecycle and reload

Definitions you register persist. The actual UAA tabs are built only while the advancement system is
ready — after CraftEngine items load and UAA is enabled — and are disposed and rebuilt when it goes down
and comes back.

On a rebuild, FarmersDelight re-shows every rebuilt tab to players already online (re-granting the root
and re-showing the tab), so the tab does not vanish from their client until they rejoin.

Practically, for an addon:

* Call `register()` once from `onEnable`, after FarmersDelight is ready. You do not need to re-register
  on `/fd reload`.
* Call `unregister(tabId)` from `onDisable`.

## Threading

Every award, revoke and check ultimately calls into UltimateAdvancementAPI, and the facade adds no
scheduling of its own — your call runs on whatever thread you make it on.

UNKNOWN: the source does not state UAA's own threading contract. The safe assumption is the standard
one for player-facing Bukkit state — call from the main thread on Paper, and from the player's region
thread on Folia. The facade does catch and swallow exceptions from the underlying grant calls
(best-effort), so a threading violation may surface as a silently missing advancement rather than as a
stack trace.

## Related pages

* [Items](items.md)
* [Recipe discovery](recipe-discovery.md)
* [Getting started](getting-started.md)
