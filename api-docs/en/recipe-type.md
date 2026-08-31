
[简体中文](../zh-cn/recipe-type.md)
# RecipeType and ViewableRecipe

A `RecipeType` is a titled, icon'd, registrable category of recipes. Register one and FarmersDelight can render
your addon's recipes in a recipe book without knowing anything about your internal recipe format. Brewin &
Chewin's keg is the reference implementation: `KegRecipeType` adapts `KegRecipe` objects the plugin already had.

## RecipeType

```java
package com.huidu.farmersdelight.api.recipe;

@ApiStatus.OverrideOnly
public interface RecipeType {
    String id();
    Component title();
    ItemStack icon();
    List<ViewableRecipe> recipes();

    default ViewableRecipe recipe(String id);
    default RecipeEditor editor();          // null
    default RecipeBookLayout listLayout();  // null
    default RecipeBookLayout detailLayout();// null
    default String switchTarget();          // null
}
```

`Component` is `net.kyori.adventure.text.Component`; `ItemStack` is `org.bukkit.inventory.ItemStack`.

### id()

The registry key. Registration is a map keyed on `id()`, so registering a second type with the same id
**replaces** the first. Namespace it. It is also half of every discovery storage key, so it must not contain a
space and renaming it orphans unlocks.

### title() and icon()

Both are used for the category button in the shared book's main menu. Be aware of what the menu does with them:
the icon is cloned, and its display name is then **overwritten with `title()`**. Any custom name you put on the
icon stack is discarded there. If `icon()` returns `null` or an air stack, the menu falls back to
`Material.BOOK`.

`title()` is also the window title of the list and detail pages **when you do not supply your own layout**. If
you supply a `listLayout()`/`detailLayout()`, that layout's own `title()` wins and `RecipeType.title()` is used
only for the category button.

### recipes()

A snapshot of the type's recipes, in display order. It is called on every list draw, on every page change, when
the "craftable only" filter is toggled, and when the recipe-discovery obtain index is rebuilt — so build it
cheaply and make it safe to call from any thread. BAC rebuilds a fresh `ArrayList` of adapter objects on each
call, which is fine; what is not fine is touching world state or blocking on disk here.

### recipe(String id)

The default implementation is a linear scan over `recipes()`. FarmersDelight calls it every time a detail page
is drawn, every time the Fill button is clicked, and on every jump. If your type has thousands of recipes,
override it with a map lookup.

Returning `null` for an unknown id is correct and is what the caller expects — a detail page for a missing id
renders as an empty page rather than throwing.

### editor()

Return a `RecipeEditor` to make the type editable through FarmersDelight's generic editor GUI; return `null`
(the default) for a read-only type. `FarmersDelightApi.openRecipeEditor` is a silent no-op when this is `null`.
See [RecipeEditor](recipe-editor.md).

### listLayout() / detailLayout()

`null` (the default) means "render me in FarmersDelight's shared book", which is driven by the server's
`gui.yml` → `recipe-book-gui`. Non-null means "render me as an independent book using my layout" — your title,
your grid, your decoration items, never piled into a shared category menu with other addons.
See [RecipeBookLayout](recipe-book-layout.md).

The two are queried independently. Supplying only `detailLayout()` gives you the shared list page and your own
detail page.

### switchTarget()

The id of a sibling type to jump to when the book's `switch` button is clicked — e.g. a keg toggling between
its fermenting recipes and its pouring recipes. The button is only rendered when **both** are true: your list
layout maps a slot to the `switch` role, and `switchTarget()` is non-null. Clicking it draws the sibling's list
at page 0. If the sibling id is not registered, the click does nothing.

For the toggle to round-trip, both types must point at each other.

## ViewableRecipe

```java
@ApiStatus.OverrideOnly
public interface ViewableRecipe {
    String id();
    List<ItemStack> inputs();
    ItemStack result();

    default List<Component> infoLines(Player viewer);        // List.of()
    default ItemStack icon();                                // result()
    default Map<String, List<ItemStack>> displaySlots();     // Map.of()
    default Map<String, JumpTarget> jumpTargets();           // Map.of()
    default boolean craftableBy(Player player);              // true
}
```

### id()

Unique within its `RecipeType`. Used for detail lookup, editor lookup, jump resolution — and as the
per-player discovery save key. **No spaces, and renaming it orphans unlocks.**

### inputs() and result()

`inputs()` are already-resolved concrete stacks, not ingredient specs. They are placed into the detail page's
`ingredient` slots in order and truncated to `min(ingredientSlots, inputs)` — extra inputs are silently
dropped, so give the layout enough ingredient cells.

`result()` goes into the **first** `result` slot. A `null` or air result renders as `Material.PAPER`.

### infoLines(Player viewer)

Extra lines shown in the detail view — cook time, experience, anything. They are applied as the **lore of the
result item**, replacing whatever lore that stack had, and forced non-italic. If the list is empty the result
item's own lore is left alone. The `viewer` parameter is there so you can localize per player.

### icon()

The stack shown in the list page. Defaults to `result()`. `null`/air falls back to `Material.PAPER`. When
recipe discovery is on and the recipe is locked, the locked placeholder replaces it entirely.

### displaySlots()

Extra display items keyed by a **custom role name**, for a type that supplies its own `detailLayout()`. The
detail view looks up every slot whose legend maps to that role and fills them index-wise with your list. Items
that are `null` or air are skipped, leaving whatever the chrome drew there.

The roles `ingredient` and `result` are already handled by `inputs()`/`result()` and need not be repeated. BAC's
keg uses `fluid`, `fluid_icon`, `fluid_level`, `output_fluid`, `temperature` and `ferment_info`.

If a layout maps several cells to one role and you only supply one item, only the first cell is filled. BAC
repeats the same stack once per cell to fill a wide gauge:

```java
private static List<ItemStack> repeat(ItemStack item, int count) {
    List<ItemStack> copies = new ArrayList<>(count);
    for (int i = 0; i < count; i++) {
        copies.add(item);
    }
    return copies;
}
```

### jumpTargets()

Makes a display role's slots clickable: clicking one opens another recipe's detail page, possibly in a
different `RecipeType`.

```java
@Override
public Map<String, JumpTarget> jumpTargets() {
    return Map.of("fluid", new JumpTarget("brewinandchewin:keg", producerRecipeId));
}
```

`JumpTarget` is `record JumpTarget(String typeId, String recipeId)`. The book resolves `typeId` against the
registry and `recipeId` against that type; if either is missing the click is ignored rather than erroring. A
role absent from the map (or mapped to `null`) is not clickable.

Jumps are checked **after** the `back` and `fill` slots, so a role sharing a slot with those buttons never
fires. A successful jump pushes the current page onto the book's history, so `back` returns to the recipe you
jumped from, and chains through multi-hop jumps.

Note that the roles are matched against the layout's slots, which means jump targets only work on a type with
its own `detailLayout()` mapping those roles.

### craftableBy(Player player)

Backs the book's optional "craftable only" filter. It is only called when the list layout has a `filter` slot
**and** the player has toggled the filter on — a type that never shows a filter button never sees this called.
When it is called, it is called for every recipe of the type on every list draw, on the viewer's region thread.
Reading `player.getInventory()` there is safe; anything expensive is not.

The default `true` means "always shown", which is the right answer for a type that cannot cheaply test an
inventory.

## Registering

```java
FarmersDelightApi.get().registerRecipeType(kegRecipeType);
```

```java
FarmersDelightApi.get().unregisterRecipeType(BrewinConstants.RECIPE_TYPE_KEG_FERMENTING);
```

Both are safe from any thread. A `null` type, or a type whose `id()` is `null`, is ignored silently.
Registration order is preserved and is the order categories appear in the shared menu. Both calls also
invalidate the recipe-discovery obtain index, so a newly registered type's recipes become auto-unlockable
immediately.

## A minimal type

```java
package com.example.fdaddon;

import com.huidu.farmersdelight.api.recipe.RecipeType;
import com.huidu.farmersdelight.api.recipe.ViewableRecipe;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import java.util.List;

public final class ExampleRecipeType implements RecipeType {

    @Override
    public String id() {
        return "fdaddon:example";
    }

    @Override
    public Component title() {
        return Component.text("Example Recipes", NamedTextColor.GOLD);
    }

    @Override
    public ItemStack icon() {
        return new ItemStack(Material.RABBIT_STEW);
    }

    @Override
    public List<ViewableRecipe> recipes() {
        // ExampleViewableRecipe is your own ViewableRecipe implementation, built from your config.
        return List.of(new ExampleViewableRecipe());
    }
}
```

With no layouts and no editor this type shows up as one category in FarmersDelight's shared book. Add
`listLayout()`/`detailLayout()` to break it out into its own book, and `editor()` to make it editable.
