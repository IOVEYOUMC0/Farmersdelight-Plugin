# The Cutting Board

The **Cutting Board** lets you process a single item with a tool — slicing meat and
fish into portions, stripping logs, turning crops into their parts, and more. It is
the cheap, early-game workstation you'll use constantly.

## Crafting a Cutting Board

Shaped recipe (crafting table):

```
[ stick ] [ planks ] [ planks ]
[ stick ] [ planks ] [ planks ]
```

- `S` = `minecraft:stick`
- `P` = any plank (`#minecraft:planks`)

Result: `farmersdelight:cutting_board` ×1.

## Using it

1. Place the cutting board like any block.
2. **Right-click with an item** to lay that item on the board (one item is placed from
   your hand).
3. **Right-click again with a valid tool** to process the item. The result pops off
   the board as a dropped item.
4. **Right-click with an empty hand** to take the item back off the board.

If the item on the board has a cutting recipe but you're holding the wrong tool,
you'll get an action-bar hint telling you the tool is wrong. If the item has no cutting
recipe at all, you're told there's no recipe.

### Which tools work

The board accepts:

- **Knives** — the `#farmersdelight:knives` tag (Flint, Iron, Golden, Diamond and
  Netherite knives). Knives are the primary cutting-board tool and unlock the most
  recipes.
- **Axes** — `#minecraft:axes`
- **Pickaxes** — `#minecraft:pickaxes`
- **Shovels** — `#minecraft:shovels`
- **Shears** — `minecraft:shears`

Each recipe specifies which tool it needs. For example, slicing raw meat and fish
needs a knife; some recipes call for shears or an axe instead. Check `/fd recipe` and
open the **Cutting Board Recipes** list to see the tool each recipe wants.

### Results and chance

A recipe can have several results, and each result can have a **chance** to drop. Some
by-products (like extra straw from rice) only appear part of the time.

**Fortune helps:** if your cutting tool has the Fortune enchantment, each output unit
gets a bonus chance to drop (+10% per Fortune level). Fortune never pushes a result
above its normal maximum count — it just makes the chancy extras more reliable.

### Tool durability

Processing an item costs your tool **1 durability** per cut (in Survival). A tool that
would break on the cut is used up with the usual break sound. Unbreakable and
indestructible tools aren't consumed.

## Stacking mode

Depending on server configuration, the board may allow **stacking**: right-clicking
with more of the same item adds to the stack already on the board (up to the board's
stack limit), rather than being limited to a single item. When stacking is off, the
board holds one item at a time. Each cut processes one item from the stack.

## Placing tools on the board (carving)

If you **sneak + right-click** the empty board with a tool in your main hand, the tool
itself is laid on the board (a decorative "carving" placement) instead of being used to
cut. Right-click with an empty hand to take it back.

## Comparator output

The cutting board emits a **redstone comparator signal** based on how full it is
relative to its stack limit. A single non-stacking item (such as a tool) reads full
strength; one unit of a 64-stacking item reads the minimum.

## Tips

- Keep a Knife on your hotbar — it is the go-to cutting-board tool and is cheap to
  make (see the Knives section of the recipe list).
- Slicing raw meat/fish on a board usually yields more portions than the whole item,
  stretching your food supply.
- Enchant a knife with Fortune to squeeze extra by-products out of recipes that have
  chance-based results.
- Use `/fd recipe` → **Cutting Board Recipes** whenever you're unsure what an item
  turns into or which tool to use.
