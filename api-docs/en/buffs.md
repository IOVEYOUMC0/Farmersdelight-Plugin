# Custom buffs

Package: `com.huidu.farmersdelight.api.buff`

A *custom buff* is per-player state an addon keeps outside of vanilla `PotionEffect` — FarmersDelight's own
Comfort and Nourishment, Brewin' And Chewin's Tipsy / Sweet Heart / Raging / Intoxication. Because that state
lives in the addon's own maps, nothing vanilla can see it: a `milk_bucket` does not clear it, a relog does not
carry it, and a HUD plugin cannot read it.

The three types in this package solve exactly that. You implement `CustomBuff` and hand it to
`CustomBuffRegistry`; FarmersDelight then drives the parts that need a central owner (milk curing, join/quit
persistence, the `/fd buff` admin commands, PlaceholderAPI output, change events). `BuffBossbar` is separate
and optional: it is how you get the buff *drawn* on screen, on whichever channel the server admin picked.

| Type | Shape | You |
| --- | --- | --- |
| `CustomBuff` | interface, `@ApiStatus.OverrideOnly` | implement it |
| `CustomBuffRegistry` | final class, static methods, `@ApiStatus.NonExtendable` | call it |
| `BuffBossbar` | final class, static methods, `@ApiStatus.NonExtendable` | call it |

`@ApiStatus.OverrideOnly` on `CustomBuff` means: implement the interface, but do not call another addon's
`CustomBuff` methods directly — go through the registry, which isolates exceptions and keeps the change-event
diff cache honest. `@ApiStatus.NonExtendable` on the other two is mechanically enforced anyway (both are
`final` with a private constructor).

## Implementing CustomBuff

Only three methods are abstract. Everything else has a default that degrades gracefully.

```java
String id();                                       // stable, namespaced: "myaddon:example_buff"
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

Rules the implementation must hold to:

- **Levels are 1-based.** `1` is baseline strength (potion amplifier 0), `2` is amplifier 1, and `0` means
  inactive. `level()` returning `0` is what the registry reads as "the player does not have this buff".
- **Everything is idempotent.** `remove()` on a player who does not have the buff is a no-op; `isActive()`
  is `false` both before the first grant and after removal.
- **`apply()` returns whether the grant happened.** The default `false` means "this buff cannot be granted
  programmatically", which is what `/fd buff give` reports back to the admin. Implement it if you want the
  admin command to work for your buff.
- **`restoreState()` must be gap-filling.** FarmersDelight calls it twice on join (see
  [Persistence](#persistence)), so it must return without touching anything when the buff is already active.
  The template's implementation opens with exactly that guard.
- **`saveState()` must clear its own keys when the buff is inactive**, otherwise a stale entry lingers in the
  player's PDC and gets restored on a later join.

Threading: every method is invoked on the target player's region thread. On Paper that is the main thread; on
Folia it is the region owning the player. Keep per-player state in a `ConcurrentHashMap` anyway — your own
timers will read it from elsewhere.

Persist **remaining seconds**, never an absolute tick or timestamp, so a restart cannot inflate or zero the
duration. This is the template's `ExampleCustomBuff`, trimmed:

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
            return; // gap-filling: never overwrite state that is already there
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

Brewin' And Chewin' registers its four buffs as anonymous implementations that delegate straight into its
existing managers — a reasonable shape when the state already lives somewhere else:

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

Note the null guards on the manager: the registry entry can outlive a manager that has already been stopped
during disable, and a buff method that throws is swallowed rather than reported.

## Registering and unregistering

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

Registration is keyed by `id()` and is idempotent: registering a second buff with an id already present
replaces the previous entry, which is what a live plugin reload naturally produces. `unregister` exists in two
forms — `unregister(CustomBuff)` (matched by id, so an equal-id instance works) and `unregister(String id)`.
Brewin' And Chewin' uses the string form on disable because its buffs are anonymous classes it does not keep
references to.

Registration works even when the buff system is switched off in FarmersDelight's config; you simply get no
grants until an admin switches it back on. Never make registration conditional on `isSystemEnabled()`.

## The registry surface

All methods are static on `CustomBuffRegistry`.

| Method | Returns | Notes |
| --- | --- | --- |
| `register(CustomBuff buff)` | `void` | throws `NullPointerException` on a null buff or null `id()` |
| `unregister(CustomBuff buff)` | `void` | no-op when absent or null |
| `unregister(String id)` | `void` | no-op when absent or null |
| `all()` | `List<CustomBuff>` | unmodifiable view over the live copy-on-write list; no allocation per call, safe to iterate during a concurrent register |
| `byId(String id)` | `CustomBuff` | `null` when not registered; O(1) |
| `isSystemEnabled()` | `boolean` | mirrors config `buff.enabled` |
| `apply(Player, String id, int level, int durationSeconds)` | `boolean` | `false` when the system is off, the id is unknown, the buff refused, or the buff threw |
| `clearAll(Player)` | `int` | count actually removed |
| `clearOne(Player)` | `CustomBuff` | removes exactly one, preferring non-low-priority; `null` when nothing was active |
| `activeBuffs(Player)` | `List<CustomBuff>` | fresh list, both priorities |
| `syncState(Player, String buffId)` | `boolean` | `true` when a transition was detected and the event fired |
| `syncState(Player, CustomBuff)` | `boolean` | same, for an instance you already hold |
| `syncAll(Player)` | `void` | syncs every registered buff |
| `saveAll(Player)` | `void` | calls every buff's `saveState`; no-op when the system is off |
| `restoreAll(Player)` | `void` | calls every buff's `restoreState`, then syncs; no-op when the system is off |
| `forget(Player)` | `void` | drops the player's diff-cache entry |

Two members are annotated `@ApiStatus.Internal` and must not be called by an addon:
`setSystemEnabled(boolean)` (written by FarmersDelight's config load) and `syncTrackedPlayers()` (driven by
FarmersDelight's own 20-tick pass).

Every method that calls into a `CustomBuff` wraps the call and swallows `RuntimeException` per buff, so one
broken addon cannot abort a milk wipe, a save or a restore for the others. Nothing is logged when that
happens — a buff that silently does nothing is worth checking against this.

## What FarmersDelight drives for you

### Milk

FarmersDelight listens on `PlayerItemConsumeEvent` at `MONITOR` with `ignoreCancelled = true`, on the
consuming player's region thread:

- **`minecraft:milk_bucket`** calls `clearAll(player)` — vanilla "milk wipes everything" semantics extended
  to custom state. Each removal is followed by a `syncState`, so change events fire immediately.
- **any custom item carrying the CraftEngine tag `farmersdelight:milk`** (FarmersDelight's `milk_bottle`)
  removes exactly **one** curable thing, chosen uniformly at random from a pool of *every active vanilla
  potion effect plus every active non-low-priority custom buff*. Low-priority buffs form a fallback pool used
  only when that primary pool is empty.

Two details worth knowing, verified at the call site rather than from the javadoc:

- The milk-bottle path does **not** call `CustomBuffRegistry.clearOne`. It builds its own pool through
  `activeBuffs(player)` so vanilla potion effects compete on equal weight with custom buffs — `clearOne`
  alone would never consider vanilla effects. `clearOne` is offered to addons but is not used anywhere inside
  FarmersDelight.
- Because that path calls `buff.remove(player)` directly, it does **not** sync the transition. The resulting
  `FarmersDelightBuffChangeEvent` therefore arrives from the periodic sync pass instead, up to 20 ticks
  later. Do not rely on a milk-bottle cure being reported in the same tick.

`isLowPriority()` returning `true` is what puts a buff in the fallback pool. Brewin' And Chewin' sets it on
Tipsy so a milk bottle cures a real debuff first and only sobers you up as a last resort, mirroring the
original mod's `brewinandchewin:low_priority/milk_bottle` effect tag.

### Persistence

Persistence is centralised, so you never register a join or quit listener for your buff.

| Moment | What runs |
| --- | --- |
| `PlayerJoinEvent` (`MONITOR`) | `restoreAll(player)` |
| after `buff.persistence.restore-retry-delay-ticks` | `restoreAll(player)` again, on the player's entity scheduler, if still online |
| `PlayerQuitEvent` (`LOWEST`) | `saveAll(player)` |
| `PlayerQuitEvent` (`MONITOR`) | `forget(player)` |
| FarmersDelight disable | `saveAll` for every online player, before buffs are unregistered |

The retry exists for whole-profile sync plugins (HuskSync, MySQLPlayerDataBridge and friends) that apply the
synced PDC a moment *after* join. It defaults to 40 ticks and is disabled by setting the key to `0`. This is
the whole reason `restoreState` must be gap-filling: the second call runs unconditionally.

The quit save deliberately runs at `LOWEST` so your PDC writes land before a sync plugin snapshots the
player. Because the state lives in the player's PDC, a buff persisted this way rides a whole-PDC sync across
a proxy network for free.

Brewin' And Chewin' additionally calls `saveAll` for every online player in its own `onDisable`, before it
stops the managers that hold the live values. A runtime disable (a plugin manager, a watchdog cascade) fires
no quit events, so without that loop the state of everyone online would be lost. Copy the pattern if your
buff is worth persisting:

```java
for (Player player : getServer().getOnlinePlayers()) {
    try {
        CustomBuffRegistry.saveAll(player);
    } catch (Throwable ignored) {
        // per-player isolation; FarmersDelight may already be down in a cascade
    }
}
```

### Change events

`com.huidu.farmersdelight.api.event.FarmersDelightBuffChangeEvent` fires when a registered buff's level on a
player *really* moves: gained (`0` to `N`), lost (`N` to `0`), or shifted between two non-zero levels. It does
**not** fire on a plain duration refresh, which is by far the most common update.

The transition is detected against a last-known-level map inside the registry, which is what makes the
"push as often as you like" contract work. `syncState` finding the same level as before does nothing at all —
no event, no allocation beyond the lookup.

```java
// after your own code mutates the buff's state
CustomBuffRegistry.syncState(player, ExampleCustomBuff.ID);
```

You do **not** need to call it after `CustomBuffRegistry.apply`, `clearAll`, `clearOne` or `restoreAll` —
those paths sync themselves.

A buff that simply runs out of time expires inside whoever owns the timer, and none of those paths go through
the registry. FarmersDelight therefore runs an internal pass every **20 ticks** that re-syncs every player the
diff cache still holds a level for, each on that player's own scheduler. That pass is what reports the loss
half of the transition. It is only armed while the buff system is enabled, and it returns on the first read
when nobody is buffed.

The event is **not cancellable** (it extends `Event`, not `Cancellable`) — the change has already happened.
Dispatch thread: if the transition is detected on the primary thread the event is called inline there;
otherwise the finished event is handed to the global region scheduler and delivered from it, because Bukkit
rejects a synchronous event dispatched off a tick thread. So on Folia a listener may see this event on the
global region thread and must not assume it owns the player's region. Listener exceptions are swallowed.

Fields: `getPlayerId()`, `getPlayerName()` (may be `null`), `getBuffId()`, `getPreviousLevel()`,
`getNewLevel()`, `getRemainingSeconds()`, plus the convenience predicates `isGained()` and `isLost()`. Note
it carries a `UUID`, not a `Player`.

### Admin command and placeholders

`/fd buff give <buff> [level] [seconds] [player]` routes through `CustomBuffRegistry.apply`, which is why
implementing `apply()` is what makes your buff grantable. A buff token resolves by exact namespaced id first,
then by the short suffix (`tipsy` finds `brewinandchewin:tipsy`). `/fd buff clear` uses `clearAll` or removes
one named buff directly.

When PlaceholderAPI is installed, every registered buff is exposed under the `farmersdelight` identifier:

```text
%farmersdelight_buff_<ns>_<id>_active%     1 / 0
%farmersdelight_buff_<ns>_<id>_level%      effective level, 0 when inactive
%farmersdelight_buff_<ns>_<id>_time%       remaining seconds
%farmersdelight_buff_<ns>_<id>_time_fmt%   m:ss, or h:mm:ss past an hour
%farmersdelight_buff_<ns>_<id>_name%       translated display name, server locale
%farmersdelight_buff_count%                number of active buffs on the player
```

`<ns>_<id>` is your buff id with the colon replaced by an underscore, so `brewinandchewin:sweet_heart`
becomes `brewinandchewin_sweet_heart`. These read `level()`, `remainingSeconds()` and `nameKey()` — that is
what those three defaults are for, and leaving them unimplemented gives a HUD nothing but `0` and your raw
id. This is a hot path (HUD plugins evaluate placeholders per player per tick), so keep them cheap map reads.

### The master switch

Config key `buff.enabled` in FarmersDelight's `config.yml` mirrors into `CustomBuffRegistry.isSystemEnabled()`
on every load and reload. While it is `false`:

- `register` / `unregister` still work normally;
- `apply` returns `false`, `saveAll` and `restoreAll` are no-ops;
- FarmersDelight's own effect ticker and the 20-tick sync pass are not armed;
- every `BuffBossbar` call is a no-op.

`saveAll` is gated deliberately: switching the system off clears the live state, and an ungated save would
write that emptiness over the player's stored buffs on their next quit and destroy them permanently. Skipping
the write leaves what is stored intact, ready for the switch being turned back on.

Read `isSystemEnabled()` to skip your own per-tick buff work entirely:

```java
if (!CustomBuffRegistry.isSystemEnabled()) {
    return;
}
```

## BuffBossbar

`BuffBossbar` renders per-player buff state. You push a title, a progress value and a style keyed by a stable
`NamespacedKey`; the *admin* decides the channel and layout in FarmersDelight's `config.yml`. The addon never
picks the channel.

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

`isEnabled()` is `false` when config has `buff.enabled: false`, or `buff.display.enabled: false`, or the
manager is not running (before FarmersDelight starts it, after it stops). Every other call is a safe no-op in
those windows, so the guard is an optimisation, not a correctness requirement.

`update` is idempotent per `(player, key)`: the first call creates the bar, later calls mutate it in place.
Push whatever the current state is, as often as you like. Cheap by design — the manager keeps a snapshot of
the last pushed tuple and returns without touching Adventure when nothing changed, and quantises `progress`
onto a 1/128 grid first so sub-pixel movement never becomes a packet. `progress` is clamped to `[0,1]`
(`NaN` clamps to `0`). A `null` title becomes empty, a `null` color becomes `WHITE`, a `null` overlay becomes
`PROGRESS`. Nothing happens when the player is offline.

Colors are `PINK, BLUE, RED, GREEN, YELLOW, PURPLE, WHITE`; overlays are `PROGRESS, NOTCHED_6, NOTCHED_10,
NOTCHED_12, NOTCHED_20`. `parseColor` / `parseOverlay` accept those case-insensitively with hyphens or
underscores and fall back rather than throwing on a typo, which is what makes them safe to point straight at
an admin-edited YAML string.

Use `FarmersDelightText.translatable(key, args...)` for the title so each viewer's client renders it in its
own language, with a server-resolved fallback for packs missing the entry. `FarmersDelightText.formatDuration(int)`
gives the vanilla-style `m:ss` used by FarmersDelight's own bars.

This is the template's feed, trimmed:

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

Drive it from a repeating task — `FarmersDelightApi.get().runRepeating(feed::tick, 20L, 10L)` returns an
`ApiTask` you cancel in `onDisable`. Ten ticks (half a second) is what both the template and Brewin' And
Chewin' use; it matches how a vanilla potion bar feels and avoids per-tick work for a value that barely moves.

Traps worth knowing:

- **`hideAll(owner, player)` removes every bar on that player, not only yours.** The `owner` argument is
  currently ignored by the implementation and the manager drops the player's whole bar map. Use `hide` per
  key unless you genuinely mean "clear this player's bars entirely". The `owner` parameter is accepted for
  future per-plugin features and for error reporting; it does not scope anything today.
- **Hiding one key at a time is the cheap path.** `hide` on a key that was never shown returns immediately.
  Brewin' And Chewin' relies on this and calls `hide` for every inactive buff on every feed tick.
- **A bar you stop pushing stays on screen.** There is no expiry; you must call `hide`.

You do not have to clean up on quit — `PlayerQuitEvent` and FarmersDelight's own disable both flush every
bar. You *should* still hide your bars in your `onDisable` (as Brewin' And Chewin' does) so a live reload of
your plugin alone does not leave stale bars behind, and on `PlayerDeathEvent` if you want the bar gone before
the next feed tick catches up.

Threading: the manager synchronises per player, so two addons pushing to the same player do not race, and
cross-player updates stay parallel. It reads no world state, so a global repeating task is a fine driver — the
values you feed it are your own.

The admin side, for reference when writing your own documentation: `buff.display.channels` takes any
combination of `bossbar`, `actionbar` and `tab_footer`; `buff.display.layout-mode` is `stacked` (every bar at
once) or `rotating` (one at a time, advancing every `rotation-interval-ticks`). Per-buff toggles — "show
Raging but hide Tipsy" — are your addon's concern, not FarmersDelight's. Brewin' And Chewin' keeps them in
its own `bossbar:` config section, alongside per-buff `color` / `overlay` read through `parseColor` and
`parseOverlay`.

## Related pages

* [Text and messages](text-and-messages.md)
* [Scheduling](scheduling.md)
* [Events](events.md)
* [Config updates](config-updates.md)
