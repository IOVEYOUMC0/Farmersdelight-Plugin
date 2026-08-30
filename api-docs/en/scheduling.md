
[简体中文](../zh-cn/scheduling.md)
# Scheduling and `ApiTask`

FarmersDelight supports both Paper and Folia. Its internal scheduler adapter detects which one it is running
on and picks the global, region or entity scheduler accordingly. Three methods on `FarmersDelightApi` expose
that adapter to addons, and `com.huidu.farmersdelight.api.scheduler.ApiTask` is the handle you get back for
the repeating one.

Using these helpers is what lets an addon put `folia-supported: true` in its `plugin.yml` without writing its
own Folia reflection. The feature id is `scheduler`.

## These calls are not soft no-ops — gate them on `isAvailable()`

Every scheduling entry point resolves `FarmersDelightPlugin.getInstance()`, null-checks **that**, and then
calls `plugin.scheduler()`. The instance is assigned in FarmersDelight's `onLoad`; the `SchedulerAdapter` is
built partway through `onEnable` and set back to `null` during `onDisable`. `scheduler()` throws
`IllegalStateException("Scheduler is not available")` whenever that field is `null`.

So in the window where the plugin instance exists but the adapter does not, `runAtLocation`,
`runLaterAtLocation`, `runRepeating`, `isFolia()` and `awardCraftingExperience` **throw — they do not
no-op and they do not return a fallback**. Two concrete windows:

- between FarmersDelight's `onLoad` and the adapter's construction in `onEnable` — which includes the case
  where FarmersDelight's own `/reload` guard aborts `onEnable` before the adapter is ever built;
- after FarmersDelight's `onDisable` has run, for the rest of the JVM session.

The null-check inside each method only covers "FarmersDelight was never loaded at all". `isAvailable()` is the
check that closes both windows for an addon, because it tests the enabled flag and not just the instance:
`enabled` is set `false` as the very first statement of `onDisable`, ahead of the field teardown, and it is
`false` for the whole `onLoad`-to-`onEnable` gap. The per-method notes below say "no-op when FarmersDelight is
not loaded"; read that as *not loaded*, not as *not enabled*.

One honest caveat on `isAvailable()`: `enabled` is set `true` near the top of FarmersDelight's `onEnable`, a
few statements *before* the adapter is constructed, so there is a brief interval during FarmersDelight's own
startup where `isAvailable()` is already `true` and `scheduler()` would still throw. An addon that declares
`depend: [FarmersDelight]` cannot observe it from its own `onEnable` — Bukkit finishes FarmersDelight's
`onEnable` first. It is reachable only from something that runs *during* FarmersDelight's enable, such as a
listener registered even earlier. If you are in that position, do the work from
`FarmersDelightWarmupEvent` or `FarmersDelightReloadEvent` instead of from your own enable.

## `ApiTask`

```java
package com.huidu.farmersdelight.api.scheduler;

@ApiStatus.NonExtendable
public interface ApiTask {

    ApiTask NOOP = new ApiTask() {
        @Override
        public void cancel() {
        }

        @Override
        public boolean isCancelled() {
            return true;
        }
    };

    void cancel();

    boolean isCancelled();
}
```

That is the entire type. It prevents the internal scheduler implementation from leaking across the API
boundary and gives addons a stable wrapper they can hold for the lifetime of the plugin.

`@ApiStatus.NonExtendable` on an interface means: use it, do not implement it. FarmersDelight supplies the
instances.

`ApiTask.NOOP` is the null object returned when a task could not be scheduled. Its `isCancelled()` returns
`true` and its `cancel()` does nothing, so the usual `if (task != null) task.cancel()` teardown works
uniformly and never NPEs.

## `void runAtLocation(Location location, Runnable task)`

Runs `task` on the thread that owns `location`.

- **Folia**: schedules onto the region scheduler for the chunk containing `location`.
- **Paper**: if the calling thread is already the primary thread the task runs **inline, synchronously,
  before `runAtLocation` returns**; otherwise it is scheduled onto the main thread for the next tick.
- If `location` is `null` **or its world is `null`**, the task is routed to the global path instead (Folia
  global region scheduler, or the Paper behaviour above). Both are checked: `runAt(Location, ...)` bails to
  the global path on a null location, and the world-taking overload it delegates to bails again on a null
  world.

The inline-on-Paper detail matters when you write code that assumes the callback is deferred: on Paper it may
have already run by the next statement.

The method is a no-op when FarmersDelight is not loaded or `task` is `null`; see the availability section
above for when it throws instead. It has no return value and no cancellation handle.

```java
/** Folia-safe: touch the block from the region that owns it. */
public void doAtBlock(Location loc, Runnable task) {
    FarmersDelightApi.get().runAtLocation(loc, task);
}
```

## `void runLaterAtLocation(Location location, Runnable task, long delayTicks)`

Same routing as `runAtLocation`, delayed by `delayTicks`. The fallback when there is no usable location is
**not** the main thread on Folia — `SchedulerAdapter.runLaterAt` falls through to `runLater`, which is
platform-split in its own right:

- **Folia, with a usable `location`**: a delayed task on the region owning `location`. `delayTicks <= 0`
  becomes a plain `run` on that region (next region tick, not inline); otherwise `runDelayed` with the delay
  as given.
- **Folia, with `location` null or its world null**: the task goes to the **global region scheduler**, not to
  a main thread. `delayTicks <= 0` becomes a plain global `run` — i.e. it fires on the next global tick
  rather than after your delay; otherwise `runDelayed` with the delay as given. There is no main thread to
  fall back to on Folia at all.
- **Paper** (any `location`, including `null`): a delayed task on the main thread, with the delay clamped to
  a minimum of 0 ticks. **This 0-tick clamp is the Paper path only.**

The practical trap is passing a `null`/world-less location together with `delayTicks <= 0` on Folia and
expecting your delay to be honoured on a location-owning thread: you get a global-region task on the next
tick, and touching blocks or entities from it will throw.

No-op when FarmersDelight is not loaded or `task` is `null`; see the availability section above for when it
throws instead. Returns nothing — there is no handle, so a
delayed task cannot be cancelled through this api. If you need cancellation, use `runRepeating` and cancel
after the first run, or track a flag your task checks.

## `ApiTask runRepeating(Runnable task, long delayTicks, long periodTicks)`

Schedules a repeating task and returns the handle.

- **Folia**: the task runs on the **global region scheduler** — *not* bound to any location's region.
- **Paper**: a main-thread repeating task.
- `delayTicks` and `periodTicks` are each clamped to a minimum of 1 tick **on both platforms** — Paper via
  `Math.max(1L, ...)` around `runTaskTimer`, Folia via the same `Math.max(1L, ...)` around the global
  scheduler's `runAtFixedRate`. A period of `0` is a period of `1` either way.
- Returns `ApiTask.NOOP` (never `null`) when FarmersDelight is not loaded or `task` is `null`; see the
  availability section above for when it throws instead of returning `NOOP`.

This is the one place where the threading model really shows through. **A Folia global-region task must not
touch blocks, block entities or entities directly** — those belong to a region thread. The correct shape is a
repeating tick that hops per location:

```java
heartbeat = FarmersDelightApi.get().runRepeating(() -> {
    for (Location loc : trackedStations()) {
        FarmersDelightApi.get().runAtLocation(loc, () -> tickStation(loc));
    }
}, 20L, 20L);
```

On Paper this collapses to a plain main-thread loop, since `runAtLocation` executes inline there.

Call `runRepeating` from your `onEnable` after the `isAvailable()` gate. FarmersDelight's scheduler accessor
throws `IllegalStateException("Scheduler is not available")` if the adapter has not been built yet, which is
one more reason not to call the api before FarmersDelight is enabled.

### Holding and cancelling the handle

Store the `ApiTask` in a field and cancel it in `onDisable`. FDAddonTemplate keeps two:

```java
private ApiTask heartbeat;
private ApiTask buffBossbarTask;

@Override
public void onEnable() {
    // ...
    heartbeat = FarmersDelightApi.get().runRepeating(this::onHeartbeat, 20L, 20L * 60L);
    buffBossbarTask = FarmersDelightApi.get().runRepeating(buffBossbar::tick, 20L, 10L);
}

@Override
public void onDisable() {
    if (heartbeat != null) {
        heartbeat.cancel();
    }
    if (buffBossbarTask != null) {
        buffBossbarTask.cancel();
    }
}
```

`isCancelled()` reports the underlying task's state, so it also flips to `true` once the task has been
cancelled by a plugin shutdown.

## A real wrapper

BrewinAndChewin routes its repeating and its region-bound tasks through a small helper rather than calling the
api at each site — a useful pattern if you want a single place to swap implementations later. Three public
static methods, plus a private constructor:

```java
/** Folia-friendly scheduling, routed through FarmersDelight's addon API (works on Paper and Folia). */
public final class Schedulers {

    private Schedulers() {
    }

    public static ApiTask runRepeating(Plugin plugin, long periodTicks, Runnable task) {
        return FarmersDelightApi.get().runRepeating(task, 1L, periodTicks);
    }

    /** Runs on the region owning {@code location} (immediately on Paper's main thread). */
    public static void runAt(Plugin plugin, Location location, Runnable task) {
        FarmersDelightApi.get().runAtLocation(location, task);
    }

    public static void cancel(ApiTask task) {
        if (task != null) {
            task.cancel();
        }
    }
}
```

Note it is *not* the addon's only scheduling route, and do not copy it as if it were. It covers exactly the
two api calls it wraps. **Delayed** location-bound work calls `FarmersDelightApi.get().runLaterAtLocation(...)`
directly — `KegManager`, `KegListener` and `BrewinAdvancementListener` each do — because the helper has no
delayed method. Per-entity work calls Folia's own `entity.getScheduler().run(...)` directly, because the api
exposes no entity-scheduling helper at all (see *What is not exposed* below).

One detail if you copy the shape: the `plugin` parameter on both wrapper methods is accepted and then unused.
The api resolves FarmersDelight's plugin instance itself, and the task is owned by FarmersDelight rather than
by your plugin — which is also why cancelling in your `onDisable` matters.

## What is not exposed

The internal scheduler adapter has more: entity-bound scheduling with a "retired" callback, async execution,
region-bound repeating tasks, and an `isOwnedByCurrentRegion(Location)` check. **None of these are on the api
surface** at the current revision. If you need an async file write, use Bukkit's own async scheduler; if you
need entity-region scheduling on Folia, use Folia's `Entity#getScheduler()` directly.

## Threading of api calls generally

- Anything that reads or mutates a block, block entity or inventory must happen on the owning region thread
  under Folia. Wrap it in `runAtLocation`.
- `awardCraftingExperience(...)` handles this for you: it internally schedules onto the region that owns the
  supplied location before dropping orbs and firing its event.
- The packet item display calls (`createItemDisplay`, `updateItemDisplay`, `removeItemDisplay`) are
  documented as needing to be called on the region that owns the location under Folia.
- Registry-style calls — `registerRecipeType`, `registerCookingPotRecipe`, `registerAddonBlockNamespace`,
  `DebugToolRegistry.register`, `hasFeature`, `apiVersion` — touch concurrent collections only and carry no
  region requirement.

## Related pages

* [FarmersDelightApi entry point](farmersdelight-api.md)
* [Custom buffs and bossbars](buffs.md)
* [Getting started](getting-started.md)
