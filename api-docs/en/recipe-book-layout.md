# RecipeBookLayout and opening a book

By default a registered `RecipeType` renders inside FarmersDelight's shared recipe book, laid out by the
server's `gui.yml` → `recipe-book-gui`. That is simple, but if several addons register types they all share one
category menu. Supplying a `RecipeBookLayout` from `RecipeType.listLayout()` / `RecipeType.detailLayout()` makes
FarmersDelight render **your** type as an independent book with your title, grid and decorations.

## The interface

```java
@ApiStatus.OverrideOnly
public interface RecipeBookLayout {
    Component title();
    int rows();
    List<String> layout();
    Map<Character, String> legend();

    default Map<String, ItemStack> decorations();      // Map.of()
    default int size();                                // rows() * 9
    default List<Integer> slotsByType(String role);
    default int firstSlotByType(String role);          // -1 when none
}
```

The default `slotsByType`/`firstSlotByType` derive slot indices from `layout()` + `legend()`, so a plain data
record is a complete implementation. Both real addons do exactly that:

```java
public record SimpleRecipeBookLayout(Component title, int rows, List<String> layout,
                                     Map<Character, String> legend, Map<String, ItemStack> decorations)
        implements RecipeBookLayout {
}
```

### title()

Used as-is. FarmersDelight does not resolve anything in it — if you want CraftEngine `<image:ns:id>` /
`<shift:N>` background glyphs, resolve them yourself before building the `Component`, since your addon is the
one with CraftEngine access.

**List titles** support two literal placeholders, substituted on every draw: `{page}` (1-based current page)
and `{total}` (page count). **Detail titles do not** — they are used verbatim.

### rows() and size()

`rows()` is 1–6 and `size()` is `rows() * 9`. The window is created at that size.

### layout() and legend()

`layout()` is one string per row, up to 9 characters each. Characters past column 8 are ignored, and a slot
index beyond `size()` is skipped. Each character is mapped through `legend()` to a **role name**. A character
with no legend entry is left empty.

### decorations()

Static items by role name. Items are used as-is (cloned when placed), so CraftEngine custom items are fine here.

## Roles

Two groups of roles behave differently, and getting this wrong is the most common layout bug.

**Dynamic roles** are filled by the book itself and are *never* painted from `decorations()` as static chrome:

```
category  recipe  ingredient  result  prev_page  next_page  fill  filter  switch  progress
```

**Everything else** — including `back`, `background` and any role of your own invention — is static decoration:
if `decorations()` has an entry under that role name, it is drawn into every slot mapped to it.

The subtlety: several dynamic roles are *buttons*, and when the book decides to place one it still looks the
item up in `decorations()` by role name. So `prev_page`, `next_page`, `fill`, `filter`, `filter_active` and
`switch` need entries in `decorations()` even though they are dynamic — the legend gives them a slot, the
decorations map gives them an item. `back` is different: it is static, so it is drawn by the chrome pass and
its slot is read back when handling clicks.

What each role does:

| Role | Page | Behaviour |
| --- | --- | --- |
| `recipe` | list | One recipe icon per slot. The number of `recipe` slots **is** the page size. |
| `prev_page` / `next_page` | list | Placed only when such a page exists. |
| `filter` | list | "Craftable only" toggle. Shown whenever the role has a slot. When active the book prefers the `filter_active` decoration and falls back to `filter`. |
| `switch` | list | Placed only when `RecipeType.switchTarget()` is non-null. |
| `back` | list, detail | Static item; clicking it navigates back. |
| `ingredient` | detail | Filled from `ViewableRecipe.inputs()` in order. |
| `result` | detail | First slot only, from `ViewableRecipe.result()`, with `infoLines` as lore. |
| `fill` | detail | Placed only when a `RecipeFiller` was passed to `openRecipeBook` **and** the recipe resolved. |
| `progress` | detail | Animated cooking chevron, see below. |
| *your own role* | detail | Filled from `ViewableRecipe.displaySlots()`. |
| `category` | — | Meaningless in an addon layout; see below. |

### The category menu is never yours

`category` appears in the dynamic-role list because the **shared** book uses it. The category menu is always
drawn from the server's `gui.yml`, never from a `RecipeBookLayout` — your layouts only cover the list and detail
pages. That is by design: an independent book skips the category menu entirely.

### The progress role

Any detail slot mapped to `progress` cycles FarmersDelight's chevron frames (`farmersdelight:0` …
`farmersdelight:20`, CraftEngine items) once per GUI tick while the detail page is open, matching
FarmersDelight's own recipe view. The animation ticks on the viewer's region thread and stops when the
inventory really closes. If the CraftEngine frame items are not loaded yet (mid CE reload), a light grey glass
pane stands in and the frames retry on a later tick.

You do not supply anything for this role — just map a character to it.

## A complete pair of layouts

From `FDAddonTemplate`'s `ExampleRecipeType`:

```java
@Override
public RecipeBookLayout listLayout() {
    return new Layout(
            Component.text("Example Recipes", NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false),
            6,
            List.of("RRRRRRRRR",
                    "RRRRRRRRR",
                    "RRRRRRRRR",
                    "RRRRRRRRR",
                    "RRRRRRRRR",
                    "PXXXBXXXN"),
            Map.of('R', "recipe", 'P', "prev_page", 'N', "next_page", 'B', "back", 'X', "background"),
            Map.of("background", filler(Material.GRAY_STAINED_GLASS_PANE),
                    "prev_page", named(Material.ARROW, "Previous"),
                    "next_page", named(Material.ARROW, "Next"),
                    "back", named(Material.BARRIER, "Close")));
}

@Override
public RecipeBookLayout detailLayout() {
    return new Layout(
            Component.text("Example Recipe", NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false),
            6,
            List.of("XXXXXXXXX",
                    "XIIIXTXRX",
                    "XXXXXXXXX",
                    "XXXXXXXXX",
                    "XXXXXXXXX",
                    "XXXXBXFXX"),
            Map.of('I', "ingredient", 'R', "result", 'T', "tool", 'F', "fill", 'B', "back", 'X', "background"),
            Map.of("background", filler(Material.GRAY_STAINED_GLASS_PANE),
                    "fill", named(Material.HOPPER, "Fill ingredients"),
                    "back", named(Material.ARROW, "Back")));
}

/** A plain data RecipeBookLayout; FD's default methods derive slot lookups from layout+legend. */
private record Layout(Component title, int rows, List<String> layout,
                      Map<Character, String> legend, Map<String, ItemStack> decorations)
        implements RecipeBookLayout {
}
```

That list page shows 45 recipes per page. `'T'` maps to `"tool"`, which is not a known role, so it is filled
from `ViewableRecipe.displaySlots().get("tool")`.

Build these from your own `gui.yml` rather than hard-coding them, so server owners can retheme the book. BAC
reads `KegRecipeBookConfig` from disk and swaps the layouts on reload:

```java
private volatile KegRecipeBookConfig book;

public void setBook(KegRecipeBookConfig book) {
    this.book = book;
}

@Override
public RecipeBookLayout listLayout() {
    KegRecipeBookConfig b = book;
    return b == null ? null : b.list();
}
```

Reading the volatile field into a local once per call is deliberate: a `/reload` can swap the config between
two reads.

## Opening a book

```java
public void openRecipeBook(Player player);
public void openRecipeBook(Player player, RecipeFiller filler);
public void openRecipeBook(Player player, String typeId, RecipeFiller filler);
public void openRecipeEditor(Player player, String typeId, String recipeId);
```

- `openRecipeBook(player)` — the shared book over all registered types, read-only (no Fill button).
- `openRecipeBook(player, filler)` — the shared book with a Fill button bound to your station.
- `openRecipeBook(player, typeId, filler)` — opens straight to that one type as an independent book, never the
  shared category menu. If `typeId` is not registered it **falls back to the shared book** rather than failing.
  Pass `null` for `filler` if there is no station to fill.

All of these open an inventory, so call them on the thread that owns the player (the region thread on Folia).
Calling from your own inventory-click handler for that player is already correct:

```java
if (raw == gui.recipeSlot()) {
    event.setCancelled(true);
    if (event.getWhoClicked() instanceof Player player) {
        FarmersDelightApi.get().openRecipeBook(player, BrewinConstants.BLOCK_KEG,
                new KegRecipeFiller(key));
    }
    return;
}
```

One more behaviour worth knowing: when exactly **one** `RecipeType` is registered on the whole server,
`openRecipeBook(player)` and `openRecipeBook(player, filler)` skip the category menu and open that type's list
directly. Do not rely on the category menu existing.

The book is read-only navigation: every click in it is cancelled before your code sees it, so no item can be
moved or duplicated out of a recipe book.
