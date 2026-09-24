
[简体中文](../zh-cn/farmersdelight-api.md)
# FarmersDelightApi — the entry point

`com.huidu.farmersdelight.api.FarmersDelightApi` is the single addon-facing entry point. It is
`@ApiStatus.NonExtendable`, `final`, has a private constructor, and is obtained through a static accessor:

```java
FarmersDelightApi api = FarmersDelightApi.get();
```

`get()` returns a process-wide singleton. It never returns `null`, and it is safe to call before
FarmersDelight has enabled — the instance exists independently of the plugin's lifecycle. What is *not* safe
before enable is calling the service methods, which is what `isAvailable()` is for.

The class holds two kinds of members: instance methods (`api.someCall(...)`) and two statics
(`FarmersDelightApi.consoleMessage(...)`, `FarmersDelightApi.isDebugEnabled(...)`). The distinction is
mechanical, not semantic — just match the source.

## Availability

### `boolean isAvailable()`

True when the FarmersDelight plugin instance exists and its internal enabled flag is set. Every addon call
into the api should be guarded with it. FDAddonTemplate and ExpandedDelight both open `onEnable` with the
check, immediately after `saveDefaultConfig()`:

```java
if (!FarmersDelightApi.get().isAvailable()) {
    getLogger().warning("FarmersDelight not available; addon features disabled.");
    return;
}
```

BrewinAndChewin does **not** — its `onEnable` goes from the `/reload` guard through `saveDefaultConfig()` and
its config bootstrap straight into `registerAddonBlockNamespace("brewinandchewin")`, with no `isAvailable()`
gate anywhere in the method. It relies on `depend: [FarmersDelight]` in its `plugin.yml` to guarantee load
order instead, and calls `api.isAvailable()` at one runtime site (`CoasterManager`) rather than at startup.
Follow the template, not BrewinAndChewin, here: a hard `depend` guarantees FarmersDelight *enabled first*, but
not that its enable *succeeded*.

Several api methods already no-op internally when this is false (`registerCookingPotRecipe`,
`registerCuttingBoardRecipe`, `createItemDisplay` and friends check it themselves), but the check is cheap and
silent failure is worse than an explicit early return. Others — every scheduling entry point — throw rather
than no-op; see the scheduling page.

### `boolean isFolia()`

True when FarmersDelight's scheduler adapter detected a Folia server. Use it only when you need to branch on
threading model; the scheduling helpers already do the right thing on both.

**It does not simply return `false` when FarmersDelight is unavailable — it can throw.** The implementation is
`plugin != null && plugin.scheduler().isFolia()`: the null guard is on
`FarmersDelightPlugin.getInstance()`, not on the adapter. `FarmersDelightPlugin.scheduler()` throws
`IllegalStateException("Scheduler is not available")` whenever the adapter field is `null`. That field is
assigned partway through `onEnable` and cleared in `onDisable`, while the instance is assigned back in
`onLoad` — so in the window between `onLoad` and the adapter's construction (including the case where
FarmersDelight's own `/reload` guard aborts `onEnable` early), and again after `onDisable`, `isFolia()`
throws rather than returning `false`.

`runAtLocation`, `runLaterAtLocation`, `runRepeating` and `awardCraftingExperience` share exactly this trap:
each null-checks only `getInstance()` and then dereferences `plugin.scheduler()`, so the documented "no-op
when not loaded" / "returns `ApiTask.NOOP`" behaviour holds only for *never loaded*, not for *loaded but not
enabled*. Gate on `isAvailable()` — it checks the enabled flag — and all five are safe.

## Versioning the surface

FarmersDelight's plugin version tracks content. The **api version** tracks the surface you compile against,
and they move independently — never gate an integration on the plugin version string.

### `int apiVersion()`

Returns the api surface revision of the running build. The documented bump policy: monotonic, `+1` on every
release that *adds* to the addon-facing surface (a new api class, method, event or feature id), never
decremented, never reused, and never bumped for internal refactors. Removals and incompatible changes are not
made under this scheme at all, so an addon compiled against revision N keeps compiling and linking against
every revision greater than N.

The current value is **3**. Revision 3 added `api.visual.DisplayGroup` (a group that holds packet displays and declares them live for the cleanup sweep) and `api.block.HeatSources` (per-plugin heat-source declarations, replayed after a reload). Revision 1 was the first to expose `apiVersion()` itself. On a build older than that the
method does not exist, so the call throws `NoSuchMethodError` — catch it and treat it as revision 0:

```java
int version;
try {
    version = FarmersDelightApi.get().apiVersion();
} catch (NoSuchMethodError pre) {
    version = 0;
}
if (version >= 1) {
    // use revision-1 capabilities
}
```

Unlike `isAvailable()`, this reports the compiled-in surface, so it stays meaningful while the plugin is still
enabling.

### `boolean hasFeature(String feature)`

True when the running build exposes the named capability. Ids are lowercase and hyphenated; the argument is
trimmed and lowercased before lookup, and `null` or unknown ids return `false`. Ids are never removed once
published, so probing an id that a newer build introduces is safe on an older one.

The complete set answered by the current build:

| Feature id | Covers |
| --- | --- |
| `recipes` | Runtime recipe registration plus the generic recipe book and editor: `registerRecipeType`, `registerCookingPotRecipe`, `registerCuttingBoardRecipe`, `openRecipeBook`, `openRecipeEditor` |
| `item-displays` | Packet-only item displays: `createItemDisplay`, `updateItemDisplay`, `removeItemDisplay` |
| `scheduler` | Folia-safe scheduling helpers: `runAtLocation`, `runLaterAtLocation`, `runRepeating` |
| `buffs` | The custom buff registry and the buff bossbar render channels |
| `station-query` | `com.huidu.farmersdelight.api.block`: station identification and read-only snapshots |
| `harvest-event` | `FarmersDelightHarvestEvent` for the Java-side harvest handlers |
| `cook-start-event` | `FarmersDelightCookStartEvent` on the cooking pot's idle-to-cooking transition |
| `buff-change-event` | `FarmersDelightBuffChangeEvent` on real custom-buff level transitions |
| `cooking-experience-location` | `ProfessionCookingExperienceEvent` carries the station location |
| `knife-drop-rules` | Knife extra-drop rule registration (`FarmersDelightKnifeDrops`) |
| `compat-util` | `com.huidu.farmersdelight.api.util` cross-version compatibility helpers |
| `debug-tools` | Debug tool extension hooks for `/fd debugtools` |
| `villager-trades` | Runtime villager / wandering-trader trade registration (`FarmersDelightVillagerTrades`) |
| `durable-items` | Durability decoupled from the sword: the `farmersdelight:durable` item setting plus `FarmersDelightItems.damage(...)` |
| `special-recipes` | Programmatic special-recipe registration (`registerSpecialRecipe` / `unregisterSpecialRecipe` / `specialRecipes`) with per-recipe display types |
| `content-check` | CraftEngine content existence checks (`FarmersDelightContent`) |
| `common-tags` | Central tag registry: addons register their tag→item mappings so the whole family resolves the same tags (`registerCommonTags` / `unregisterCommonTags`) |
| `advancement-triggers` | Shared obtain/craft/produce and consume advancement item triggers |

Common-tag registration after content load rebuilds tag-dependent recipes and GUI caches automatically.

Prefer `hasFeature` when you care about one capability, and `apiVersion()` when you need an ordering. Like
`apiVersion()`, calling `hasFeature` on a build older than the one that introduced it throws
`NoSuchMethodError`, so guard the first probe if you support such builds.

```java
if (FarmersDelightApi.get().hasFeature("knife-drop-rules")) {
    // register your knife drops
}
```

## `void registerAddonBlockNamespace(String namespace)`

FarmersDelight monitors CraftEngine block-state consumption and reports it. By default the report only
attributes FarmersDelight's own namespace; registering yours makes your addon's blocks show up alongside it.
BrewinAndChewin does this early in `onEnable`:

```java
FarmersDelightApi.get().registerAddonBlockNamespace("brewinandchewin");
```

The call is idempotent, lowercases and trims the argument, tolerates a trailing `:`, and ignores `null` or an
empty result. It has no gameplay effect — it is purely diagnostic bookkeeping.

The companion reader `Set<String> addonBlockNamespaces()` returns an immutable copy of the registered
namespaces, without trailing colons. It exists for the block-state usage monitor; addons rarely need it.

## Console messages and the shared lang system

FarmersDelight ships `lang/en_us.yml` and `lang/zh_cn.yml` and deploys them to
`plugins/FarmersDelight/lang/`. Console strings live under the top-level `console:` section.

### `static String consoleMessage(String key, Object... args)`

Formats a console line from those lang files, so an addon's log output follows the operator's configured
language instead of hardcoded English. Note it is **static** and it *returns* a string — it does not log.
The caller picks the logger and the level:

```java
getLogger().info(FarmersDelightApi.consoleMessage("bac.enabled"));
getLogger().info(FarmersDelightApi.consoleMessage("bac.keg_recipes_loaded", "count", recipes.size()));
getLogger().warning(FarmersDelightApi.consoleMessage("bac.resources_release_failed", "error", e.getMessage()));
```

Resolution rules, as implemented:

- The key is prefixed with `console.` unless it already starts with it. `"bac.enabled"` looks up
  `console.bac.enabled`.
- Lookup uses the console/default locale, then the current locale, then the language file bundled inside the
  FarmersDelight jar for that locale, then `en_us`.
- An unknown key resolves to the key itself — including the added prefix, so you will see the literal string
  `console.bac.enabled` on the console. That is the signal your key is missing.
- `args` are name/value **pairs**: `("count", 3, "file", name)` substitutes `{count}` and `{file}` in the
  message. Pairs are read two at a time; a trailing odd argument is ignored. Values go through
  `String.valueOf`, so any type works.

An example entry from FarmersDelight's `lang/en_us.yml`:

```yaml
console:
  bac:
    enabled: "Brewin' And Chewin' (CraftEngine addon) enabled."
    config_keys_merged: "Added {count} new setting(s) to config.yml."
```

**Limitation worth knowing before you build on this.** There is no api call that registers an addon's own
language file. BrewinAndChewin's `console.bac.*` keys are shipped inside FarmersDelight's lang files. A
third-party addon has two honest options: add your keys to the deployed
`plugins/FarmersDelight/lang/<locale>.yml` (keys present there resolve normally) and document that for server
owners, or keep your own message catalogue and use `consoleMessage` only for keys you know exist.

### `String resolveTranslations(String text, Player player)`

Instance method. Resolves `<l10n:key>`, `<lang:key>` and `<i18n:key>` tags inside `text` to the player's
locale, falling back to the default locale and then to the raw key, and leaves everything else — including
MiniMessage markup — untouched. Keys resolve through both FarmersDelight's lang files and CraftEngine's
translations, so an addon can put localized placeholders in its own GUI config strings. `player` may be
`null`, which uses the default locale. The text section of this documentation covers the wider rendering
helpers.

## `static boolean isDebugEnabled(String category)`

True when FarmersDelight's shared debug switch is on **and** the given category is listed. The full condition,
in evaluation order — every clause must hold:

1. `FarmersDelightPlugin.getInstance() != null`. The static returns `false` when FarmersDelight is not
   loaded; unlike the scheduling calls it cannot throw, because it delegates to a plain field read.
2. FarmersDelight's master debug switch is on. It is read as `debug` **or** `debug.enabled` — either the
   legacy boolean scalar `debug: true` or the section form `debug: {enabled: true}` turns it on, both
   defaulting to `false`. When the switch is off the call returns `false` immediately, **before the category
   is looked at at all** — so `*` or `all` in `debug.categories` does not rescue a disabled switch.
3. `category` is neither `null` nor blank. This check comes *after* the master switch and is unconditional:
   a `null` or whitespace-only category returns `false` even when `debug.enabled` is on and
   `debug.categories` contains `*`. There is no "no category means everything" fallback.
4. The category matches. `category` is trimmed and lowercased, then `debug.categories` must contain `*`, or
   `all`, or that normalized string. The operator's list is normalized the same way when it is loaded
   (trimmed, lowercased, empty entries dropped), so case and stray spacing on either side are irrelevant —
   with one edge: the list is lowercased with `Locale.ROOT` while your argument is lowercased with the
   JVM default locale. Under a Turkish default locale a category containing `I` will not match. Pass
   already-lowercase ids and it never comes up.

The intent is that an addon applies the same console policy as FarmersDelight itself: keep a healthy boot to a
single summary line, and emit per-subsystem counts through your own logger at `INFO` only when the operator
asked for that category, at `FINE` otherwise. Nothing is dropped either way — raising the logger level still
surfaces it.

```java
String line = FarmersDelightApi.consoleMessage("myaddon.recipes_loaded", "count", loaded);
if (FarmersDelightApi.isDebugEnabled("startup")) {
    getLogger().info(line);
} else {
    getLogger().fine(line);
}
```

Use the `startup` category for boot census lines, matching FarmersDelight's own usage. Category names are
whatever the operator writes in `debug.categories`; FarmersDelight's internals use ids such as `startup`,
`config` and `recipe`.

## Heat source queries

Two instance methods let a custom cooking block reuse FarmersDelight's configured heat rules instead of
hardcoding a block list:

- `boolean isHeatSource(Block block)` — true if the block is a configured heat source (what heats a cooking
  pot).
- `boolean isConductor(Block block)` — true if the block is a configured heat conductor, i.e. it passes heat
  up from a source below it.

Both return `false` when FarmersDelight is not loaded or the block is `null`.

```java
public boolean isHeated(Block below) {
    FarmersDelightApi api = FarmersDelightApi.get();
    return api.isHeatSource(below) || api.isConductor(below);
}
```

Reading a block means touching the world, so on Folia call these from the region that owns the block — see
the scheduling page.

## The rest of the surface

`FarmersDelightApi` also carries members documented in other sections, listed here so you know where they
live:

- Recipes: `registerRecipeType`, `unregisterRecipeType`, `recipeTypes`, `recipeType`,
  `registerCookingPotRecipe`, `unregisterCookingPotRecipe`, `registerCuttingBoardRecipe`,
  `unregisterCuttingBoardRecipe`, `openRecipeBook` (three overloads), `openRecipeEditor`.
- Packet item displays: `createItemDisplay`, `updateItemDisplay`, `removeItemDisplay`.
- Experience: `awardCraftingExperience`.
- Scheduling: `runAtLocation`, `runLaterAtLocation`, `runRepeating` — see the scheduling page.

## What `@ApiStatus.NonExtendable` means here

`FarmersDelightApi` is annotated `@ApiStatus.NonExtendable` and is also `final` with a private constructor, so
the annotation is documentation of intent rather than the only barrier: do not subclass, do not proxy, do not
construct. Obtain the singleton with `get()`. The same annotation appears on `ApiTask` and
`DebugToolRegistry`; where it sits on an interface (`ApiTask`) it means you may *use* the type but must not
implement it — FarmersDelight hands you the implementations.

## Related pages

* [Getting started](getting-started.md)
* [Events](events.md)
* [The recipe package](recipes-overview.md)
* [Scheduling](scheduling.md)
