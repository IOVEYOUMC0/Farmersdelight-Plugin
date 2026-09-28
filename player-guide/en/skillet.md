
[简体中文](../zh-cn/skillet.md)
# The Skillet

The **Skillet** is a two-in-one item: a fast frying pan you place on a heat source,
and a heavy, hard-hitting **melee weapon** you can swing in a pinch.

## Crafting a Skillet

Shaped recipe (crafting table):

```
[      ] [ iron ] [ iron ]
[      ] [ iron ] [ iron ]
[ brick ] [    ] [      ]
```

- `I` = `minecraft:iron_ingot` (×4)
- `B` = `minecraft:brick` (×1)

Result: `farmersdelight:skillet` ×1.

## Frying food

The skillet fries raw food the same way a campfire does — but **faster**.

1. **Place the skillet** as a block: hold it and **sneak + right-click** a surface.
   The skillet is laid flat, facing you.
2. Make sure a **heat source** sits directly below it (a lit Stove, lit Campfire,
   Magma Block, lava, etc. — see the Stove page for the full list). A hopper directly
   under the skillet also conducts heat from a source one block further down.
3. **Right-click the placed skillet with a raw food** that has a campfire recipe to
   drop it in. Right-click again with more of the same food to stack it up.
4. When an item finishes, the cooked result **pops out** of the pan on top for you to
   collect. It cooks item by item until the pan is empty.

Right-click the pan with an **empty hand** to take the contents back out. If the heat
source goes out, cooking progress cools down until you provide heat again.

**Faster than a campfire:** the skillet applies a cook-time reduction, so food fries
more quickly than it would on a campfire. If the skillet item is enchanted with **Fire
Aspect**, cooking is faster still — each level shaves more time off (down to a minimum
cook time).

## Handheld cooking

Hold a skillet in one hand and an ingredient with a campfire recipe in the other, stand near a heat source, and hold right-click. Completion consumes one ingredient from the other hand and gives the result, dropping overflow. Releasing right-click, changing items, swapping hands, dropping, using an inventory, dying, or disconnecting interrupts cooking. Unfinished ingredients remain in place and progress is discarded.

"Near a heat source" means any block in the 3x3x3 box around the **player's** block position is a heat source. Which block you right-click, which face you hit, and even right-clicking air make no difference, and being on fire counts too. The heat source itself is judged exactly as it is for a placed skillet, the pot, the tray and the stove: it must be **lit**, so an extinguished campfire or stove nearby is not enough (this differs from the mod's portable check, which reads the heat source block tag without the lit property).

Eating or drinking from the other hand prevents cooking from starting and interrupts an existing cook before progress advances. Vanilla consumption proceeds normally without an additional cooking debit. This applies with either hand holding the skillet.

Handheld and placed skillets share recipe times, the cooking multiplier, and the Fire Aspect bonus. With default settings and no enchantment, a 600-tick campfire recipe takes about 120 ticks (6 seconds at normal TPS). Placed skillets process progress every 4 ticks.

Progress damage and the cooking model are sent only to the cooking player every four ticks while progress display is enabled. With progress hidden, unchanged cooking models need no periodic packets. Single-slot and full inventory updates use the same progress snapshot during cooking. The server item keeps its actual damage and model, and holding right-click no longer triggers repeated swings. Attacking consumes actual durability, and breaking the tool cannot lose an ingredient. Completion and interruption remove the display override and refresh the actual item.

On 1.21.11+ servers the cooking display copy also uses `swing_animation: none` to suppress client-predicted swings when interacting with note-block appearances. Left-clicking or attacking interrupts cooking and restores the original item animation. The initial click may swing before the display reaches the client. Older clients lack this component; cancelling server events cannot suppress their predicted animation.

CE pack generation builds overlays from loaded vanilla, FD, addon, and custom item resources without a fixed ingredient list. Automatic overlays support untinted single-layer `item/generated` and `item/handheld` models, including inherited textures. Layered, tinted, conditional, and special 3D models use the fallback; supply an explicit `ingredient-models` entry for those. These are static layers, without the mod's tossing animation.

Servers can disable handheld cooking with `skillet.handheld.enabled` and its durability display with `skillet.handheld.progress-display.enabled`. Disabling cooking also skips automatic model generation and cache-folder merging on subsequent CE pack builds. Existing cache files remain; previously distributed packs change only after regeneration and redistribution. Run `/fd reload` after changing the setting and rebuild the CE pack after enabling it again. Model options belong directly to the CE item's behavior:

```yaml
behavior:
  type: farmersdelight:skillet_item
  permission: farmersdelight.use.skillet
  cooking-model: farmersdelight:skillet_cooking
  ingredient-overlay-model: farmersdelight:item/skillet_food
```

Handheld cooking is a generic item behavior. The assets above belong to the FD skillet; other items provide their own models. Omitting model settings keeps the item's normal appearance without disabling cooking.

`cooking-model` is the base and fallback item definition at `assets/<namespace>/items/<path>.json`. `ingredient-overlay-model` references a model under `models/` with a `#food` texture variable; its geometry and display transforms determine food placement. Optional `ingredient-models` entries map ingredient IDs to complete item definitions and take precedence over generation. Remove `ingredient-overlay-model` to disable automatic food overlays.

Set `"hand_animation_on_swap": false` at the root of custom cooking item definitions to suppress the item-swap animation when progress components change. Generated models and FD's fallback already include it.

Existing servers need the updated behavior configuration and template assets, followed by resource-pack generation and distribution. Replacing the FD JAR alone does not overwrite existing CE configuration. Generated assets are stored in FD's `generated/handheld-cooking` directory and merged by CE during packing.


## Using the Skillet as a weapon

Held in your hand, the skillet is a serious blunt weapon. Its combat stats:

- **Attack damage: 8**
- **Attack speed: 0.9** (slow, heavy swings)
- **Knockback: +1** (an extra shove on hit)

That damage is on par with the strongest swords, at the cost of a slow swing and extra
knockback that sends enemies flying. It's a fun, thematic sidearm as well as a cooking
tool.

> The skillet's item tooltip reminds you it is **placeable while sneaking** — that's
> how you set it down to fry rather than swinging it.

## Tips

- Set a skillet on a lit stove for a quick, always-on fryer next to your cooking pots.
- Fire Aspect on the skillet is worth it if you fry in bulk — it noticeably cuts cook
  time.
- In an emergency, you don't need a sword: the skillet in hand hits for 8 and knocks
  mobs back.
- Because placing needs a sneak-click, you can right-click a placed skillet normally
  to add food without accidentally picking it up.
