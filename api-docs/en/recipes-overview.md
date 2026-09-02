
[简体中文](../zh-cn/recipes-overview.md)
# The recipe package

`com.huidu.farmersdelight.api.recipe` is the largest part of the FarmersDelight addon API. It covers three
separate jobs that are easy to confuse:

1. **Contributing recipes to FarmersDelight's own stations** — your addon's dishes get cooked by the real
   cooking pot, your items get cut on the real cutting board. See
   [Cooking pot and cutting board recipes](recipe-registration.md).
2. **Showing your own recipes in a recipe book** — your addon has its own station (a keg, a churn, a press)
   with its own recipe format, and you want FarmersDelight to render it. See [RecipeType](recipe-type.md),
   [RecipeBookLayout](recipe-book-layout.md), [RecipeFiller and IngredientMatching](recipe-filler-matching.md).
3. **Letting admins edit those recipes in game** — see [RecipeEditor](recipe-editor.md).

Per-player lock/unlock state for everything above is [recipe discovery](recipe-discovery.md).

## What is in the package

| Type | Kind | You... |
| --- | --- | --- |
| `RecipeType` | interface, `@ApiStatus.OverrideOnly` | implement it |
| `ViewableRecipe` | interface, `@ApiStatus.OverrideOnly` | implement it |
| `RecipeBookLayout` | interface, `@ApiStatus.OverrideOnly` | implement it (a `record` is enough) |
| `RecipeEditor` | interface, `@ApiStatus.OverrideOnly` | implement it |
| `RecipeFiller` | interface, `@ApiStatus.OverrideOnly` | implement it |
| `EditableRecipe` | final class | construct and read/write it |
| `NumericField` | record | construct it |
| `JumpTarget` | record | construct it |
| `RecipeInfo` | record | receive it |
| `IngredientMatching` | final utility class | call `matchesIngredients` |
| `FarmersDelightRecipes` | final class, `@ApiStatus.NonExtendable` | call its statics |
| `FarmersDelightRecipeDiscovery` | final class, static-only | call its statics |

`@ApiStatus.OverrideOnly` on the five interfaces means FarmersDelight calls them, you do not. Implement them
and hand the instance to the API; never call another addon's `RecipeType.recipes()` yourself, and do not
assume the interface will not gain new `default` methods in a later revision — it may, and your
implementation keeps compiling because they are defaults.

`FarmersDelightRecipes` and `FarmersDelightRecipeDiscovery` are `final` with private constructors and only
static members. Nothing in the package is meant to be subclassed by an addon except the five interfaces.

## Guarding your integration

Every entry point goes through `FarmersDelightApi.get()`. Guard it:

```java
FarmersDelightApi api = FarmersDelightApi.get();
if (!api.isAvailable()) {
    getLogger().warning("FarmersDelight not available; addon features disabled.");
    return;
}
if (!api.hasFeature("recipes")) {
    return;
}
```

`isAvailable()` is true only when FarmersDelight is present *and* enabled. `hasFeature("recipes")` is the
feature id that covers recipe registration, linked-result queries, recipe books and editors. Feature ids are
never removed once published, so probing an id an older build does not know simply returns `false`.

On a build predating `hasFeature` / `apiVersion` the call itself throws `NoSuchMethodError`. If you support
those builds, catch it on the first probe and treat it as revision 0.

## Threading

FarmersDelight supports Folia, so thread affinity matters.

- **Registration** (`registerRecipeType`, `unregisterRecipeType`, `registerCookingPotRecipe`,
  `registerCuttingBoardRecipe` and their `unregister` counterparts) is backed by concurrent/synchronized maps
  and is safe from any thread.
- **Opening a GUI** (`openRecipeBook`, `openRecipeEditor`) ends in `Player.openInventory`, so it must run on
  the thread that owns the player — the region thread on Folia. Calling it from an inventory-click handler or
  an interact handler for that same player is already correct.
- **Your interface implementations** are called back by FarmersDelight on the viewer's region thread while a
  book or editor is open, so reading that player's inventory inside `craftableBy`, `fill` or `infoLines` is
  safe. The one exception is `RecipeType.recipes()` and `ViewableRecipe.id()/result()/inputs()`, which the
  recipe-discovery obtain index also walks from whichever thread triggered the obtain. Keep those methods
  cheap, allocation-only and free of world access.

## Lifecycle

Register a `RecipeType` in `onEnable`. Before CraftEngine content is ready, registration stores the type and
the warmup pass indexes its recipe results. If the registered type later replaces its recipe collection,
call `refreshRecipeType(type.id())`. `findRecipesProducing(item)` then returns FD and registered-addon
`JumpTarget`s from reverse indexes without scanning recipe collections on each click.

Cooking-pot and cutting-board recipes are different: their `ItemStack` result and container have to exist, so
they must be registered once CraftEngine items are loaded. FarmersDelight itself defers its recipe load to
`CraftEngineReloadEvent`; register yours from the same event and re-register on every later CE reload.

Unregister on `onDisable` so a `/plugman`-style unload does not leave a dead type in the book:

```java
@Override
public void onDisable() {
    if (FarmersDelightApi.get().isAvailable()) {
        FarmersDelightApi.get().unregisterRecipeType("fdaddon:example");
        FarmersDelightApi.get().unregisterCookingPotRecipe("fdaddon:example_stew");
        FarmersDelightApi.get().unregisterCuttingBoardRecipe("fdaddon:example_cut");
    }
}
```

## Id conventions, and one hard rule

Type ids and recipe ids are plain strings. Namespace them (`brewinandchewin:keg`, `fdaddon:example`) so they
cannot collide with another addon's.

**Neither a type id nor a recipe id may contain a space.** Recipe discovery stores unlock state as the single
string `"<typeId> <recipeId>"` and splits it at the first space. A space in either id corrupts every unlock of
it. The same storage rule means **renaming a recipe id orphans every player's unlock of that recipe** — see
[recipe discovery](recipe-discovery.md) before you ship an id you might want to change.
