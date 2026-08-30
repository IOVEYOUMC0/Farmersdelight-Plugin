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
| Cooking-pot, cutting-board and special recipes | FD/addon recipe configuration | `/ce reload all` |

CE YAML is deliberately comment-free. Keep an edited file as data only and use this page as the field
reference. Do not use Bukkit `/reload`; it leaves CE registries and scheduled work in an undefined state.

After `/ce reload`, cutting-board and skillet displays are rebuilt in small per-tick batches. Tune
`performance.reload-visual-refreshes-per-tick` in `config.yml` if you need a slower or faster recovery pass;
the default is 32 and it does not load chunks.

## Common block-list syntax

Behavior arguments such as `bottom-blocks`, `grow-on-blocks`, `connector-blocks` and
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

## Mushroom colonies

```yaml
- type: farmersdelight:mushroom_colony
  mushroom-type: minecraft:brown_mushroom
  grow-on-blocks:
    - farmersdelight:rich_soil
    - farmersdelight:organic_compost
  place-on-block-tags:
    - minecraft:mushroom_grow_block
  place-on-overrides-default: false
```

`grow-on-blocks` and `grow-on-block-tags` decide where an existing colony advances its age. They do not
decide whether its item can be placed.

`place-on-blocks` and `place-on-block-tags` are the direct-placement rules. With
`place-on-overrides-default: false` they add valid supports to the original behavior: all
`minecraft:mushroom_grow_block` blocks, the `mushroom-colonies.placement.always-valid-supports` list in
`config.yml`, and any solid support at or below the configured light limit. Set the flag to `true` to make
the configured lists an exact whitelist. An empty exact whitelist allows no placement.

Other useful keys are `age-property`, `max-age`, `grow-speed`, `light-requirement`,
`bonemeal-min-age-bonus`, `bonemeal-max-age-bonus`, `harvest-tool-tags` and `harvest-tool-items`.

Rich Soil and Organic Compost already carry `minecraft:mushroom_grow_block` as CE tags, so the shipped
configuration both places colonies on them and keeps them growing. A custom support only needs that CE tag
to join the default placement rule; add it to `grow-on-*` as well when colonies should grow there.

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
  half-property: half
  supporting-property: supporting
  grow-speed: 0.25
  light-requirement: 9
  requires-water: true
  upper-block: farmersdelight:rice_upper
```

`age-property` and `half-property` are required. The behavior validates their type at load time so a broken
definition is rejected instead of creating a crop that cannot grow or harvest. `supporting-property` is
optional. Configure `max-age-lower`, `max-age-upper`, `half-lower-value`, `half-upper-value`,
`reset-on-harvest`, `harvest-tool-tags`, `harvest-tool-items`, `extra-planting-items` and standard
`bottom-blocks` / `bottom-block-tags` as needed.

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
  display-support: true
  require-non-full-support: true
  handle-toggle-sound: minecraft:block.lantern.place
  handle-toggle-sound-volume: 0.7
  handle-toggle-sound-pitch: 1.0
```

`display-support` controls the tray/handle renderer state for that individual station. Set it to `false` for a
custom model that has no matching support appearances. `require-non-full-support` keeps the tray state off above
full blocks; set it to `false` for a model intended to render over them. `place-tray-on-open` only applies to a
cooking pot and requests an immediate support-state refresh when its UI opens.

The handle sound is also per cooking-pot behavior. A value of `0` for its volume silences it. A `skillet`
behavior accepts `permission`, `display-support` and `require-non-full-support` with the same meanings, along
with its existing `add-food-sound` and `sizzle-sound` fields. These options belong in the CE block definition,
so differently-modelled custom stations can use different values.

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
