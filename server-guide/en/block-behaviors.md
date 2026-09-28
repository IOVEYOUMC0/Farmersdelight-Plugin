---
icon: blocks
---

# Block behavior configuration

[简体中文](../zh-cn/block-behaviors.md)

FarmersDelight is a Paper/Folia port of Farmer's Delight. CraftEngine owns item, block, model, loot and
recipe data; FarmersDelight adds the stateful gameplay that CE does not provide by itself: workstations,
crops, soil, rope, mushroom colonies and their integrations. Addons use the same model.

## Where configuration belongs

| Change | File | Apply it |
| --- | --- | --- |
| Item, block, tag, loot or CE recipe | `plugins/CraftEngine/resources/farmersdelight/configuration/*.yml` | `/ce reload all` |
| Runtime tuning, permissions, performance, displays | `plugins/FarmersDelight/config.yml` | `/fd reload config` or restart |
| Pack-declared cooking-pot / cutting-board / special recipes and advancement trees | the `cooking_recipes`, `cutting_recipes`, `special_recipes` and `farmersdelight_advancements` sections of `plugins/CraftEngine/resources/<pack>/configuration/**.yml` | `/ce reload all` |
| The plugin's own recipe files | `plugins/FarmersDelight/recipes/*.yml` | `/fd reload recipes` |

CE YAML is deliberately comment-free. Keep an edited file as data only and use this page as the field
reference. Do not use Bukkit `/reload`; it leaves CE registries and scheduled work in an undefined state.

After `/ce reload`, cutting-board and skillet displays are rebuilt in small per-tick batches. Tune
`performance.reload-visual-refreshes-per-tick` in `config.yml` if you need a slower or faster recovery pass;
the default is 32 and it does not load chunks.

## Common block-list syntax

Behavior arguments such as `bottom-blocks`, `grow-on.blocks`, `connector-blocks` and
`unaffected-blocks` accept the same list form:

```yaml
some-blocks:
  - minecraft:dirt
  - farmersdelight:rich_soil
  - '#minecraft:mushroom_grow_block'
  - '#myaddon:fertile_soils'
```

- `minecraft:*` is a vanilla block.
- `namespace:id` is a CE custom block id.
- `#namespace:tag` is a tag. Quote it in YAML because an unquoted `#` starts a comment.
- Vanilla tags resolve through Bukkit; a CE block is matched against its `settings.tags` entries. Therefore a
  custom tag in `settings.tags` is useful to every FD behavior that accepts a block list.
- A list with explicit block states is supported where the behavior accepts normal CE soil rules, for example
  `minecraft:farmland[moisture=7]`.

## Grouped options

Several options that describe one concept may be written **grouped** or in the original flat form — both are read, and
the grouped value wins when both are present (`-` and `_` stay interchangeable in key names):

```yaml
- type: farmersdelight:stove
  crackle-sound: farmersdelight:block.stove.crackle   # single options stay flat
  burn:                                               # same as burn-enabled + burn-damage
    enabled: true
    damage: 1.0
  ignite:                                             # same as ignite-enabled + ignite-sound + fire-charge-sound
    enabled: true
    sound: minecraft:item.flintandsteel.use
    fire-charge-sound: minecraft:item.firecharge.use
  extinguish:                                         # same as extinguish-enabled + extinguish-sound + water-extinguish-sound
    enabled: true
    sound: minecraft:block.fire.extinguish
    water-sound: minecraft:entity.generic.extinguish_fire
```

Grouped options today (grouped form ← legacy flat keys):

| Behavior | Groups |
| --- | --- |
| `farmersdelight:stove` (and addon stoves reusing it) | `burn.{enabled,damage}`, `ignite.{enabled,sound,fire-charge-sound}`, `extinguish.{enabled,sound,water-sound}` |
| `brewinandchewin:fiery_fondue_pot` | `burn.{enabled,damage}` |
| `farmersdelight:cooking_pot` | `support.{display,require-non-full}`, `handle-toggle-sound.{sound,volume,pitch}`, `boil.{sound,soup-sound}`, `sound.{chance,volume,pitch-min,pitch-max}` |
| `farmersdelight:skillet` | `support.{display,require-non-full}` |
| `farmersdelight:organic_compost` | `light.{high-bonus,low-bonus,threshold}`, `water.{bonus}`, `activator.{bonus-per-neighbor}` |
| `farmersdelight:rich_soil` | `mushroom-colony.{brown,red}` |
| `farmersdelight:tall_crop`, `farmersdelight:wild_plant`, `farmersdelight:mushroom_colony` | `light.{requirement,max-requirement}`, `spawn-light.{requirement,max-requirement}`, `bone-meal.{is-target,age-bonus,overflow,success-chance,min-age-bonus,max-age-bonus,climb-chance}` |
| `farmersdelight:tall_crop` | `half.{property,lower-value,upper-value}`, `max-age.{lower,upper}`, `upper.{block,min-age}`, `harvest-tool.{tags,items}` |
| `farmersdelight:mushroom_colony` | `grow-on.{blocks,block-tags}`, `place-on.{blocks,block-tags,overrides-default}`, `harvest-tool.{tags,items}` |
| `farmersdelight:tatami` | `pair.{property,while-sneaking}` |

Everything else stays flat (`crackle-sound`, `tool-damage`, `add-food-sound`, `sizzle-sound`, `knife-sound`, `permission`,
`grow-speed`, `max-age` — the colony's single cap, unlike the tall crop's `max-age.{lower,upper}` —, `rich-soil-block`,
`requires-water`, `bottom-blocks`, `bottom-block-tags`, ...). `bottom-*` deliberately stays flat because CraftEngine's own
`bush_block` uses the same flat names, so a block carrying both behaviors keeps one shared spelling. CraftEngine's own
behaviors (`crop_block`, `bush_block`, `item_display`, `simple_storage_block`, ...) keep their own argument tables and are
unaffected.

## Mushroom colonies

```yaml
- type: farmersdelight:mushroom_colony
  mushroom-type: minecraft:brown_mushroom
  grow-on:
    blocks:
      - farmersdelight:rich_soil
      - farmersdelight:organic_compost
  place-on:
    block-tags:
      - minecraft:mushroom_grow_block
    overrides-default: false
```

`grow-on.blocks` and `grow-on.block-tags` decide where an existing colony advances its age. They do not
decide whether its item can be placed.

`place-on.blocks` and `place-on.block-tags` are the direct-placement rules. With
`place-on.overrides-default: false` they add valid supports to the original behavior: all
`minecraft:mushroom_grow_block` blocks, the `mushroom-colonies.placement.always-valid-supports` list in
`config.yml`, and any solid support at or below the configured light limit. Set the flag to `true` to make
the configured lists an exact whitelist. An empty exact whitelist allows no placement.

All four lists also read their old flat spellings (`grow-on-blocks`, `grow-on-block-tags`, `place-on-blocks`,
`place-on-block-tags`, `place-on-overrides-default`).

Other useful keys are `age-property`, `max-age`, `grow-speed`, `light.requirement`, `bone-meal.min-age-bonus`,
`bone-meal.max-age-bonus`, `harvest-tool.tags` and `harvest-tool.items` (the flat `light-requirement`,
`bonemeal-min-age-bonus`, `bonemeal-max-age-bonus`, `harvest-tool-tags` and `harvest-tool-items` are still read).

Rich Soil and Organic Compost already carry `minecraft:mushroom_grow_block` as CE tags, so the shipped
configuration both places colonies on them and keeps them growing. A custom support only needs that CE tag
to join the default placement rule; add it to `grow-on.*` as well when colonies should grow there.

## Rope

```yaml
- type: farmersdelight:rope
  placement-mode: vanilla
  connection-mode: restricted
```

`placement-mode` controls the connections made while placing a rope:

| Value | Meaning |
| --- | --- |
| `vanilla` | Full solid faces on horizontal placement; ropes, panes/bars and walls for vertical placement and reel-down. |
| `restricted` | Only ropes, panes/bars and walls. |
| `solid-face` | Any allowed full solid face on every placement. |

`connection-mode` controls later neighbor updates and ropes placed programmatically. It accepts
`restricted` (default) and `solid-face`.

`connector-blocks` replaces the default pane/bar/wall connector set when it is non-empty.
`connection-exceptions` replaces the default solid-face exclusions when present. Include every block/tag that
must not receive a solid-face connection; the original exclusion set is barriers, leaves, shulker boxes,
pumpkins and melons. Ropes always connect to other ropes regardless of these two lists.

## Rich Soil and crops

```yaml
- type: farmersdelight:rich_soil
  boost-chance: 0.2
  brown-mushroom-colony: farmersdelight:brown_mushroom_colony
  red-mushroom-colony: farmersdelight:red_mushroom_colony
  brown-mushroom-blocks: [minecraft:brown_mushroom, farmersdelight:brown_mushroom]
  red-mushroom-blocks: [minecraft:red_mushroom, farmersdelight:red_mushroom]
  unaffected-blocks: ['#farmersdelight:wild_crops']
```

`boost-chance` is the random-tick probability of a growth boost. `brown-mushroom-blocks` and
`red-mushroom-blocks` decide which mushrooms Rich Soil turns into colonies; both default to the two values
shown above. `unaffected-blocks` opts plants out of the growth boost.

`farmersdelight:rich_soil_farmland` uses `boost-chance`, `moisture-property` and `rich-soil-block`.
The moisture property must exist and be an integer property. Crops should normally combine CE's own
`crop_block` or `bush_block` with FD's `tall_crop` or `tomato_vine` behavior; sibling behaviors are
intentional and CE dispatches all of them.

## Tall crops and tomatoes

```yaml
- type: farmersdelight:tall_crop
  age-property: age
  half:
    property: half
  supporting-property: supporting
  grow-speed: 0.25
  light:
    requirement: 9
  requires-water: true
  upper:
    block: farmersdelight:rice_upper
```

`age-property` and `half.property` are required. The behavior validates their type at load time so a broken
definition is rejected instead of creating a crop that cannot grow or harvest. `supporting-property` is
optional. Configure `max-age.{lower,upper}`, `half.{lower-value,upper-value}`, `reset-on-harvest`,
`harvest-tool.{tags,items}`, `extra-planting-items` and standard `bottom-blocks` / `bottom-block-tags` as
needed; their flat spellings (`half-property`, `max-age-lower`, `half-lower-value`, `upper-block`,
`harvest-tool-tags`, ...) keep working.

For tomatoes, keep the three IDs consistent across every vine stage:

```yaml
- type: farmersdelight:tomato_vine
  blocks:
    budding: farmersdelight:budding_tomatoes
    tomatoes: farmersdelight:tomatoes
    crop-on-rope: farmersdelight:tomato_crop_on_rope
  min-light: 9
  max-stack-height: 3
```

The hanging stage's CE `bush_block.max-height` is the live climb cap when present. `max-stack-height` is its
fallback, so do not set conflicting values unless that fallback is intentional.

## Cooking pot and skillet

### Custom cooking pot example

Use the `farmersdelight:cooking_pot` behavior on the CraftEngine block and declare the recipe group and slot counts in `custom`:

```yaml
block:
  myaddon:custom_pot:
    behavior:
      - type: farmersdelight:cooking_pot
        custom:
          id: myaddon:custom_pot
          input-slots: 9
          pending-output-slots: 3
          output-slots: 3
          container-slots: 3
```

Use the same group id in the recipe file:

```yaml
custom_cooking_pot_recipes:
  myaddon:custom_pot:
    soup:
      ingredients:
        - minecraft:carrot
        - minecraft:potato
      result:
        id: minecraft:rabbit_stew
        count: 1
      cooking-time: 200
      container: minecraft:bowl
```

`custom.id` must match the group name under `custom_cooking_pot_recipes`. A custom pot can expose up to 54 input slots; its GUI still needs a layout under `recipe-view-gui.recipe-detail-cooking-pot-guis` in `gui.yml`, otherwise the default cooking-pot layout is used. Recipe items, tags and containers use FD's unified resolver, and addons may register recipes with `FarmersDelightApi.registerCookingPotRecipe`.

```yaml
- type: farmersdelight:cooking_pot
  permission: farmersdelight.use.cooking_pot
  place-tray-on-open: true
  support:
    display: true
    require-non-full: true
  handle-toggle-sound:
    sound: minecraft:block.lantern.place
    volume: 0.7
    pitch: 1.0
```

`support.display` controls the tray/handle renderer state for that individual station. Set it to `false` for a
custom model that has no matching support appearances. `support.require-non-full` keeps the tray state off above
full blocks; set it to `false` for a model intended to render over them. `place-tray-on-open` only applies to a
cooking pot and requests an immediate support-state refresh when its UI opens. Both `support.*` keys also accept
their old flat spellings (`display-support`, `require-non-full-support`).

### Return items for consumed ingredients

When cooking finishes and the pot consumes its ingredients, each ingredient's return item is resolved in this
order (`container-returns` is the highest-priority override, not the only source):

1. `container-returns` in `config.yml` — the key may be a CE custom id or a vanilla id;
2. the item's own CraftEngine `craft-remainder`: `fixed` always returns it, `recipe_based` matches the id of the
   recipe being cooked and falls back to its configured `fallback`;
3. the item's `use_remainder` component (vanilla semantics: what drinking/eating it leaves behind);
4. the vanilla crafting remainder of the item's material — for a CE item that is its base material's, so a custom
   drink built on `minecraft:honey_bottle` returns a glass bottle with no configuration at all;
5. the bucket/bottle fallback for containers vanilla declares no remainder for (milk/water/lava bucket → bucket,
   honey bottle → glass bottle).

An entry such as `farmersdelight:milk_bottle: minecraft:glass_bottle` is therefore usually unnecessary now; keep
one only to override the inference or to switch a return item off with `""`. Pot-specific extras (fish buckets,
stews, potions) can still be added under `cooking-pot.ingredient-remainders`.

### The required container of a pot recipe (inferred)

The `container:` field of a cooking pot recipe is now **optional**: when it is omitted, the plugin asks the
**result** item the same way, and the remainder it declares is the container the recipe needs (a soup result → a
bowl, a drink result → a glass bottle), because what a meal leaves behind when eaten is the container it is served
in. Hand-written recipes and addon packs therefore no longer have to repeat `container:` for every soup or drink,
and saving in the recipe editor writes the inferred container into the file explicitly so you can see and change
it later.

Overriding or disabling the inference:

| Value | Meaning |
| --- | --- |
| `container: minecraft:bowl` | Explicit container, overrides the inference; a custom item uses the map form |
| `container: none` (also `air` / an empty string) | Declares "this recipe needs no container", ignoring the result's remainder |

A **tool** remainder is never taken as a container (a corn dog's `minecraft:stick`, a ham's `minecraft:bone`); the
default exclusions live under `cooking-pot.container-inference.excluded-remainders` in `config.yml` and accept
vanilla or CraftEngine custom ids case-insensitively. `cooking-pot.container-inference.enabled: false` turns the
whole inference off, so every recipe must then state `container:` (or `container: none`) itself.

The inference happens only while **reading recipe files**; recipes registered through the API
(`FarmersDelightApi.registerCookingPotRecipe`) keep the container the caller passes. When anything is inferred the
boot log reports it as "Inferred the required container of N cooking pot recipe(s) from their result item" (a
`recipe` debug category line, so enable it under `debug.categories` to see it).

The handle sound is also per cooking-pot behavior. A value of `0` for its volume silences it. A `skillet`
behavior accepts `permission`, `support.display` and `support.require-non-full` with the same meanings, along
with its existing `add-food-sound` and `sizzle-sound` fields. These options belong in the CE block definition,
so differently-modelled custom stations can use different values.

### Stove

`farmersdelight:stove` requires the block to declare a boolean **`fire`** property (the name is fixed). A block
that declares the behavior without it, or with a property of that name that is not a boolean, fails its own load
naming the config node and the property — the same way `craftengine:crop_block` requires `age`. Both igniting and
extinguishing write that property, so without it a stove could never change its flame.

```yaml
- type: farmersdelight:stove
  crackle-sound: farmersdelight:block.stove.crackle
  burn:
    enabled: true
    damage: 1.0
  ignite:
    enabled: true
    sound: minecraft:item.flintandsteel.use
    fire-charge-sound: minecraft:item.firecharge.use
  extinguish:
    enabled: true
    sound: minecraft:block.fire.extinguish
    water-sound: minecraft:entity.generic.extinguish_fire
  tool-damage: 1
```

| Option | Default | Meaning |
| --- | --- | --- |
| `crackle-sound` | `farmersdelight:block.stove.crackle` | Occasional crackle while lit. |
| `burn.enabled` | `true` | Whether standing on the lit stove's grilling plate hurts. |
| `burn.damage` | `1.0` | Damage per burn. |
| `ignite.enabled` | `true` | Allow flint & steel / fire charge to light it. |
| `extinguish.enabled` | `true` | Allow a shovel / water bucket to put it out. |
| `ignite.sound` | `minecraft:item.flintandsteel.use` | Flint & steel sound. |
| `ignite.fire-charge-sound` | `minecraft:item.firecharge.use` | Fire charge sound. |
| `extinguish.sound` | `minecraft:block.fire.extinguish` | Shovel sound. |
| `extinguish.water-sound` | `minecraft:entity.generic.extinguish_fire` | Water bucket sound. |
| `tool-damage` | `1` | Durability a shovel / flint & steel loses per use. |

Every grouped option in that table also reads its original flat key (`burn-enabled`, `ignite-sound`,
`fire-charge-sound`, `water-extinguish-sound`, ...), and the grouped value wins when both are set, so an existing
resource pack needs no edit.

A sound id must look like `minecraft:block.fire.extinguish`; a typo fails the block's load with its config path
instead of silently falling back to the vanilla sound. The two states are exclusive: a lit stove only accepts a
shovel / water bucket, an unlit one only flint & steel / a fire charge, and a water bucket leaves an empty bucket
behind (nothing is consumed in creative).

These four interactions live in the behavior, matching the mod's `AbstractStoveBlock#tryToIgnite` /
`#tryToExtinguish`, so any addon stove that reuses the behavior (Ends Delight's End Stove, for example) gets them
without repeating four `events` handlers in its resource pack.

## Other registered behaviors

| Behavior | Role | Main configuration |
| --- | --- | --- |
| `basket` | Simple inventory block | Storage and display settings on its block. |
| `connected_rug`, `double_block`, `tatami` | Decorative connected/pair blocks | Property names, partner IDs and visual item settings. |
| `cooking_pot`, `cutting_board`, `skillet`, `stove` | Stateful cooking stations | Block behavior identifies the station and carries station-specific model/interaction options; recipes and shared tuning live in FD config/recipe files. |
| `organic_compost` | Compost conversion and mushroom source | Target Rich Soil id and colony ids. |
| `wild_plant`, `wild_rice` | Natural plants | Soil/water and spread settings. |
| `conditional_block_planting` | Item behavior, not a block behavior | Item `behavior.rules`, mapping a clicked CE block to the planted block. |

## Validate an edit

1. Keep a backup of the resource before editing.
2. Run `/ce reload all`.
3. Read the first behavior error, if any. It names the block node and the missing or wrong property.
4. Test the block in an unprotected area, then in the protection setup used by the server.

The shipped defaults target original-mod behavior. Prefer adding a new custom block id over changing the
definitions that existing worlds already use.
