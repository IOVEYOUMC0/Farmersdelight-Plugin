
[简体中文](../zh-cn/recipe-filler-matching.md)
# RecipeFiller and IngredientMatching

Two related pieces: the Fill button that moves a recipe's ingredients from a player's inventory into your
station, and the matcher that answers "do these slots satisfy these ingredients?" the same way the cooking pot
does.

## RecipeFiller

```java
@ApiStatus.OverrideOnly
public interface RecipeFiller {
    boolean fill(Player player, ViewableRecipe recipe);

    default boolean onBack(Player player);   // false
}
```

A filler is supplied per book-open, not per type:

```java
FarmersDelightApi.get().openRecipeBook(player, "brewinandchewin:keg", new KegRecipeFiller(posKey));
```

Construct a **fresh filler bound to the specific station** the book was opened from. The keg's filler takes the
keg's position key in its constructor; that is what makes Fill and Back target *that* keg rather than "some
keg". A shared singleton filler cannot know which station to fill.

Pass `null` instead when there is no station — the standalone book then has no Fill button at all.

### fill(Player, ViewableRecipe)

Called when the player clicks the detail page's `fill` slot. The button is only rendered when a filler was
supplied *and* the detail layout maps a `fill` slot *and* the recipe resolved.

Return `true` if anything was filled. Return `false` and FarmersDelight sends the player its
"missing ingredients" message — so `false` is the correct answer for "the player does not have the items", not
an error condition.

The call arrives on the **clicking player's region thread**, which on Folia is not the station's region thread.
That is the single most important fact about implementing `fill`: reading the player's inventory is free, but
writing into a block-backed inventory that another region ticks is not. BAC takes the keg's per-key lock around
the whole take-from-backpack → write-into-keg sequence so it is atomic against the fermentation ticker running
on the keg's own region.

**Implementations must be dupe-safe: remove from the player exactly what is placed.** The idiom is to take one
item at a time and place that same stack:

```java
private static ItemStack takeOne(PlayerInventory inventory, Predicate<ItemStack> match) {
    for (int i = 0; i < inventory.getSize(); i++) {
        ItemStack item = inventory.getItem(i);
        if (item != null && !item.getType().isAir() && match.test(item)) {
            ItemStack one = item.clone();
            one.setAmount(1);
            if (item.getAmount() <= 1) {
                inventory.setItem(i, null);
            } else {
                item.setAmount(item.getAmount() - 1);
                inventory.setItem(i, item);
            }
            return one;
        }
    }
    return null;
}
```

Removing first and placing second — never the reverse, and never "place, then try to remove" — is what keeps a
mid-operation failure from creating items.

### onBack(Player)

Called when the player clicks `back` on the **top-level page** of a book opened from your station. Reopen the
station GUI and return `true`; return `false` (the default) to let the book simply close.

Precisely when it fires: only from the list page, only when the book was opened as a single independent type
(which is what `openRecipeBook(player, typeId, filler)` does, and also what happens when exactly one type is
registered server-wide), and only when the book's navigation history is empty. Back from a detail page goes to
the list; back from a jumped-to detail goes to the recipe it was jumped from. `onBack` is the last stop, not
every back click.

```java
@Override
public boolean onBack(Player player) {
    return reopenKeg(player);
}
```

## IngredientMatching

```java
public final class IngredientMatching {

    public static <Slot, Ingredient> boolean matchesIngredients(
            List<Ingredient> required,
            List<Slot> slots,
            boolean exactSlots,
            BiPredicate<Slot, Ingredient> matcher,
            ToIntFunction<Slot> initialAmount);
}
```

A generic two-pass ingredient matcher, deliberately decoupled from Bukkit: you supply the `matcher` ("does this
slot satisfy this ingredient?") and `initialAmount` ("how many ingredient-units does this slot contribute?").
Because it touches no Bukkit types, it is unit-testable without a running server, and FarmersDelight ships tests
for it.

It is the exact logic the cooking pot and the keg use, so calling it is how an addon station gets matching
semantics that agree with FarmersDelight's rather than merely resembling them.

### exactSlots

| Value | Rule |
| --- | --- |
| `true` | The number of filled slots must **equal** the ingredient count — no leftover slots. |
| `false` | Extra filled slots are allowed **only** if each holds an item the recipe itself uses. A slot holding a foreign item still blocks the match. |

The two are meant to be tried in that order. The exact pass first means a precise recipe wins before a lenient
one can shadow it — beetroot soup in exactly 4 beetroot slots beats any recipe that would also tolerate those
4 slots. The lenient pass then covers the same ingredient spread over several slots, like rice in 3 slots for a
1-rice recipe, while still refusing to match when something unrelated is in the pot.

BAC's keg does exactly this:

```java
private boolean matchesIngredientsTwoPass(List<KegIngredient> required, List<ItemStack> present) {
    return matchesIngredients(required, present, true)
            || matchesIngredients(required, present, false);
}

static boolean matchesIngredients(List<KegIngredient> required, List<ItemStack> present, boolean exactSlots) {
    return IngredientMatching.matchesIngredients(
            required, present, exactSlots,
            (slot, ingredient) -> ingredient.test(slot), slot -> 1);
}
```

Note that the keg filters to non-empty stacks before calling, and gives every slot a budget of exactly 1. The
cooking pot instead passes `slot -> 1` for the exact pass and `ItemStack::getAmount` for the lenient one — the
exact pass must force one ingredient per filled slot, because that is what it later consumes.

### Why it is not greedy

The assignment is modelled as bipartite matching with augmenting paths (Kuhn's algorithm), not greedy first-fit.
This is not gold-plating: greedy first-fit is *wrong* whenever ingredient specs overlap. If a broad spec (a tag
or a choice) is listed before a narrower spec that is a subset of it, greedy lets the broad spec grab the only
slot the narrow one could have used, and a match that genuinely exists is missed. The matcher finds a feasible
assignment regardless of ingredient or slot order.

A slot whose `initialAmount` is *a* offers `min(a, requiredCount)` interchangeable units — no ingredient needs
more than one unit, and there are only `requiredCount` ingredients. A negative `initialAmount` is clamped to 0.

### Using it from a filler

`FDAddonTemplate`'s filler uses it to answer "can this inventory satisfy this recipe?" before touching anything:

```java
@Override
public boolean fill(Player player, ViewableRecipe recipe) {
    List<ItemStack> required = recipe.inputs();
    if (required == null || required.isEmpty()) {
        return false;
    }
    List<ItemStack> slots = new ArrayList<>();
    for (ItemStack content : player.getInventory().getContents()) {
        if (content != null) {
            slots.add(content);
        }
    }
    boolean canFill = IngredientMatching.matchesIngredients(
            required,
            slots,
            false, // exactSlots=false: extra, unrelated inventory items are allowed
            (slot, ingredient) -> FarmersDelightItems.matchesId(slot, FarmersDelightItems.idOf(ingredient)),
            ItemStack::getAmount);
    return canFill;
    // A real filler would now remove one matching item per ingredient and place it into the station.
}
```

Here both type parameters resolve to `ItemStack`: the inventory slots are the `Slot` type and the recipe's
already-resolved input stacks are the `Ingredient` type. In a real station the ingredients would be your own
ingredient objects with their own `test` method, as in the keg example above.

`FarmersDelightItems.idOf` / `matchesId` are the CraftEngine-aware id helpers — use them instead of raw
`Material` comparisons, or custom items with the same base material will match each other.
