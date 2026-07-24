# Food Effects

Package: `com.huidu.farmersdelight.api.effect`
Class: `FarmersDelightFoodEffects` — `final`, private constructor. No `@ApiStatus` annotation on this
class, so treat it as ordinary stable API.

FarmersDelight ships two custom food effects:

* **Comfort** — slow regeneration while the player is not saturated.
* **Nourishment** — suppressed exhaustion.

An addon can apply, query and clear them directly, or register its own food items so that eating them
grants an effect.

Every signature uses only Bukkit / `java` types; the bodies delegate to renamed internals.

## Threading

The apply/remove methods mutate player state and send messages, so they must run on the player's owning
thread — the main thread on Paper, the player's region thread on Folia. Not from an async task.

The register/unregister methods only write to a `ConcurrentHashMap` and are safe to call from
`onEnable`, from a reload handler, or from `onDisable`.

## Applying and querying

```java
public static void    applyComfort(Player player, int durationSeconds);
public static void    applyComfort(Player player, int durationSeconds, int level);
public static void    applyNourishment(Player player, int durationSeconds);
public static void    applyNourishment(Player player, int durationSeconds, int level);

public static boolean hasComfort(Player player);
public static boolean hasNourishment(Player player);

public static void    removeComfort(Player player);
public static void    removeNourishment(Player player);
```

Durations are in **seconds**, not ticks. The two-argument overloads apply at level 1.

`level` is 1-based and clamped to a minimum of 1 internally.

A `null` player, or a `durationSeconds` of 0 or less, is a silent no-op on the apply methods. `null` is
also safe on the query and remove methods.

### Stacking rule

Applying stacks against any active dose by the vanilla potion rule:

* a **higher** level replaces the active effect and refreshes its duration;
* an **equal** level extends to whichever remaining time is longer;
* a **lower** level is ignored while a stronger dose is active.

These effects are not chainable, so a masked weaker dose is not stored to resume later — it is simply
dropped.

### One condition worth knowing

Verified at the implementation: `applyComfort` and `applyNourishment` return immediately when
FarmersDelight's custom buff system is disabled (`CustomBuffRegistry.isSystemEnabled()` is false). The
call silently does nothing. If your addon depends on the effect actually landing, check
`hasComfort` / `hasNourishment` afterwards rather than assuming the apply took.

```java
FarmersDelightFoodEffects.applyComfort(player, 120);          // 2 minutes, level 1
FarmersDelightFoodEffects.applyNourishment(player, 300, 2);   // 5 minutes, level 2

if (FarmersDelightFoodEffects.hasComfort(player)) {
    // ...
}
```

## Registering addon foods

```java
public static void registerComfortFood(String itemId, int durationSeconds);
public static void registerNourishmentFood(String itemId, int durationSeconds);
public static void unregisterComfortFood(String itemId);
public static void unregisterNourishmentFood(String itemId);
```

`itemId` is a namespaced id — a CraftEngine custom id or `minecraft:...`. Eating that item then grants
the effect for `durationSeconds`.

Three properties, all verified at the eat-handling site:

1. **They ignore the config toggles.** A registered food fires regardless of the `comfort-foods` /
   `nourishment-foods` `enabled` flags in FarmersDelight's config.
2. **They take precedence over config entries.** The handler reads the addon-registered map first and
   only falls back to the config-loaded map when there is no registered entry for that id.
3. **They survive `/fd reload`.** A config reload clears only the config-loaded maps; the
   addon-registered maps are untouched. You do not need to listen for `FarmersDelightReloadEvent` just
   to re-register.

Registering the same id again replaces the previous duration. A `null` id or a non-positive duration is
a no-op. All four methods are no-ops when FarmersDelight is unavailable.

Unregister on your plugin's disable.

## The registrar pattern

BrewinAndChewin's `FoodEffectRegistrar` is the production shape, and its own javadoc calls it a pattern
any addon can copy verbatim: hold one instance per plugin, call `apply` from `onEnable` and again from
your reload listener, call `clear` from `onDisable`. It tracks which ids it added, so a reload-time
`apply` cleanly unregisters the previous set before applying the new one.

```java
import com.huidu.farmersdelight.api.effect.FarmersDelightFoodEffects;
import org.bukkit.configuration.ConfigurationSection;

public final class FoodEffectRegistrar {

    private final Set<String> registeredNourishment = new HashSet<>();
    private final Set<String> registeredComfort = new HashSet<>();

    public void apply(ConfigurationSection root) {
        clear();
        if (root == null) {
            return;
        }
        registerSection(root.getConfigurationSection("nourishment"), true);
        registerSection(root.getConfigurationSection("comfort"), false);
    }

    public void clear() {
        for (String id : registeredNourishment) {
            FarmersDelightFoodEffects.unregisterNourishmentFood(id);
        }
        for (String id : registeredComfort) {
            FarmersDelightFoodEffects.unregisterComfortFood(id);
        }
        registeredNourishment.clear();
        registeredComfort.clear();
    }

    private void registerSection(ConfigurationSection section, boolean nourishment) {
        if (section == null) {
            return;
        }
        for (String id : section.getKeys(false)) {
            int seconds = section.getInt(id);
            if (seconds <= 0) {
                continue;
            }
            if (nourishment) {
                FarmersDelightFoodEffects.registerNourishmentFood(id, seconds);
                registeredNourishment.add(id);
            } else {
                FarmersDelightFoodEffects.registerComfortFood(id, seconds);
                registeredComfort.add(id);
            }
        }
    }
}
```

With a matching config section:

```yaml
food-effects:
  comfort:
    brewinandchewin:kombucha: 240
  nourishment:
    brewinandchewin:cheesy_pasta: 300
```

`FDAddonTemplate` ships the same class under the name `ExampleFoodEffectRegistrar`, reading a
list-of-maps shape (`- id: ... / duration: ...`) instead of a key-to-value shape. Either config layout
works — the API only cares about the id and the number of seconds. Note that the template's comments
say "duration-ticks"; the API parameter is `durationSeconds`, and seconds is what the implementation
uses (it multiplies by 20 internally).

## Related pages

* [Items](items.md)
* [Custom buffs and bossbars](buffs.md)
* [Events](events.md)
