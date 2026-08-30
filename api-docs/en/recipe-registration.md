
[简体中文](../zh-cn/recipe-registration.md)
# Cooking pot and cutting board recipes

This is the other half of the recipe API: instead of rendering your own station's recipes, you contribute
recipes to **FarmersDelight's** stations. A recipe registered this way is cooked by the real cooking pot and cut
on the real cutting board, shows up in FarmersDelight's own recipe views, and participates in recipe discovery.

Registration lives on `FarmersDelightApi`; read-only queries live on
`com.huidu.farmersdelight.api.recipe.FarmersDelightRecipes`.

## Ingredient syntax

Ingredients are strings in the same syntax the recipe files use:

| Form | Meaning |
| --- | --- |
| `ns:id` | exactly that item (vanilla or a CraftEngine custom item) |
| `#ns:tag` | any item in that tag |
| `a\|b` | a choice — any of the alternatives, each itself an item or a tag |

## Registering a cooking-pot recipe

```java
public void registerCookingPotRecipe(String id, List<String> ingredients, ItemStack container,
                                     ItemStack result, double experience, int cookTime, String category);

public void unregisterCookingPotRecipe(String id);
```

- `container` is the required bowl/bottle; `null` (or an air stack) means none.
- `result` carries its own amount.
- `container` and `result` are cloned on the way in, so you keep ownership of your stacks.
- `experience` is clamped to `>= 0`, `cookTime` to `>= 1`, and a `null` `category` becomes `"misc"`.
- Registering an id that already exists **replaces** it.
- The recipe survives `/fd reload`. It does not survive a restart — re-register on every enable.

Two different failure modes, and the difference matters:

- `null` plugin, FarmersDelight not available, or `null` `id` / `ingredients` / `result` — **silent no-op**.
- blank `id`, empty `ingredients` list, or an air `result` — **`IllegalArgumentException`**.

So a config-driven registrar should validate before calling rather than relying on a silent skip.

The republish is **coalesced to the next tick**: a batch of registrations triggers one recipe reload one tick
later. A query issued immediately after `registerCookingPotRecipe` will not see the new recipe yet.

## Registering a cutting-board recipe

```java
public void registerCuttingBoardRecipe(String id, String input, String tool,
                                       List<ItemStack> results, String sound);

public void unregisterCuttingBoardRecipe(String id);
```

- `input` and `tool` use the same ingredient syntax. **`tool` is required** — there is no "any tool" recipe
  through this API.
- Each stack in `results` carries its own amount and is dropped with 100% chance. The probabilistic multi-output
  that the recipe files support is not exposed here.
- `sound` is a sound id, or `null` for the default knife sound.
- `null` stacks and air stacks in `results` are dropped before validation.
- Silent no-op on `null` `id` / `input` / `tool` / `results`; `IllegalArgumentException` on a blank `id`, blank
  `input`, blank `tool`, an input spec that resolves to no display item, or a `results` list with nothing valid
  left in it.
- Same next-tick republish and same `/fd reload` persistence as the cooking pot.

## When to register

The result and container are `ItemStack`s, so CraftEngine items must already be loaded. FarmersDelight itself
defers its recipe load to `CraftEngineReloadEvent`; do the same and re-register on every later CE reload. This
is BAC's registrar, trimmed:

```java
public final class BrewinCookingPotRecipes implements Listener {

    private final JavaPlugin plugin;
    private final Set<String> registeredIds = new LinkedHashSet<>();

    @EventHandler
    public void onCraftEngineReload(CraftEngineReloadEvent event) {
        BrewinItems.clearCache();
        register();
    }

    public void register() {
        YamlConfiguration config = YamlConfiguration.loadConfiguration(
                new File(plugin.getDataFolder(), "recipes/cooking_pot_recipes.yml"));
        ConfigurationSection root = config.getConfigurationSection("cooking_pot_recipes");
        if (root == null) {
            return;
        }
        FarmersDelightApi api = FarmersDelightApi.get();
        Set<String> freshIds = new LinkedHashSet<>();
        for (String key : root.getKeys(false)) {
            ConfigurationSection section = root.getConfigurationSection(key);
            if (section == null) {
                continue;
            }
            List<String> ingredients = section.getStringList("ingredients");
            String resultId = section.getString("result");
            if (ingredients.isEmpty() || resultId == null) {
                continue;
            }
            ItemStack result = BrewinItems.create(resultId);
            if (result == null) {
                continue; // CraftEngine items not ready yet; a later CraftEngineReloadEvent retries.
            }
            result.setAmount(Math.max(1, section.getInt("result-count", 1)));
            String containerId = section.getString("container");
            ItemStack container = containerId == null ? null : BrewinItems.create(containerId);
            String recipeId = "brewinandchewin:" + key;
            api.registerCookingPotRecipe(recipeId, ingredients, container, result,
                    section.getDouble("experience", 0.0), section.getInt("cook-time", 200),
                    section.getString("category", "misc"));
            freshIds.add(recipeId);
        }
        // Drop recipes that were registered before but are gone now, then adopt the fresh set.
        for (String stale : registeredIds) {
            if (!freshIds.contains(stale)) {
                api.unregisterCookingPotRecipe(stale);
            }
        }
        registeredIds.clear();
        registeredIds.addAll(freshIds);
    }

    public void unregister() {
        FarmersDelightApi api = FarmersDelightApi.get();
        for (String id : registeredIds) {
            api.unregisterCookingPotRecipe(id);
        }
        registeredIds.clear();
    }
}
```

Three things this pattern gets right and a naive one does not: it is **idempotent** (both `onEnable` and the
startup `CraftEngineReloadEvent` may call it), it **skips** entries whose CraftEngine items are not loaded yet
so a later reload can retry them, and it **unregisters** ids that vanished from the config instead of leaving
orphans in the pot.

## Querying recipes

`FarmersDelightRecipes` is `@ApiStatus.NonExtendable`, final, and static-only. Every method degrades to a safe
empty answer when FarmersDelight is not **loaded** — but *loaded* is doing real work in that sentence, and it
is not the same thing as *enabled*.

Every method here null-checks `FarmersDelightPlugin.getInstance()` and then calls
`plugin.getCookingPotRecipes()` or `plugin.getCuttingBoardRecipes()`. Those two accessors throw
`IllegalStateException("Plugin is not enabled")` when their manager field is `null` — and the fields are
assigned during `onEnable` and set back to `null` during `onDisable`, while the instance is assigned back in
`onLoad`. So between FarmersDelight's `onLoad` and its recipe managers being built, and again after its
`onDisable`, these queries **throw instead of returning an empty answer**. Sister facades differ here:
`FarmersDelightKnifeDrops` resolves its handler through a guard that checks `isEnabled0()` and returns `null`,
so it genuinely does degrade; `FarmersDelightRecipes` has no such guard.

Guard with `FarmersDelightApi.get().isAvailable()` — it checks the enabled flag, which is cleared as the first
statement of `onDisable` — before querying, and an addon never sees the throw. Note that the *registration*
methods on `FarmersDelightApi` (`registerCookingPotRecipe`, `unregisterCookingPotRecipe`,
`registerCuttingBoardRecipe`, `unregisterCuttingBoardRecipe`) already call `isAvailable()` internally and
really are silent no-ops; it is only the `FarmersDelightRecipes` query facade that omits the check. The same
trap applies to `FarmersDelightApi`'s scheduling methods; see the scheduling page.

```java
public static boolean matchesCookingPot(List<ItemStack> inputs, ItemStack container);
public static ItemStack cookingPotResult(List<ItemStack> inputs, ItemStack container);
public static boolean hasCuttingBoardRecipe(ItemStack input);
public static List<ItemStack> cuttingBoardResults(ItemStack input, ItemStack tool);

public static List<String> cookingPotRecipeIds();
public static RecipeInfo cookingPotRecipe(String id);
public static List<String> cuttingBoardRecipeIds();
public static RecipeInfo cuttingBoardRecipe(String id);
```

`cookingPotResult` and `cuttingBoardResults` return **clones**. That is deliberate: the underlying recipe holds
one live result stack, and handing it out unguarded would let a caller's `setAmount` corrupt every future cook
of that recipe. The clone means you may mutate what you get freely.

`cookingPotRecipeIds()` lists the default recipe map — built-in plus addon-registered. Recipes that exist only
inside a custom recipe *group* are not in it.

```java
for (String id : FarmersDelightRecipes.cookingPotRecipeIds()) {
    RecipeInfo info = FarmersDelightRecipes.cookingPotRecipe(id);
    if (info != null) {
        getLogger().fine(id + " ingredients=" + info.ingredients() + " results=" + info.results()
                + " time=" + info.cookTimeTicks() + " xp=" + info.experience());
    }
}
```

## RecipeInfo

```java
public record RecipeInfo(String id, String type, List<String> ingredients, List<String> tools,
                         ItemStack container, List<ItemStack> results, int cookTimeTicks,
                         double experience, String category) {

    public static final String TYPE_COOKING_POT = "cooking_pot";
    public static final String TYPE_CUTTING_BOARD = "cutting_board";
}
```

A read-only snapshot carrying only Bukkit and java types, so it crosses the supported API boundary.
`ingredients` and `tools` come back as id strings in the recipe-file syntax above — internal ingredient records
never leave the plugin. `container()` and `results()` clone on every access, and the list fields are immutable
copies, so nothing you do to a `RecipeInfo` can reach a live recipe.

Which fields are populated depends on `type()`:

| Field | `cooking_pot` | `cutting_board` |
| --- | --- | --- |
| `ingredients` | every ingredient spec | one entry: the input spec |
| `tools` | empty | the tool tags, always rendered with a leading `#` |
| `container` | the required bowl, or null | always null |
| `results` | one result | one or more results |
| `cookTimeTicks` | the cook time | always 0 |
| `experience` | the experience | always 0.0 |
| `category` | the category | always null |

Note that `RecipeInfo.TYPE_COOKING_POT` is the bare string `"cooking_pot"`, while the discovery type id for the
same station is the namespaced `"farmersdelight:cooking_pot"` on
`FarmersDelightRecipeDiscovery.TYPE_COOKING_POT`. They are different constants for different purposes — do not
substitute one for the other.
