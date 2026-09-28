
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

**Preferred: declare the recipe in your own CraftEngine pack — no Java at all.** Put it in any YAML under
`<your pack>/configuration/`, with `cooking_recipes` / `cutting_recipes` / `special_recipes` as the root key;
CraftEngine hands the whole section to FarmersDelight while loading packs, and FD parses it, resolves the
items and merges it into its recipe table. The entry fields are exactly the ones FD's own `recipes/*.yml` uses:
only the root key differs.

```yaml
# craftengine/myaddon/configuration/farmersdelight/cooking_pot_recipes.yml
cooking_recipes:
  myaddon:cheese_soup:
    ingredients:
      - "myaddon:cheese"
      - "farmersdelight:onion"
    container: "minecraft:bowl"
    result: "myaddon:cheese_soup"
    experience: 0.35
    cook-time: 200
    category: meals
```

- The key **is** the recipe id, verbatim (FD does not prefix a namespace for you), so write the full
  `myaddon:cheese_soup` here.
- Load order is FarmersDelight's own recipe files, then packs, then runtime API registrations, with the later
  source winning an id clash. Special recipes work the same way and lose to the plugin file, the bundled
  defaults and API registrations.
- Run `/ce reload all` (or restart) after editing: pack content is read once, while CraftEngine loads packs.
  `/fd reload recipes` only re-reads `plugins/FarmersDelight/recipes/*.yml`.
- Reach for the Java path below only when a recipe has to be decided at runtime (a database, per-player or
  time-based content, data another plugin feeds in).

**When you do need runtime registration:** the result and container are `ItemStack`s, so CraftEngine items
must already be loaded. FarmersDelight itself defers its recipe load to `CraftEngineReloadEvent`; do the same
and re-register on every later CE reload. This is the shape of a hand-written registrar (BAC's cooking-pot
recipes used to be read this way; they now live in its pack, so treat this as API usage only):

```java
public final class ExampleCookingPotRecipes implements Listener {

    private final JavaPlugin plugin;
    private final Set<String> registeredIds = new LinkedHashSet<>();

    @EventHandler
    public void onCraftEngineReload(CraftEngineReloadEvent event) {
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
            ItemStack result = ExampleItems.create(resultId);
            if (result == null) {
                continue; // CraftEngine items not ready yet; a later CraftEngineReloadEvent retries.
            }
            result.setAmount(Math.max(1, section.getInt("result-count", 1)));
            String containerId = section.getString("container");
            ItemStack container = containerId == null ? null : ExampleItems.create(containerId);
            String recipeId = "myaddon:" + key;
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

The low-effort runtime route is FD's own `AddonRecipeFiles` helper: hand it the plugin, a source id, a
`recipes/*.yml` path and a namespace and it does all of the above — read the file, prefix bare keys with the
namespace, keep the previous set while CraftEngine items are missing and retry, and withdraw deleted ids (each
addon used to copy this and the copies drifted). Recipes it loads are still written back to that file by the
recipe editor; recipes that come from a pack are written into FD's own recipe file instead.

## Sections for an addon's own content

FarmersDelight reads its own four sections through this mechanism and exposes it:
`com.huidu.farmersdelight.api.pack.AddonPackSections` lets an addon claim sections of its own (keg fermenting,
grilling, skewering — anything FarmersDelight itself does not know about), handed over by CraftEngine while it
loads packs.

```java
// onLoad: must run before CraftEngine loads packs, which happens in its own onEnable
recipeSections = AddonPackSections.claim(this, "myaddon:recipes", "myaddon recipe sections",
        Map.of("grilling_recipes", "grilling_recipes", "skewering_recipes", "skewering_recipes"));

// in the reader that used to parse recipes/*.yml
for (AddonPackSections.Entry entry : AddonPackSections.entries(recipeSections, "grilling_recipes",
        "grilling_recipes", new File(getDataFolder(), "recipes/grilling_recipes.yml"))) {
    ConfigurationSection body = entry.section();   // entry.id() is the key, entry.source() locates errors
}
```

- The claimed id is the file's root key and must not collide with a section CraftEngine or another plugin
  already owns; a collision logs one warning and leaves the claim empty.
- Every claim gets its own loading stage: CraftEngine's loading pyramid keys tasks by stage, so sharing one
  would replace its owner's task.
- `entries(...)` layers a file from the plugin data folder on top of the pack (same id wins, position kept), so
  an operator or an in-game editor can still override the shipped defaults; pass `null` for no extra layer.
- Sections are read-only snapshots; do the item resolution in the reader, never inside the parser.

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
