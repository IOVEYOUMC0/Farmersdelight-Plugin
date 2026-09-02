
[简体中文](../zh-cn/getting-started.md)
# Getting Started

FarmersDelight is a Paper/Folia plugin built on CraftEngine. An addon is a **separate Bukkit plugin** that
runs in the same JVM, declares FarmersDelight as a hard dependency, and calls into FarmersDelight's Java API
in process. There is no network protocol and no command bridge — you compile against a jar and call methods.

This page covers the build wiring, the plugin.yml contract, what belongs in `onEnable`, and the lifecycle
guards every addon is expected to install.

## Only `com.huidu.farmersdelight.api.**` is stable

Everything outside that package is internal and may change without compatibility guarantees. The `api`
package is the whole contract: its signatures use only Bukkit types, JDK types, Adventure types and other
`api` types, so internal implementations do not leak through parameters or return values.

The corollary is that the api jar is all you need to compile.

## Getting the api jar

FarmersDelight's build defines an `apiJar` task that packages only `com/huidu/farmersdelight/api/**` — no
internals, not a runnable plugin:

```bash
# in the FarmersDelight repo
./gradlew apiJar
# -> build/libs/farmersdelight-1.0.2-api.jar
```

There are two ways real addons consume it.

### Option A — copy the jar (FDAddonTemplate)

The template keeps a copy under `libs/` and references it as `compileOnly`:

```kotlin
plugins {
    id("java")
    // Shadow bundles your code into one jar. FarmersDelight + CraftEngine are NOT bundled (compileOnly).
    id("io.github.goooler.shadow") version "8.1.7"
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.momirealms.net/releases/") // CraftEngine
    mavenLocal()
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.1-R0.1-SNAPSHOT")
    compileOnly("org.jetbrains:annotations:26.1.0")
    compileOnly("net.momirealms:craft-engine-core:26.8")
    compileOnly("net.momirealms:craft-engine-bukkit:26.8")
    compileOnly(files("libs/farmersdelight-api-1.0.0.jar"))
}

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(21)) }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(21)
}
```

`compileOnly` is deliberate: the real FarmersDelight plugin supplies the implementation at runtime. Shading
the api jar into your addon would give you a second, dead copy of those classes.

### Option B — composite build (BrewinAndChewin, ExpandedDelight)

Both production addons sit next to the FarmersDelight checkout and let Gradle rebuild and stage the api jar
automatically, so the addon never compiles against a stale surface:

```kotlin
// settings.gradle.kts
rootProject.name = "brewinandchewin"

includeBuild("../plugin") {
    name = "farmersdelight-plugin"
}
```

```kotlin
// build.gradle.kts
val syncFarmersDelightApi by tasks.registering(Copy::class) {
    group = "build"
    description = "Builds farmersdelight :apiJar via composite build and stages it into libs/."
    dependsOn(gradle.includedBuild("farmersdelight-plugin").task(":apiJar"))
    from(file("../plugin/build/libs")) {
        include("farmersdelight-plugin-*-api.jar")
        rename { "farmersdelight-1.0.0.jar" }
    }
    into("libs")
}

tasks.compileJava { dependsOn(syncFarmersDelightApi) }

dependencies {
    compileOnly(files("libs/farmersdelight-1.0.0.jar"))
}
```

`../plugin` is a directory junction pointing at the `FarmersDelight` module in this repository layout.

Both addons target Java 21 and `options.release.set(21)`.

## plugin.yml

Use `depend`, not `softdepend`. CraftEngine must have defined its items/blocks and FarmersDelight must have
built its api-backed services before your `onEnable` runs:

```yaml
name: FDAddonTemplate
version: '${version}'
main: com.example.fdaddon.FDAddonTemplate
api-version: '1.21'
folia-supported: true
description: Example addon template for the FarmersDelight (CraftEngine) plugin.
authors:
  - YourName
depend: [CraftEngine, FarmersDelight]
```

Notes on this file, all taken from the shipping addons:

- `folia-supported: true` — the api's scheduling helpers are Folia-aware, so an addon that routes its world
  access through them can honestly claim Folia support.
- Neither FDAddonTemplate nor BrewinAndChewin registers a command of its own. FarmersDelight drives reloads
  (`/fd reload all`) and recipe browsing (`/fd recipe book`) for registered addons.
- Optional integrations go in `softdepend` (BrewinAndChewin lists `BreweryX` there).

## onLoad

Two things belong in `onLoad`, before CraftEngine parses its configuration:

```java
@Override
public void onLoad() {
    // CraftEngine's own api — FarmersDelight does not bridge block-behavior registration.
    if (BuiltInRegistries.BLOCK_BEHAVIOR_TYPE.getValue(Key.of(NS + ":example_block")) == null) {
        BlockBehaviors.register(Key.of(NS + ":example_block"), ExampleBlockBehavior.FACTORY);
    }
    // Copy bundled CraftEngine resources into plugins/CraftEngine/resources/<namespace>/.
    CraftEngineResources.release(this, NS);
}
```

Custom block behaviors must be registered before `blocks.yml` is parsed, and your bundled CraftEngine
resources must be on disk before CraftEngine scans them. Block-behavior registration is a CraftEngine call;
resource release uses FarmersDelight's public API.

## onEnable

The shape below is FDAddonTemplate's `onEnable`, trimmed to the lifecycle-relevant parts.

```java
@Override
public void onEnable() {
    saveDefaultConfig();

    // ALWAYS guard api use with isAvailable(): true only when FarmersDelight is present AND enabled.
    if (!FarmersDelightApi.get().isAvailable()) {
        getLogger().warning("FarmersDelight not available; addon features disabled.");
        return;
    }

    // Count this addon's CraftEngine blocks in FarmersDelight's block-state usage report.
    FarmersDelightApi.get().registerAddonBlockNamespace("fdaddon");

    // Recipe types, recipes, buffs, listeners ... (see the other pages)
    FarmersDelightApi.get().registerRecipeType(new ExampleRecipeType());
    getServer().getPluginManager().registerEvents(new ExampleReloadListener(this), this);
    registerRecipes();

    // Folia-safe repeating task; keep the handle so onDisable can cancel it.
    heartbeat = FarmersDelightApi.get().runRepeating(this::onHeartbeat, 20L, 20L * 60L);

    // /fd debugtools integration. Safe even on a non-debug FarmersDelight build.
    DebugToolRegistry.register(new ExampleDebugExtension(this));

    // Refuse PlugMan-style runtime management of this addon.
    getServer().getPluginManager().registerEvents(new PluginManagerGuard(getName()), this);
}
```

### Recipes must wait for CraftEngine items

`FarmersDelightItems.create(...)` returns `null` while CraftEngine has not finished its deferred item-load
pass, so recipe registration cannot simply run once at enable. Both real addons register in two places: once
during `onEnable` (covers the case where CraftEngine finished first) and again from a listener on
CraftEngine's `CraftEngineReloadEvent`. The same listener handles FarmersDelight's `FarmersDelightReloadEvent`
so `/fd reload all` re-syncs the addon:

```java
public final class ExampleReloadListener implements Listener {

    private final FDAddonTemplate plugin;

    public ExampleReloadListener(FDAddonTemplate plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onFarmersDelightReload(FarmersDelightReloadEvent event) {
        plugin.reloadAddon(event.getReason());
    }

    @EventHandler
    public void onCraftEngineReload(CraftEngineReloadEvent event) {
        plugin.registerRecipes();
    }
}
```

Recipes registered through the api survive `/fd reload` — FarmersDelight keeps externally registered recipes
across a reload of its own recipe files.

## onDisable

Undo what you registered, guarding the api calls with `isAvailable()` again, and cancel every task handle:

```java
@Override
public void onDisable() {
    if (heartbeat != null) {
        heartbeat.cancel();
    }
    if (FarmersDelightApi.get().isAvailable()) {
        FarmersDelightApi.get().unregisterRecipeType(NS + ":example");
        FarmersDelightApi.get().unregisterCookingPotRecipe(NS + ":example_stew");
        FarmersDelightApi.get().unregisterCuttingBoardRecipe(NS + ":example_cut");
        CustomBuffRegistry.unregister(exampleBuff);
    }
    DebugToolRegistry.unregister("example_block");
}
```

`DebugToolRegistry` is a static registry that outlives your plugin classloader, so unregistering is not
optional — a stale entry keeps a torn-down manager reachable.

## Reload and unload guards

FarmersDelight, CraftEngine and any addon built on them all keep late-bound references into their own plugin
classloader: CraftEngine block behaviors, per-chunk block entities, scheduler tasks, event lambdas. When a
classloader is closed by `/reload` or a plugin manager, those references start throwing
`NoClassDefFoundError` at unpredictable times. There is no way to make that safe, so the project's answer is
to refuse the operation loudly. Three mechanisms exist, and an addon should adopt all three.

### 1. The JVM-lifetime reload guard

A system property survives plugin classloader recreation, so a second `onEnable` in the same JVM can be
detected and refused. FarmersDelight uses `farmersdelight.enabled.in.this.jvm`; BrewinAndChewin mirrors it:

```java
private static final String RELOAD_GUARD_PROPERTY = "brewinandchewin.enabled.in.this.jvm";

@Override
public void onEnable() {
    if (System.getProperty(RELOAD_GUARD_PROPERTY) != null) {
        getLogger().severe("PLEASE DO NOT /reload OR HOT-DISABLE Brewin' and Chewin'.");
        getLogger().severe("To apply config changes: /stop then start the server again.");
        getServer().getPluginManager().disablePlugin(this);
        return;
    }
    System.setProperty(RELOAD_GUARD_PROPERTY, "1");
    // ... real startup
}
```

Pick your own property name. Vanilla `/reload` is a core command and cannot be cancelled by a listener, which
is exactly why this guard is needed in addition to the next one.

### 2. `PluginManagerGuard` — refuse PlugMan-style commands

`com.huidu.farmersdelight.api.util.PluginManagerGuard` is a ready-made `Listener` you register with your own
plugin name (as it appears in `plugin.yml`). The whole public surface:

```java
public final class PluginManagerGuard implements Listener {

    public PluginManagerGuard(String pluginName);

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPlayerCommand(PlayerCommandPreprocessEvent event);

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onServerCommand(ServerCommandEvent event);
}
```

The constructor is the only member you touch — the two handlers are public because Bukkit's event system
requires it, not because you call them. Everything else (the command-name set, the verb set, the matcher, the
refusal message) is private. The class is `final` and carries **no** `@ApiStatus` annotation of any kind,
unlike `FarmersDelightApi`, `ApiTask` and `DebugToolRegistry`; there is nothing to extend anyway, and nothing
to implement.

`pluginName` is stored as given for the refusal message and lowercased once with `Locale.ROOT` for matching.
It is not null-checked — passing `null` throws `NullPointerException` from the constructor. Use `getName()`.

```java
getServer().getPluginManager().registerEvents(new PluginManagerGuard(getName()), this);
```

Both FDAddonTemplate and BrewinAndChewin install it (ExpandedDelight does not). It listens on
`PlayerCommandPreprocessEvent` and `ServerCommandEvent` at `EventPriority.LOWEST` with
`ignoreCancelled = true`, and cancels the command when all three of these hold:

- the command name (after stripping any `namespace:` prefix) is one of `plugman`, `plm`, `pluginmanager`,
  `plugmanx`, `plmx`, `pluginsmanager`, `pl`;
- the first argument is one of `unload`, `reload`, `disable`, `enable`, `load`, `restart`, `stop`, `start`;
- some later argument equals your plugin name, case-insensitively.

Read-only verbs such as `info` and `list` pass through. The sender gets a message telling them to `/stop` and
restart instead. Vanilla `/reload` is not covered — that is the previous guard's job.

### 3. `RequiredPluginWatchdogListener` — cascade a runtime disable

FarmersDelight itself installs a `CraftEngineWatchdogListener`: if CraftEngine is disabled while the server
keeps running, FarmersDelight disables itself rather than throwing from every CraftEngine-bound task. Your
addon should do the same one level down. BrewinAndChewin's version, in full:

```java
public final class RequiredPluginWatchdogListener implements Listener {

    private static final Set<String> REQUIRED = Set.of("FarmersDelight", "CraftEngine");

    private final BrewinChewinPlugin plugin;

    public RequiredPluginWatchdogListener(BrewinChewinPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPluginDisable(PluginDisableEvent event) {
        if (!REQUIRED.contains(event.getPlugin().getName())) {
            return;
        }
        if (plugin.getServer().isStopping() || !plugin.isEnabled()) {
            return; // normal shutdown, or we are already going down
        }
        plugin.getLogger().severe(event.getPlugin().getName() + " was disabled while the server is running.");
        plugin.getLogger().severe("Restart the server (/stop) to bring both back up.");
        plugin.getServer().getPluginManager().disablePlugin(plugin);
    }
}
```

The two early returns matter: without them the listener would fire during every normal server stop, where all
plugins are shutting down anyway. `MONITOR` priority is used because the listener only observes and reacts —
it never modifies the event.

### Ordering note for state you persist

Your addon disables **before** FarmersDelight and CraftEngine, because it depends on them. If your addon
persists block-entity state, flush or pre-serialize it at the top of `onDisable`, while your classloader is
still open. BrewinAndChewin calls `KegChunkListener.passivateLoadedChunks()` first thing in `onDisable`
precisely because CraftEngine serializes chunks after BAC is already gone, and re-entering keg code at that
point fails with "zip file closed".

## Where to go next

- The entry point itself — `apiVersion`, `hasFeature`, `isAvailable`, `isFolia`, `consoleMessage`,
  `isDebugEnabled`, `registerAddonBlockNamespace` — is documented in the FarmersDelightApi page.
- Scheduling (`runAtLocation`, `runLaterAtLocation`, `runRepeating`, `ApiTask`) has its own page, including
  which thread each callback arrives on under Folia.
- `/fd debugtools` integration is documented in the Debug Tools page.
- Recipes, items, blocks, buffs, events, text and config helpers are covered by their own sections.

## Related pages

* [FarmersDelightApi entry point](farmersdelight-api.md)
* [Events](events.md)
* [The recipe package](recipes-overview.md)
* [Scheduling](scheduling.md)
