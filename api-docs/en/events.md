# Events

Everything in `com.huidu.farmersdelight.api.event` is a plain Bukkit `org.bukkit.event.Event`. You subscribe
with an ordinary `@EventHandler` method and register the listener in your own `onEnable`:

```java
getServer().getPluginManager().registerEvents(new MyFarmersDelightListener(), this);
```

> **A note on the snippets on this page.** They show the api call *in context*, not compilable files. Method
> signatures, event type names, getter names and return types are exact and can be relied on. Identifiers that
> are plainly yours — `MyFarmersDelightListener`, the `myPlugin` field passed to `player.getScheduler().run`,
> `recordHarvest(...)`, `plugin.reloadAddon()` and the like — are placeholders standing in for your addon's
> own fields and methods; they are not declared anywhere in the snippet and there is no api member behind
> them. Imports are omitted except where a snippet exists to show
> which package a type comes from. Substitute your own references and the surrounding boilerplate.

**None of these eleven events is cancellable.** Not one of them implements `org.bukkit.event.Cancellable`, so
there is no `setCancelled` to call and no fire site that could honour it. They are notification and
accumulation hooks. The only ways to influence what FarmersDelight does are the accumulators on
`FarmersDelightCleanupEvent` / `FarmersDelightMigrateEvent` and the protective set on
`FarmersDelightCollectLiveDisplaysEvent`.

Ten of the eleven are annotated `@ApiStatus.NonExtendable`: do not subclass them and do not construct them to
fake a FarmersDelight action. Constructing one to report *your own* station's activity is a different matter
and is explicitly supported for `FarmersDelightProduceEvent` and `ProfessionCookingExperienceEvent` — that is
how Brewin' And Chewin' reports keg output. `FarmersDelightRecipeDiscoveryEvent` carries no `@ApiStatus`
annotation at all, but treat it as equally closed; nothing in the plugin expects a foreign subclass.

## Summary

| Event | Fires when | Cancellable |
| --- | --- | --- |
| `FarmersDelightBuffChangeEvent` | A registered custom buff's level on a player really changes (gained / lost / level moved) | No |
| `FarmersDelightCleanupEvent` | `/fd cleanup`, after FarmersDelight swept its own orphans | No (accumulates a count) |
| `FarmersDelightCollectLiveDisplaysEvent` | `/fd cleanup`, *before* the orphan display sweep runs | No (accumulates protected ids) |
| `FarmersDelightCookStartEvent` | A cooking pot goes from no matched recipe to a matched one | No |
| `FarmersDelightHarvestEvent` | A mushroom colony is sheared/knifed, or mature rice is harvested | No |
| `FarmersDelightMigrateEvent` | **Never — no fire site exists in the codebase** | No (accumulates a count) |
| `FarmersDelightProduceEvent` | A station hands a produced item to a player | No |
| `FarmersDelightRecipeDiscoveryEvent` | A player's recipe-discovery state really changes (unlock / re-lock) | No |
| `FarmersDelightReloadEvent` | A FarmersDelight reload finishes | No |
| `FarmersDelightWarmupEvent` | CraftEngine items are ready and FarmersDelight has warmed its caches | No |
| `ProfessionCookingExperienceEvent` | A station credits a player with cooking experience | No |

---

## FarmersDelightBuffChangeEvent

`com.huidu.farmersdelight.api.event.FarmersDelightBuffChangeEvent` — `@ApiStatus.NonExtendable`

### When it fires

One fire site: `CustomBuffRegistry.syncState(Player, CustomBuff)`, which diffs the buff's current level against
a last-known-level cache and only fires when the number actually moved — gained (0 to N), lost (N to 0), or
shifted between two non-zero levels. A refresh that pushes the duration out without changing the level fires
**nothing**. That diff is what makes it safe to call `syncState` after every mutation, even every tick, without
turning the event into a firehose.

The registry's own mutating paths (`apply`, `clearAll`, `clearOne`, `restoreAll`, the admin grant, the milk
bucket and milk bottle wipes) sync themselves. If you keep your own buff state outside the registry, call
`CustomBuffRegistry.syncState(player, buffId)` after mutating it or the transition is never reported.

### What it carries

`getPlayerId()` `UUID`, `getPlayerName()` `String` (may be null), `getBuffId()` `String`,
`getPreviousLevel()` `int`, `getNewLevel()` `int`, `getRemainingSeconds()` `int`, plus the convenience
predicates `isGained()` and `isLost()`. Levels are 1-based: level 1 is the baseline strength, equivalent to
potion amplifier 0. `getRemainingSeconds()` is 0 when the buff was lost or reports no duration.

### Cancellation and threading

Not cancellable — the buff already changed before the event is built.

Threading is worth reading carefully, because the fire site does more than the class javadoc suggests. In
`CustomBuffRegistry.fireChange`:

- If `Bukkit.isPrimaryThread()` the event is dispatched **inline, on the calling thread**.
- Otherwise it is handed to FarmersDelight's scheduler (`plugin.scheduler().run(...)`), which on Folia lands on
  the **global region**, not the player's region. This exists because Bukkit throws when a synchronous event is
  dispatched off a tick thread, and the diff cache has already consumed the transition by then — dropping the
  event would lose it permanently.
- If the plugin is down there is no scheduler to hand it to, so it is dispatched inline as a last resort.

Two consequences. First, do not assume you are on the affected player's region thread; resolve the player from
`getPlayerId()` and use `player.getScheduler()` for anything region-sensitive. Second, **a listener exception is
swallowed** — `callChange` catches `RuntimeException` so a broken listener cannot roll back a buff change that
already happened. Your handler will fail silently, so log your own errors.

### Example

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
            return; // may be offline, or we may be on a foreign thread
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

Nothing in FarmersDelight, Brewin' And Chewin' or ExpandedDelight currently listens to this event; it exists
purely for third parties.

---

## FarmersDelightCleanupEvent

`com.huidu.farmersdelight.api.event.FarmersDelightCleanupEvent` — `@ApiStatus.NonExtendable`

### When it fires

One fire site: `FarmersDelightCommand.executeCleanup`, the handler for `/fd cleanup`. It fires **last**, after
FarmersDelight has swept its own orphan proxy item-displays and invalid auto-trays, so by the time you run the
plugin's own numbers are already settled.

### What it carries

Only an accumulator. `addRemoved(int count)` adds to an internal `AtomicInteger` (values `<= 0` are ignored);
`getRemoved()` reads the sum. The command reads `getRemoved()` immediately after dispatch and folds the number
into the `addon` and `count` placeholders of its reply message.

### Cancellation and threading

Not cancellable. It arrives on the command thread — the thread `/fd cleanup` was executed on, which for console
and player commands is the main/global thread.

Treat it as a one-shot admin signal, never as a periodic tick. On Folia you may do best-effort regional
scheduling: the contract explicitly allows the reported count to mean "scheduled for removal" rather than
"removed before this method returned", because the command reads the total synchronously.

### Example

Modelled on `ExampleFarmersDelightEventsListener` in the addon template:

```java
import com.huidu.farmersdelight.api.event.FarmersDelightCleanupEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

public final class MyCleanupListener implements Listener {

    @EventHandler
    public void onCleanup(FarmersDelightCleanupEvent event) {
        int removed = 0;
        // Sweep your own orphan entities or stale records here, incrementing for each.
        event.addRemoved(removed);
    }
}
```

---

## FarmersDelightCollectLiveDisplaysEvent

`com.huidu.farmersdelight.api.event.FarmersDelightCollectLiveDisplaysEvent` — `@ApiStatus.NonExtendable`

### When it fires

One fire site: `FarmersDelightCommand.executeCleanup`, but **before** the sweep, not after. The command builds
the live-id set with `plugin.collectLiveDisplayIds()`, fires this event so addons can add their own ids, and
only then calls `displayManager.cleanupOrphans(liveIds)`. It is the protective counterpart to
`FarmersDelightCleanupEvent`: that one reports removals, this one prevents them.

Both events fire during the same `/fd cleanup` invocation, this one strictly first.

### What it carries

`addLiveId(int entityId)` and `addLiveIds(Collection<Integer> entityIds)`. The event wraps FarmersDelight's own
live-id set directly — it is not a copy. **Only ever add.** There is no removal method, and clearing or
mutating the set through any other route would cause FarmersDelight to delete its own in-use displays.

Any addon that owns packet item-displays created through `FarmersDelightApi.createItemDisplay` **must** listen
here. Without it, `/fd cleanup` removes your displays as orphans.

### Cancellation and threading

Not cancellable. Command thread, same as `FarmersDelightCleanupEvent`.

### Example

This is Brewin' And Chewin's real listener, protecting the item-displays shown on a coaster
(`CoasterLifecycleListener`):

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

The addon template does the same from its display manager with
`event.addLiveIds(List.copyOf(handles.values()))`.

---

## FarmersDelightCookStartEvent

`com.huidu.farmersdelight.api.event.FarmersDelightCookStartEvent` — `@ApiStatus.NonExtendable`

### When it fires

One dispatch point, `CookingPotBlockEntity.firePendingCookStart`, reached from two callers: `canCook()` and
`finishCooking(World, Location)`. Both record the match while holding the pot's monitors and then call the
dispatcher after releasing every lock. A pending slot (`AtomicReference.getAndSet(null)`) guarantees the recipe
is announced exactly once even when both paths run in the same tick.

This is an **edge, not a state**. It fires on the idle-to-cooking transition only:

- A pot bubbling away on the same batch for a thousand ticks produces exactly one event.
- Swapping ingredients straight from one valid recipe to another valid recipe is a recipe *change*, not a
  start, and is not reported. The pot must fall idle (no match) before the next match counts.
- Finishing a batch clears the match, so a pot with ingredients for a second batch fires again for that batch.

The dispatch is skipped entirely when the plugin is disabled (`FarmersDelightPlugin.isEnabled0()`).

The counterpart at the other end of a batch is `FarmersDelightProduceEvent`, fired when a player takes the
finished meal.

### What it carries

`getLocation()` `Location` (block-aligned corner, a fresh clone each call; null only if the pot has no world
yet), `getRecipeId()` `String`, `getResult()` `ItemStack` (a clone, may be null), `getCookTimeTicks()` `int`.

`getCookTimeTicks()` is the ticks the recipe needs from empty progress. The pot only advances while heated, so
real time to completion is at least this and usually more.

### Cancellation and threading

Not cancellable — by the time the transition is observable the pot has already matched.

Fired on the region thread owning the pot, and — importantly — **outside the pot's block-entity lock**, which
is deliberate: a listener may read and even mutate that pot without deadlocking. Go through
`com.huidu.farmersdelight.api.block.FarmersDelightBlocks.cookingPot` rather than reaching into internals.

### Example

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

---

## FarmersDelightHarvestEvent

`com.huidu.farmersdelight.api.event.FarmersDelightHarvestEvent` — `@ApiStatus.NonExtendable`

### When it fires

Two fire sites, with meaningfully different payloads:

1. **`MushroomColonyBehavior.useOnBlock`** — right-clicking a mushroom colony with shears or a knife. Fires
   after the protection check and after the block state has been stepped down, but **before** the drop is
   spawned, so a listener sees the harvest once with the final drop amount. `getDrops()` holds one stack.
2. **`TallCropBlockBehavior.useOnBlock`** — harvesting mature rice (the upper half, with a valid harvest tool,
   when the block is configured `resetOnHarvest`). Fires after the protection check and before any drop is
   spawned. `getDrops()` is **empty**, because the loot comes from a CraftEngine loot table or a configured
   break-loot function chain and never passes through FarmersDelight as a list.

Both sites fire only after `ProtectionCompat` has approved the interaction — the mushroom site checks both
`canUse` and `canBuild` with `Feature.MUSHROOM_COLONY`, the rice site checks `canBuild` with `Feature.RICE`.

### Which harvests this does *not* cover

Tomatoes are not reported. Tomato plants are implemented entirely in CraftEngine YAML (an `on: right_click`
function chain) with no Java handler for this event to hook. To observe tomato harvests, listen to
CraftEngine's own `CustomBlockInteractEvent` and filter on the block id — `farmersdelight:tomatoes`,
`farmersdelight:budding_tomatoes`, `farmersdelight:tomato_crop_on_rope` — which is exactly what FarmersDelight's
own WorldGuard integration does for that block.

### What it carries

`getPlayerId()` `UUID`, `getPlayerName()` `String` (may be null), `getLocation()` `Location` (clone),
`getBlockId()` `String` (the CraftEngine id, e.g. `farmersdelight:brown_mushroom_colony`), `getTool()`
`ItemStack` (clone; null when harvested bare-handed), `getDrops()` `List<ItemStack>`.

`getDrops()` is **best-effort and unmodifiable**. An empty list means "not enumerable", not "no drops". The
list and every stack in it are copies; editing them changes nothing about what actually drops.

### Cancellation and threading

Not cancellable, and this is a deliberate design decision rather than an oversight. Vetoing a harvest belongs to
the protection layer: FarmersDelight asks its `ProtectionCompat` facade (WorldGuard flags plus AntiGriefLib's
24-plus land plugins) *before* every harvest, and this event fires only after that check passed. A land plugin
that wants to block harvesting should expose itself through that facade so the interaction is denied cleanly,
rather than listening here where the state change is already half-applied.

Both sites run on the interacting player's region thread, inside the CraftEngine block-behavior call, and
neither behavior holds a block-entity monitor.

### Example

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
            counted += drop.getAmount(); // empty for rice: drops are not enumerable there
        }
        recordHarvest(event.getPlayerId(), event.getBlockId(), counted);
    }
}
```

---

## FarmersDelightMigrateEvent

`com.huidu.farmersdelight.api.event.FarmersDelightMigrateEvent` — `@ApiStatus.NonExtendable`

### Status: no fire site

**A repo-wide search finds no code that constructs or dispatches this event.** The class is public API and
compiles, the addon template ships a commented example listener for it, but no FarmersDelight command path
currently fires it — the `/fd rug-migrate` action its javadoc names is not present in the command handler. A
listener you register today will never be invoked.

It is retained as a stable hook for future migrations. Registering a handler now is harmless and costs nothing,
and gating on `migrationKey()` means you will not react to a migration you do not own if and when one ships. Do
not build anything that *depends* on it firing.

### What it would carry

`migrationKey()` `String` — note there is no `get` prefix on this accessor — identifying which migration ran
(the javadoc's example value is `"rug"`). Plus the same `addRemoved(int)` / `getRemoved()` accumulator pair as
`FarmersDelightCleanupEvent`, intended to be summed into the migrate command's reply.

### Cancellation and threading

Not cancellable. Intended to arrive on the command thread as a one-shot admin signal, with the same Folia
best-effort allowance as the cleanup event. UNKNOWN in practice, since nothing fires it.

### Example

From the addon template:

```java
import com.huidu.farmersdelight.api.event.FarmersDelightMigrateEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

public final class MyMigrateListener implements Listener {

    @EventHandler
    public void onMigrate(FarmersDelightMigrateEvent event) {
        if (!"example".equals(event.migrationKey())) {
            return; // not a migration this addon owns
        }
        int migrated = 0;
        // Convert your own legacy data here, incrementing for each entry.
        event.addRemoved(migrated);
    }
}
```

---

## FarmersDelightProduceEvent

`com.huidu.farmersdelight.api.event.FarmersDelightProduceEvent` — `@ApiStatus.NonExtendable`

### When it fires

This is the one event addons are expected to **fire** as well as listen to. Three fire sites exist today:

1. **`CookingPotGui`** — a player clicks the output slot and takes a meal. Source `"cooking_pot"`, location is
   the pot. Fires after the item was delivered and the experience reward applied.
2. **`KegListener` (Brewin' And Chewin')** — a player takes the keg's stored output from its GUI. Source
   `"keg"`, location is the keg block centre.
3. **`KegManager` (Brewin' And Chewin')** — a player pours a drink out of a keg by right-clicking with a
   container. Source `"keg"`.

Fire site 3 has a threading caveat worth knowing: it dispatches **while holding the per-keg monitor**
(`synchronized (lockFor(key))`). Your handler therefore runs under a foreign plugin's lock. FarmersDelight's own
`RecipeDiscoveryListener` handles this by doing only cheap gate checks on the firing thread and pushing the
real work onto `player.getScheduler()`. Copy that pattern: **do not do heavy work, block, or acquire your own
locks in a `FarmersDelightProduceEvent` handler**, or you risk a lock-order inversion with a plugin you do not
control.

Note what is *not* covered: cutting-board output is dropped as an item entity and never produces this event.

### What it carries

`getPlayerId()` `UUID` (**may be null** for automated extraction such as a hopper or addon logic),
`getSource()` `String`, `getResult()` `ItemStack` (clone, may be null), `getLocation()` `Location` (clone, may
be null).

Constructor for addons firing their own:

```java
public FarmersDelightProduceEvent(UUID playerId, String source, ItemStack result, Location location)
```

### Cancellation and threading

Not cancellable — the item is already produced and, at every existing site, already delivered.

Thread depends on the site. All three fire on the region thread of the player performing the interaction. Only
the keg-pour site holds a lock while doing so.

### Example: listening

Brewin' And Chewin's advancement listener, trimmed. Note the `MONITOR` priority and the null guards:

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
            return; // automated extraction: nobody to award
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

### Example: firing your own

Report your own station's output so FarmersDelight's recipe discovery and other addons' stat trackers see it,
as Brewin' And Chewin's keg does:

```java
Bukkit.getPluginManager().callEvent(new FarmersDelightProduceEvent(
        player.getUniqueId(), "keg", taken,
        kegLoc == null ? null : kegLoc.clone().add(0.5, 0.5, 0.5)));
```

Prefer to dispatch after releasing your own locks, for the reason described above.

---

## FarmersDelightRecipeDiscoveryEvent

`com.huidu.farmersdelight.api.event.FarmersDelightRecipeDiscoveryEvent` — no `@ApiStatus` annotation, but treat
it as closed.

### When it fires

One dispatch point, `RecipeDiscoveryManager.fireChanged`, reached from the manager's `unlock` and `lock` paths.
It fires only on a **real transition**. Unlocking something already unlocked, or locking something already
locked, fires nothing, so a listener sees exactly one event per state change. Bulk operations such as
`lockAllOfType` fire one event per recipe that actually moved, not one per call.

### What it carries

`getPlayerId()` `UUID`, `getTypeId()` `String` (e.g. `farmersdelight:cooking_pot`, or an addon's own
`RecipeType` id), `getRecipeId()` `String`, `getAction()` `Action`, `getSource()` `Source`.

Two nested enums:

- `Action` — `UNLOCK`, `LOCK`.
- `Source` — `OBTAIN` (the obtain trigger: the player picked up, or was handed by a station, a result or exact
  ingredient), `API` (a plugin called the `FarmersDelightRecipeDiscovery` API), `COMMAND` (an operator ran the
  discovery admin command).

Every field is an immutable type, so the accessors hand out the field itself rather than a defensive copy;
there is no mutable state a listener can reach through.

**The named player may be offline.** The command path can change stored state for a player who is not on the
server, so always null-check `Bukkit.getPlayer(event.getPlayerId())`.

### Cancellation and threading

Not cancellable — the state is committed before listeners run. A veto belongs upstream, before the unlock is
requested.

Dispatched with none of the discovery manager's locks held. Like the buff-change event, the fire site branches:
on the primary thread it dispatches inline; otherwise it hands the finished event to
`plugin.scheduler().run(...)`, which lands on the global region. That branch exists because the concurrent-map
state change may legitimately happen on any thread while `callEvent` rejects synchronous dispatch from a
non-tick thread — dispatching inline would let an async unlock mutate the map and then throw half-committed.

Callers of the API must still request unlocks from a server thread. Listeners should treat the event as
region-local: act on the named player, not on unrelated world state.

### Example

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
            return; // an operator granted it; don't celebrate
        }
        Player player = Bukkit.getPlayer(event.getPlayerId());
        if (player == null) {
            return; // the command path can move state for an offline player
        }
        player.getScheduler().run(myPlugin, task -> player.sendMessage(
                "Recipe unlocked: " + event.getRecipeId()), null);
    }
}
```

---

## FarmersDelightReloadEvent

`com.huidu.farmersdelight.api.event.FarmersDelightReloadEvent` — `@ApiStatus.NonExtendable`

### When it fires

Two fire sites, and the reason string differs between them:

1. **`FarmersDelightPlugin.reloadAll()`** — fires with reason `"reloadAll"` after configs, recipes and language
   files have all been re-read.
2. **`FarmersDelightCommand.executeReload`** — fires for every `/fd reload <target>` *except* `all`, with the
   normalized target as the reason: `"config"`, `"gui"`, `"lang"`, `"language"`, `"languages"`, `"recipes"`,
   `"recipe"`, `"advancements"`, `"advancement"`. The `all` target is skipped here precisely because
   `reloadAll()` already fired it.

So a partial reload gives you a narrow reason and a full reload gives you `"reloadAll"` — never `"all"`.

### What it carries

`getReason()` `String`, documented as possibly null. Both current sites pass a non-null value.

### Cancellation and threading

Not cancellable. Arrives on the thread that performed the reload: the command thread for `/fd reload`, which is
the main/global thread.

This is the hook that lets an addon ship **no command of its own** — both Brewin' And Chewin' and
ExpandedDelight rely on it entirely. Note that recipes you registered survive a reload, but recipes that depend
on CraftEngine items should also be re-registered on CraftEngine's own `CraftEngineReloadEvent`, since CE items
only resolve after CE has loaded.

### Example

Brewin' And Chewin's real listener, in full:

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

If you need to observe values another handler reloaded, register at `MONITOR` — Brewin' And Chewin's
advancement listener rebuilds its cold-source set at `MONITOR` precisely so it runs after the `NORMAL`-priority
reload handler above.

---

## FarmersDelightWarmupEvent

`com.huidu.farmersdelight.api.event.FarmersDelightWarmupEvent` — `@ApiStatus.NonExtendable`

### When it fires

One fire site, the tail of `FarmersDelightPlugin.warmUp(String)`, which runs only once CraftEngine items are
actually loaded (`warmUpWhenReady` gates on `areCraftEngineItemsReady()`). It is reached from two places:

- Plugin enable, reason `"enable"` — taken when FarmersDelight enables *after* CraftEngine.
- The CraftEngine reload handler, reason `"reload"` — taken after every CE reload, and also on startup when
  CraftEngine loads *after* FarmersDelight. The readiness gate makes the two mutually exclusive, so you get
  exactly one warmup per readiness point, not two.

It fires after FarmersDelight has warmed its own item, GUI and behavior caches, so ordering relative to the
main plugin is deterministic.

### What it carries

`getReason()` `String` — `"enable"` or `"reload"`, documented as possibly null.

### Cancellation and threading

Not cancellable.

**Handlers must be pure computation.** They run on the global/main thread and must not touch worlds, entities,
regions or real block state. Build item stacks, prime your caches, and nothing else. This is a hard constraint
in the class contract, not a suggestion.

### Example

Brewin' And Chewin's real listener, in full — pre-building every CraftEngine item in its namespace so the first
in-game interaction does not pay the cold build cost:

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

Clearing your cache first matters: on the `"reload"` path your previously built stacks are stale.

---

## ProfessionCookingExperienceEvent

`com.huidu.farmersdelight.api.event.ProfessionCookingExperienceEvent` — `@ApiStatus.NonExtendable`

Note the name: this one has no `FarmersDelight` prefix.

### When it fires

Five fire sites, one per station plus the addon entry point. Their payloads differ in ways worth knowing:

| Site | `getSource()` | `getLocation()` | Notes |
| --- | --- | --- | --- |
| `FarmersDelightPlugin.callCookingPotExperienceEvent` | `"cooking_pot"` | **always null** | Uses the five-argument constructor. Reached from `CookingPotBlockBehavior` (taking a meal from the block) and `CookingPotGui` (taking from the output slot) |
| `StoveManager.finishCooking` | `"stove"` | the stove block | Only when a campfire recipe matched *and* the slot has a recorded owner |
| `SkilletManager.finishCooking` | `"skillet"` | the skillet block | Only when the skillet has a recorded owner |
| `CuttingBoardBlockBehavior.processCutting` | `"cutting_board"` | the board | `getBaseExperience()` is **hard-coded 0.0f** — the cutting board credits no experience |
| `FarmersDelightApi.awardCraftingExperience` | whatever the caller passed | the caller's location | The supported route for addons |

The cooking-pot site giving a null location is a real gap, not a bug in this document: the plugin still uses the
five-argument compatibility constructor there, and that constructor delegates with `null`. Always null-check
`getLocation()`.

The cutting-board site is careful about locking: the event is *constructed* inside the block entity's monitor,
so it snapshots consistent state, but is handed back and *dispatched* after the monitor is released, so no
third-party listener runs under that lock.

### What it carries

`getPlayerId()` `UUID`, `getPlayerName()` `String` (may be null), `getSource()` `String`, `getResult()`
`ItemStack` (clone, may be null), `getBaseExperience()` `float`, `getLocation()` `Location` (clone, may be
null).

`getBaseExperience()` is the experience the station is crediting **before any config multiplier**. The event is
read-only; you cannot change what is awarded.

Two public constructors:

```java
// five-argument, kept so callers compiled against earlier builds keep linking; location is null
public ProfessionCookingExperienceEvent(UUID playerId, String playerName, String source,
                                        ItemStack result, float baseExperience)

// preferred
public ProfessionCookingExperienceEvent(UUID playerId, String playerName, String source,
                                        ItemStack result, float baseExperience, Location location)
```

### Firing it from your own station

Do not construct it directly. Call the API instead, which also drops the vanilla XP orbs (gated by the
cooking-pot XP config) and awards AuraSkills XP, then fires the event for you:

```java
FarmersDelightApi.get().awardCraftingExperience(player, dropLocation, resultItem, xp, "keg");
```

Signature: `void awardCraftingExperience(Player player, Location location, ItemStack result,
double baseExperience, String source)`. It is Folia-safe — the whole body runs inside
`plugin.scheduler().runAt(location, ...)`, so the event arrives on the region owning that location, not on your
calling thread.

### Cancellation and threading

Not cancellable — the experience is already decided, and at the API site already awarded, by the time listeners
run.

Thread varies by site: the station sites fire on the region thread owning the station block; the API site fires
on the region owning the location you passed.

### Example

From the addon template:

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
            return; // getResult() is a defensive clone and may be null
        }
        logger.fine(event.getPlayerName() + " earned " + event.getBaseExperience()
                + " XP from " + event.getSource() + " -> " + result.getType());
    }
}
```

## Related pages

* [FarmersDelightApi entry point](farmersdelight-api.md)
* [Blocks and stations](blocks-and-stations.md)
* [Custom buffs and bossbars](buffs.md)
* [Recipe discovery](recipe-discovery.md)
