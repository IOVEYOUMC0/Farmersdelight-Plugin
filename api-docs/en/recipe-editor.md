
[简体中文](../zh-cn/recipe-editor.md)
# RecipeEditor, EditableRecipe and NumericField

Returning a `RecipeEditor` from `RecipeType.editor()` makes your recipes editable in game through
FarmersDelight's generic editor GUI. FarmersDelight drives the GUI; **your addon owns the storage**. The editor
hands you an `EditableRecipe` draft to persist or delete, and never touches your files itself.

## RecipeEditor

```java
@ApiStatus.OverrideOnly
public interface RecipeEditor {
    List<String> itemSlotLabels();
    List<NumericField> numericFields();
    EditableRecipe load(String id);
    boolean save(EditableRecipe draft);
    boolean delete(String id);
}
```

### itemSlotLabels()

One label per editable item slot; the list size is the slot count. The labels are shown as lore on an
information item at the top of the editor.

**The GUI shows at most 6 item slots** (inventory slots 10–15). If you declare more labels than that, the extra
slots are neither shown nor written back — on save, only indices `0 .. min(6, labels) - 1` are committed and any
higher draft slot keeps the value `load()` put there. BAC's keg uses five: four ingredients plus a base-fluid
container. The template uses three.

### numericFields()

The editable number buttons. May be empty. They occupy consecutive slots starting at 28 and stop before slot 48,
so at most 20 fields are rendered; declare more and the tail is invisible.

### load(String id)

Build a draft. `id` is `null` or blank when the admin is creating a new recipe — return a blank draft then.
If you return `null`, FarmersDelight substitutes `new EditableRecipe(id, itemSlotLabels().size())`, so your
numeric defaults are lost; prefer returning a real draft.

Seed your numeric defaults here. A key that has never been set reads back as the field's `min()` in the GUI, so
an unseeded "cook time" button starts at its minimum rather than at a sensible default.

### save(EditableRecipe draft)

Called when the admin clicks Save. Before calling, FarmersDelight commits the GUI's item slots and result slot
into the draft. Write to your own storage, reload your recipe manager, and return `true`. Return `false` to
report failure — the GUI tells the player either way and then closes.

Validate here. The template rejects a draft with no id or no result:

```java
@Override
public boolean save(EditableRecipe draft) {
    if (draft == null || draft.id() == null || draft.id().isBlank() || draft.result() == null) {
        return false;
    }
    store.put(draft.id(), draft);
    return true;
}
```

`save` runs on the clicking player's region thread. BAC writes its YAML inline there, which is acceptable for a
rare admin action; do not do anything long-running.

### delete(String id)

Called when the admin clicks Delete. It is passed `draft.id()`, which may be `null` for a new recipe that was
never saved — handle that and return `false`.

## EditableRecipe

A mutable draft. Not an interface: construct it directly.

```java
public final class EditableRecipe {
    public EditableRecipe(String id, int itemSlotCount);

    public String id();
    public void setId(String id);
    public int itemSlotCount();
    public ItemStack item(int index);            // null when empty or out of range
    public void setItem(int index, ItemStack item);
    public ItemStack result();
    public void setResult(ItemStack result);
    public int resultCount();
    public void setResultCount(int resultCount); // clamped to >= 1
    public double number(String key, double fallback);
    public void setNumber(String key, double value);
}
```

The constructor pre-fills `itemSlotCount` null slots. `item`/`setItem` are index-safe: an out-of-range index
reads `null` and writes nothing rather than throwing. `setResultCount` clamps to at least 1.

Numbers are a `String` → `double` map, so read them back with the same keys you used in your `NumericField`s.
Keeping the keys in constants avoids the classic typo bug:

```java
private static final String COOK_TIME = "cook_time";
private static final String EXPERIENCE = "experience";

@Override
public List<NumericField> numericFields() {
    return List.of(
            new NumericField(COOK_TIME, "Cook time (ticks)", 20, 20, 100000, 0),
            new NumericField(EXPERIENCE, "Experience", 0.5, 0, 100, 1));
}

@Override
public EditableRecipe load(String id) {
    EditableRecipe draft = new EditableRecipe(id, itemSlotLabels().size());
    draft.setNumber(COOK_TIME, 200);
    draft.setNumber(EXPERIENCE, 1.0);
    draft.setResultCount(1);
    return draft;
}
```

One thing to know about the item stacks you get back in `save`: the editor is **copy-based**. Clicking an item
in the player's inventory puts a fabricated 1-count clone on the cursor, and placing it into an editor slot
stores that 1-count template. No real item is ever moved, so there is no dupe and no loss — but it also means
`draft.item(i).getAmount()` is always 1 and carries no meaning. Ingredient counts, if your format has them, must
come from somewhere else. The result's quantity comes from `resultCount()`, not from `result().getAmount()`.

### Identity and NBT retention

The clone is small, but the item's **identity** is preserved in full, and the three slot kinds save differently:

* **Input / tool / container slots** save an identity string, not an item snapshot: CraftEngine custom items
  store their CE item id (`farmersdelight:straw`), MMOItems items store `mmoitems:<type>:<id>`
  (e.g. `mmoitems:AXE:TEST`), everything else stores the vanilla id (`minecraft:stone_axe`); `#ns:tag` tags and
  `a|b` choices are supported too. These strings stay readable in the recipe file and are rebuilt back into
  display items when the editor reloads the recipe.
* **Result slots** save the **full item** (all components / NBT), serialized as an `{item, count, nbt}` object,
  so ordinary items carrying custom NBT (enchantments, custom names) survive the edit round-trip. **MMOItems
  items are the exception**: their identity is uniquely determined by `mmoitems:<type>:<id>`, so they are saved
  as that id string (`result: mmoitems:AXE:TEST` or `{item: mmoitems:AXE:TEST}`) and rebuilt through the
  MMOItems plugin API on load — no NBT snapshot is written for them.

Identity resolution order is: CE item id → `mmoitems:<type>:<id>` → vanilla id — mutually exclusive, first hit wins.

## NumericField

```java
public record NumericField(String key, String label, double step, double min, double max, int decimals) {
}
```

- `key` — the `EditableRecipe` number key.
- `label` — shown on the button.
- `step` — left-click adds it, right-click subtracts it.
- `min` / `max` — inclusive clamp applied after each click.
- `decimals` — digits after the point in the button text; `0` (or less) renders as a whole number.

BAC's keg fields:

```java
@Override
public List<NumericField> numericFields() {
    return List.of(
            new NumericField(FERMENT_TIME, "Ferment time (ticks)", 1200, 1, 100000, 0),
            new NumericField(EXPERIENCE, "Experience", 0.05, 0, 100, 2),
            new NumericField(TEMPERATURE, "Temperature (1 cold..3 normal..5 hot)", 1, 1, 5, 0),
            new NumericField(FLUID_AMOUNT, "Fluid amount (mB)", 250, 1, 100000, 0)
    );
}
```

Note the third one: an enum-like value modelled as a clamped integer field, since the editor has no dropdown
control. That is the idiomatic workaround.

## Opening the editor

```java
FarmersDelightApi.get().openRecipeEditor(player, "fdaddon:example", "example_stew");
```

Pass `null` for `recipeId` to create a new recipe. The call is a silent no-op when the type id is not
registered or when its `editor()` is `null`, so gate your admin command on your own state rather than on a
return value (there is none). Like every book call, it opens an inventory and must run on the player's thread.

Gate it behind a permission of your own — FarmersDelight does not check one for you here.

## Editor GUI layout, for reference

The editor is a fixed 54-slot window; you cannot re-lay it out. Knowing the slots helps when writing docs for
your own admins:

| Slot | Contents |
| --- | --- |
| 4 | Information item listing your `itemSlotLabels()` |
| 10–15 | Editable item slots (as many as you declared, up to 6) |
| 16 | Result item |
| 25 | Result-count button (left +1 / right −1) |
| 28 … 47 | Numeric field buttons, in `numericFields()` order |
| 48 | Save |
| 49 | Cancel |
| 50 | Delete |

A stable editor instance is worth keeping if you cache drafts in memory across opens, as the template does —
`RecipeType.editor()` is queried on every open, so returning a new instance each time discards anything the
previous one held.
