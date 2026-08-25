# Debug tools — `DebugToolExtension` and `DebugToolRegistry`

FarmersDelight has an admin command, `/fd debugtools`, for stress-testing: mass-place blocks, mass-activate
them so they actually tick, print a performance snapshot, and undo the placement. The two types in
`com.huidu.farmersdelight.api.util` let an addon plug its own block into that command instead of shipping a
debug CLI of its own.

The feature id is `debug-tools`.

## Availability: this only runs on a debug build

`/fd debugtools` is compiled from a separate source set that is only included when FarmersDelight is built
with `-PdebugTools=true`; the command class is otherwise excluded from the jar and the command is never
registered. On a normal release build the registry is mostly **dormant**: `register` still works and nothing
ever calls your `place`, `activate` or `cleanupBeforeUndo`. `status` is the exception — it is invoked by the
always-resident `/fd stats addon` subcommand, which works even on a release build.

That is why registering unconditionally is safe, and why both FDAddonTemplate and BrewinAndChewin do exactly
that in `onEnable` without probing anything.

## `DebugToolRegistry`

```java
package com.huidu.farmersdelight.api.util;

@ApiStatus.NonExtendable
public final class DebugToolRegistry {

    public static void register(DebugToolExtension extension);
    public static void unregister(String name);
    @Nullable public static DebugToolExtension find(String name);
    public static Collection<String> registeredNames();
    public static Collection<DebugToolExtension> all();
}
```

A static, process-wide `ConcurrentHashMap` keyed by the lowercased extension name. Notes:

- `register` is idempotent — re-registering the same name replaces the previous extension. `null`
  extensions, and extensions whose `name()` is `null`, are ignored.
- `unregister(name)` must be called from your `onDisable`. The map is static and survives your plugin's
  classloader, so a stale entry keeps a torn-down manager reachable and will be invoked on the next
  `/fd debugtools` run.
- `find` returns `null` for an unknown or `null` name. `registeredNames()` and `all()` return unmodifiable
  views; `registeredNames()` is what feeds the command's tab-completion.

```java
@Override
public void onEnable() {
    // ...
    DebugToolRegistry.register(new ExampleDebugExtension(this));
}

@Override
public void onDisable() {
    DebugToolRegistry.unregister("example_block");
}
```

## `DebugToolExtension`

```java
package com.huidu.farmersdelight.api.util;

@ApiStatus.OverrideOnly
public interface DebugToolExtension {

    String name();

    int place(Player player, Location origin, int count, int spacing, int layers, UndoSink undo);

    @FunctionalInterface
    interface UndoSink {
        void capture(Location loc);
    }

    default int activate(Player player) { return 0; }

    default List<String> status(Player player) { return List.of(); }

    default void cleanupBeforeUndo(Location location) { }
}
```

`@ApiStatus.OverrideOnly` means the opposite of `NonExtendable`: you are expected to implement this interface,
but you must not call its methods yourself — FarmersDelight is the only caller. Treat the implementation as a
callback surface.

### `String name()`

The lowercase target keyword. It becomes the word an admin types:

```
/fd debugtools place keg 64 2 3
```

It is also used for tab-completion and as the extension name in `/fd stats addon <name>`. `DebugToolRegistry`
lowercases it on registration, so return it already lowercase to avoid surprises.

### `int place(Player player, Location origin, int count, int spacing, int layers, UndoSink undo)`

Called for `/fd debugtools place <name> [count] [spacing] [layers]` when `<name>` is not one of
FarmersDelight's built-in targets. Return how many blocks actually went into the world.

What the command has already done to the arguments before you see them:

- `count` is `max(1, requested)`, defaulting to 64.
- `spacing` is clamped to the range 1..16, defaulting to 1.
- `layers` is `max(1, requested)`, defaulting to 1.
- `origin` is the player's location, normalized to block coordinates.
- An undo batch has been opened, so every `undo.capture(...)` you make joins it.

**Important:** FarmersDelight's own `max-place-count` cap is applied to its built-in targets, but the
extension path passes you the clamped `count`, `spacing` and `layers` directly — the cap is *not* applied on
your behalf. Bound your own placement, or an admin typing a large count gets exactly what they asked for.

The placement pattern is your choice. Both shipped implementations use `grid = ceil(sqrt(count))` with
`spacing` between cells and `layers` stacked on Y, which is what the built-ins do too.

Call `undo.capture(loc)` **before** mutating each target block. `UndoSink` is a functional interface with a
single `capture(Location)`; FarmersDelight snapshots the block state at that location into the current undo
batch, so `/fd debugtools undo` can restore it. Captures that turn out not to change state are silently
no-op'd on undo.

BrewinAndChewin's keg implementation, trimmed:

```java
@Override
public int place(Player player, Location origin, int count, int spacing, int layers, UndoSink undo) {
    if (player == null || origin == null || origin.getWorld() == null) return 0;
    BlockDefinition kegBlock = CraftEngineBlocks.byId(KEG_BLOCK);
    if (kegBlock == null) {
        player.sendMessage("§c[BAC] keg block not registered with CraftEngine.");
        return 0;
    }

    int grid = Math.max(1, (int) Math.ceil(Math.sqrt(count)));
    int total = count * Math.max(1, layers);
    int placed = 0;
    for (int i = 0; i < total; i++) {
        int layer = i / count;
        int layerIndex = i % count;
        Location loc = new Location(
                origin.getWorld(),
                origin.getBlockX() + (layerIndex % grid) * spacing,
                origin.getBlockY() + 1 + layer,
                origin.getBlockZ() + (layerIndex / grid) * spacing);
        Block target = loc.getBlock();
        if (target.getType() != Material.AIR
                && !BlockStateUtils.isReplaceable(BlockStateUtils.getBlockState(target))) {
            continue;
        }
        if (undo != null) undo.capture(loc);
        if (CraftEngineBlocks.place(loc, kegBlock.defaultState(), false)) {
            placed++;
        }
    }
    return placed;
}
```

Note the `undo != null` guard — defensive, since the command always supplies a sink today.

### `int activate(Player player)`

Optional; default returns 0. Called for `/fd debugtools activate <name>`, and **also for every registered
extension** when the admin runs `/fd debugtools activate all`. Fill your placed blocks with sample state so
they start ticking, fermenting, cooking — whatever makes them load-bearing for a profiling run — and return
how many blocks transitioned to an active state. The count is added to the command's total.

FDAddonTemplate keeps its own `ConcurrentHashMap.newKeySet()` of placed locations because its demo block has
no manager. If your plugin already has a manager that tracks its blocks (as BrewinAndChewin's `KegManager`
does), iterate that instead of duplicating tracking.

### `List<String> status(Player player)`

Optional; default returns an empty list. Called by the **always-resident** `/fd stats addon <name>` subcommand
(the `/fd stats` overview lists every registered addon as a clickable name that drills into it), so it runs on a
release build too — no `-PdebugTools=true` needed. Each entry is one line, sent to the player by filling the
`{line}` placeholder of the translation key `command.stats_addon_line` (`{name}` is the extension name); the
line carries whatever prefix it has itself.

```java
@Override
public List<String> status(Player player) {
    return List.of("tracked example_blocks (debug-placed): " + placed.size());
}
```

Because each line is slotted into the `command.stats_addon_line` template, avoid a literal `{line}` or `{name}`
in your text — they are treated as placeholders. Put any prefix/colour inside your own line or in that translation
key.

Returning `null` is tolerated (treated as "no lines"), but an empty list is the documented way to opt out.

### `void cleanupBeforeUndo(Location location)`

Optional; default no-op. Called during `/fd debugtools undo` for **every restored location**, before the block
data is reverted. Use it to release in-memory tracking and remove block-entity NBT belonging to your
extension, so an undone placement does not leak ghost state.

Two properties of the call site are worth knowing:

- It is invoked on **every registered extension**, not just the one that placed the block. Your
  implementation must no-op cheaply when the location is not one of yours.
- Exceptions are caught and swallowed (`catch (Throwable ignored)`), so a bug here fails silently rather than
  aborting the undo. Log your own errors if you want to see them.

```java
@Override
public void cleanupBeforeUndo(Location location) {
    if (location == null) return;
    Iterator<Location> it = placed.iterator();
    while (it.hasNext()) {
        Location loc = it.next();
        if (loc.getBlockX() == location.getBlockX()
                && loc.getBlockY() == location.getBlockY()
                && loc.getBlockZ() == location.getBlockZ()
                && loc.getWorld() != null && loc.getWorld().equals(location.getWorld())) {
            it.remove();
            return;
        }
    }
}
```

## Threading

All four callbacks are invoked synchronously from the command handler, on the thread executing
`/fd debugtools`. On Paper that is the main thread. On Folia it is the thread the command dispatches on,
which owns the *sender's* region — not necessarily the region of the blocks you are about to place several
hundred metres away.

FarmersDelight's own built-in activation path is explicitly Folia-aware: it collects pending activations and
re-schedules each one onto the region owning its location. Extensions get no such treatment automatically. If
your extension touches blocks outside the sender's region on Folia, hop with
`FarmersDelightApi.get().runAtLocation(loc, ...)` yourself — noting that `place` must then return a count
before the deferred work has run, and that `undo.capture(loc)` should still happen up front on the command
thread so the capture joins the current undo batch.

Keep concurrent structures for any state shared between callbacks: `place` may run while a long `activate`
walks the same set. FDAddonTemplate uses `ConcurrentHashMap.newKeySet()` and iterates a defensive
`new ArrayList<>(placed)` copy for exactly this reason.

## Related pages

* [Version compatibility helpers](compat-utilities.md)
* [Getting started](getting-started.md)
* [FarmersDelightApi entry point](farmersdelight-api.md)
