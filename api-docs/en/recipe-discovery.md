# Recipe discovery

Recipe discovery is per-player lock/unlock state for the recipe books. When it is enabled, recipes start locked
and are revealed as a player unlocks them — by obtaining an ingredient or result, by an admin command, or
through this API. It is **disabled by default**, and while disabled every recipe reads as unlocked and every
call here is a harmless no-op.

Locking affects **display only**. A locked recipe can still be crafted at the station; the book just does not
show it.

## The storage key — read this before shipping ids

Unlock state is stored per player as a set of strings, each one a type id and a recipe id joined by a single
space:

```
farmersdelight:cooking_pot farmersdelight:beef_stew
brewinandchewin:keg strong_ale
```

The file is `plugins/FarmersDelight/recipe-discovery.yml`, keyed by player UUID, each value a list of those
strings.

Three consequences an addon author has to internalise:

1. **A recipe id is a save key. Renaming a recipe id orphans every player's unlock of it.** The old key stays in
   the file, matching nothing; the recipe reappears locked for everyone who had already discovered it. There is
   no migration path in the API. Choose ids you can live with, and if you must rename, expect to write your own
   fix-up pass over the file or to accept the loss.
2. **The same is true of a `RecipeType` id.** Renaming the type orphans every unlock of every recipe in it.
3. **Neither id may contain a space.** The key is split at the first space, and per-type lookups match on the
   `"<typeId> "` prefix. A space in a type id silently mangles every key it produces.

Keys of types that are not currently registered are deliberately **preserved**, not pruned — removing an addon
for one restart does not wipe its players' progress.

One more storage nuance for the cooking pot: the unlock key is id-only, so a cooking-pot recipe id declared by
several custom recipe groups shares a single unlock bit across all of them.

## The API

`com.huidu.farmersdelight.api.recipe.FarmersDelightRecipeDiscovery` — final, static-only.

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

Address FarmersDelight's own recipes with the two `TYPE_` constants; address your own with your `RecipeType.id()`
plus a `ViewableRecipe.id()`.

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

### Method semantics

`isEnabled()` — true when discovery is switched on in the config. `false` means every recipe reads as unlocked.

`isUnlocked(player, typeId, recipeId)` — returns `true` when discovery is disabled, and `true` for a `null`
player. It answers "may this player see it", not "is there a stored entry".

`unlock(...)` — returns `true` only when the recipe was **newly** unlocked. A redundant unlock returns `false`,
changes nothing and fires nothing.

`lock(...)` — re-locks. Returns `void` here; a redundant lock is likewise a no-op.

`unlockAll(player)` — unlocks every known recipe of every registered type, FarmersDelight's own included, and
returns how many were newly unlocked.

`unlockedOf(player, typeId)` — the recipe ids of that type the player has stored as unlocked. Careful: this
reads stored state directly and does **not** consult `isEnabled()`. With discovery disabled, `isUnlocked`
returns `true` for everything while `unlockedOf` may return an empty set. Do not use `unlockedOf(...).contains(...)`
as a substitute for `isUnlocked`.

`triggerObtain(player, itemId)` — treats the item id as just obtained, unlocking any recipe keyed to it. Use it
for your own "you got this" flows. It is a no-op unless discovery is enabled *and* the config's obtain trigger
is on.

The obtain index is built from each recipe's result and its **exact-item** ingredients. Tag ingredients are
deliberately excluded — a tag is far too broad to drive an auto-unlock. For addon types the index uses
`ViewableRecipe.result()` and `ViewableRecipe.inputs()`, and it is rebuilt whenever a `RecipeType` is registered
or unregistered.

### Threading

The stored state is concurrent-map work, so these methods may be called from an async callback and will still
apply and return normally. The **event** is what constrains you: Bukkit rejects synchronous event dispatch from
an async thread, so when a change is made off a server thread the finished event is handed to the global region
instead of being fired inline. Nothing breaks either way, but a listener will see the event slightly later than
the state change.

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

Fired when a player's discovery state **really** changes. Every real state change fires it — from this API, from
the obtain trigger, or from the admin command — so an addon can react to discoveries it did not itself request.

- **Not cancellable.** It extends `Event` and does not implement `Cancellable`; there is no `setCancelled` to
  call and no call site that would honour one. The state is already committed when listeners run. A veto belongs
  upstream, before the unlock is requested.
- **Exactly one event per transition.** Redundant unlocks and redundant locks fire nothing. Unlocking or locking
  a whole type at once fires one event per recipe that actually moved.
- **Threading.** Dispatched on whichever thread performed the change, with none of the discovery manager's locks
  held: the region thread owning the player for the obtain trigger, the command thread for the admin command,
  the calling thread for the API. Treat it as region-local — act on the named player, not on unrelated world
  state.
- **The player may be offline.** The event carries a `UUID`, not a `Player`, because the command path can change
  stored state for a player who is not on the server. Always null-check `Bukkit.getPlayer(uuid)`.

Every accessor returns an immutable value (`UUID`, `String`, enum), so there is no mutable state a listener can
reach through and change.

## How discovery shows up in the book

Server config decides the display mode, and your `RecipeType` sees the consequences:

- **placeholder mode** — a locked recipe keeps its slot in the list but shows a configured locked icon;
  clicking it sends the player a "still locked" message instead of opening the detail page.
- **hidden mode** — locked recipes are removed from the list entirely, which shifts pagination: with 40 of 45
  recipes locked, your six-row book collapses to one page.

Both are handled by FarmersDelight. Your `ViewableRecipe` does not need to know anything about lock state, and
should not try to filter on it itself.
