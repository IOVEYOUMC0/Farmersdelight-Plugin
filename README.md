---
icon: weight-scale
---

# FarmersDelight Admin Wiki

A CraftEngine-based port of the Forge mod **Farmer's Delight** for Paper/Folia servers. This page documents the runtime configuration knobs and the blocks/items/effects that ship out of the box.

***

## Table of contents

* [Plugin layout](./#plugin-layout)
* [Master config (`config.yml`)](./#master-config-configyml)
* [CraftEngine block definitions](./#craftengine-block-definitions)
* [Effects (Comfort / Nourishment)](./#effects-comfort--nourishment)
* [Buff bossbar API + PAPI placeholders](./#buff-bossbar-api--papi-placeholders)
* [Recipe GUI (`gui.yml`)](./#recipe-gui-guiyml)
* [Commands](./#commands)
* [Migration notes](./#migration-notes)

***

## Plugin layout

| Folder                                                             | Contents                                                                                                                                      |
| ------------------------------------------------------------------ | --------------------------------------------------------------------------------------------------------------------------------------------- |
| `plugins/FarmersDelight/config.yml`                                | Master server-tuning config. Most runtime gameplay knobs live here.                                                                           |
| `plugins/FarmersDelight/gui.yml`                                   | Cooking-pot / cutting-board / recipe-book GUI layout.                                                                                         |
| `plugins/FarmersDelight/lang/*.yml`                                | Server-side text (log / console / chat).                                                                                                      |
| `plugins/CraftEngine/resources/farmersdelight/configuration/*.yml` | The CE-side block / item / recipe / loot table definitions. **Block behavior parameters live here as `behavior:` args**, not in `config.yml`. |

CraftEngine yml files intentionally contain **no comments** — they are pure data. All explanation lives here in the wiki and in the Java behavior class docstrings.

***

## Master config (`config.yml`)

Key sections, ordered by likelihood of editing:

### `bossbar:`

Master toggle + visual styling for the FD-rendered bossbars (used by Comfort / Nourishment locally and by addon plugins like BrewinAndChewin remotely).

```yaml
bossbar:
  enabled: true
  layout-mode: stacked            # stacked | rotating
  rotation-interval-ticks: 80     # rotating mode only
  styles:
    nourishment: {color: GREEN, overlay: PROGRESS}
    comfort:     {color: BLUE,  overlay: PROGRESS}
```

`color` accepts `PINK / BLUE / RED / GREEN / YELLOW / PURPLE / WHITE`. `overlay` accepts `PROGRESS / NOTCHED_6 / NOTCHED_10 / NOTCHED_12 / NOTCHED_20`. Case-insensitive, `-`/`_` interchangeable.

### `cooking-pot.heat-sources:`

List of blocks that count as a heat source under a pot/skillet. Vanilla materials by name + optional `states`; CE custom blocks by `craftengine-id`. `tray: true` makes the heat source render a tray underneath even when `heat_source: false`.

### `cutting-board:`

* `interaction-mode: stacking | offhand` — pick how players add items to the board.
* `recipe-only-placement: false` — when true, only items matching a cutting recipe are accepted.
* `display-overrides:` per-item position/scale/style override for the on-board item display.

### CraftEngine food settings and functions

Built-in pet-food behavior and food-to-buff assignments live with the items in
`plugins/CraftEngine/resources/farmersdelight/configuration/items.yml`.

```yaml
settings:
  farmersdelight:pet_food:
    entities: [WOLF]
    require-tamed: true
    restore-health: true

events:
  - on: consume
    functions:
      - type: farmersdelight:nourishment
        duration: 180
```

Use `farmersdelight:comfort` in the same event format for Comfort. The main `config.yml` keeps only global
buff mechanics, persistence and display settings. Legacy `pet-foods` and food-duration sections remain a
runtime fallback for upgraded servers, but new content should use the CraftEngine item configuration.

### `loot-injection.install-datapack:`

Auto-install the FD vanilla loot-table injection datapack on first enable. Set to `false` if you're managing loot tables manually or via another plugin.

***

## CraftEngine block definitions

Block-specific parameters live next to their block definition in CE yml under `behavior: - type: farmersdelight:<id>`. Examples:

### `farmersdelight:rich_soil_farmland`

```yaml
behavior:
- type: farmersdelight:rich_soil_farmland
  boost-chance: 0.08
```

Boost-chance is the per-random-tick probability of applying bonemeal to the plant above. **Triggers only when hydrated** (water source in the 9×9×2 box around the block, or rain). 0.08 = original mod's vanilla rate.

### `farmersdelight:rich_soil`

```yaml
behavior:
- type: farmersdelight:rich_soil
  boost-chance: 0.08
  brown-mushroom-colony: farmersdelight:brown_mushroom_colony
  red-mushroom-colony:   farmersdelight:red_mushroom_colony
```

### `farmersdelight:tomato_vine`

```yaml
behavior:
- type: farmersdelight:tomato_vine
  tomatoes-block: farmersdelight:tomatoes
  crop-on-rope-block: farmersdelight:tomato_crop_on_rope
  mature-age: 7    # ground tomato; rope sections override to 0
```

Implements `RandomTickBlock` + `BonemealableBlock` so both the random-growth path and the player-bonemeal path attempt to climb a rope above. Composite dispatch in CE means `crop_block` still advances the age in the same event.

### `farmersdelight:canvas_rug`

```yaml
behavior:
- type: farmersdelight:canvas_rug
  scale: 1.0
  translation: [0.0, 0.0625, 0.0]
  rotation: [0.0, 0.0, 0.0]
  visual-item: farmersdelight:canvas_rug
```

The block itself uses an empty CE model — the visible appearance is drawn by an `ItemDisplay` overlay spawned by `RugDisplayListener` on place. Re-skin the rug by pointing `visual-item` at a different CE item; tune the size via `scale`. See [Migration notes](./#migration-notes) for upgrading from the legacy furniture-based rug.

### `farmersdelight:tall_crop`

```yaml
behavior:
- type: farmersdelight:tall_crop
  extra-planting-items:
    - farmersdelight:rice_panicle
  grow-speed: 0.25
  light-requirement: 6
  requires-water: true
```

A two-block-tall crop. `extra-planting-items` (also accepted as `extra_planting_items` / `extraPlantingItems`) is the list of items that plant this crop when used on valid soil, in addition to the crop's own seed item. The remaining keys tune growth: `grow-speed` is the per-random-tick advance chance, `light-requirement` the minimum light level, and `requires-water: true` gates growth on nearby water.

### Stove burn damage

Configured per stove **block** in CE yml:

```yaml
behavior:
- type: farmersdelight:stove
  burn-enabled: true
  burn-damage: 1.0
```

Burns any non-sneaking, non-creative entity standing directly above a lit stove. Uses Bukkit's standard entity-damage cooldown — calling it every tick produces vanilla-magma cadence (\~2 dmg/sec).

***

## Effects (Comfort / Nourishment)

FD's two custom buffs are tracked in `EffectManager`:

* **Comfort** — periodic auto-heal regardless of hunger level. Heal interval configurable via `buff.comfort.heal-interval-ticks` (0 = disable healing but keep the display).
* **Nourishment** — pauses hunger drain (saturation-only healing still applies).

Both are registered as `CustomBuff` entries so vanilla `milk_bucket` (clears all custom buffs) and `farmersdelight:milk_bottle` (clears one — non-low-priority first) work through a unified registry — see below.

### CustomBuff API (for addons)

Addons register their own buffs by implementing `com.huidu.farmersdelight.api.buff.CustomBuff` and calling `CustomBuffRegistry.register(...)` in their `onEnable`. The interface provides `id()`, `isActive(Player)`, `remove(Player)`, plus optional `level(Player)`, `remainingSeconds(Player)`, `nameKey()`, and `isLowPriority()` for the milk-bottle priority gate. See `BrewinChewinPlugin.registerCustomBuffs` for a four-buff example.

***

## Buff bossbar API + PAPI placeholders

If `PlaceholderAPI` is installed, FD registers an expansion under the `farmersdelight` identifier. Placeholders:

| Placeholder                                | Returns                                                    |
| ------------------------------------------ | ---------------------------------------------------------- |
| `%farmersdelight_buff_count%`              | Number of currently-active registered buffs on the player. |
| `%farmersdelight_buff_<ns>_<id>_active%`   | `1` or `0`.                                                |
| `%farmersdelight_buff_<ns>_<id>_level%`    | Current effective level.                                   |
| `%farmersdelight_buff_<ns>_<id>_time%`     | Remaining seconds.                                         |
| `%farmersdelight_buff_<ns>_<id>_time_fmt%` | `m:ss` (or `h:mm:ss` past an hour).                        |
| `%farmersdelight_buff_<ns>_<id>_name%`     | Translated display name (server locale).                   |

`<ns>_<id>` is the buff id with `:` replaced by `_`. Built-in ids: `farmersdelight_comfort`, `farmersdelight_nourishment`. Addons see their registered ids automatically.

Example BetterHud / MythicHud row:

```
condition: "%farmersdelight_buff_brewinandchewin_tipsy_active% == 1"
text: "&e%farmersdelight_buff_brewinandchewin_tipsy_name% Lv.%farmersdelight_buff_brewinandchewin_tipsy_level% [%farmersdelight_buff_brewinandchewin_tipsy_time_fmt%]"
```

***

## Recipe GUI (`gui.yml`)

`plugins/FarmersDelight/gui.yml` defines the layout of the cooking-pot, cutting-board and recipe-book GUIs — sizes, background images, and the item in each slot (`back`, `close`, `filter`, page arrows, and so on).

### Back / close button command

The `back` (and `close`) item accepts an optional list of commands that run one tick after the menu closes:

```yaml
back:
  material: BARRIER
  name: "<gray><lang:gui.recipe.back>"
  commands:
    - "[player] menu open hub"
    - "[console] tellraw {player} {\"text\":\"Returning to the hub\"}"
```

* The fields `command`, `commands` and `cmd` are all read and merged, so any of them works; each accepts a single string or a list.
* Prefix an entry with `[console]` to run it as the console, or `[player]` (the default) to run it as the clicking player. `console:` and `player:` are accepted as equivalents. A leading `/` is optional and stripped.
* Placeholders: `{player}`, `{player_name}`, `%player%`, `%player_name%` (player name), `{uuid}` (player UUID) and `{world}` (the player's world name).
* Dispatch is deferred by one tick after the GUI closes, and is skipped if the player logged off during that tick. Reopening another menu must go through this command list rather than a raw open call, because an inventory cannot be opened during the click event itself.
* These fire when the list was opened via `/fd recipe cooking_pot` or `/fd recipe cutting_board`.

***

## Commands

| Command                         | Permission                      | Purpose                                                                                                                      |
| ------------------------------- | ------------------------------- | ---------------------------------------------------------------------------------------------------------------------------- |
| `/fd recipe [book\|pot\|board]` | `farmersdelight.command.recipe` | Open the recipe book / specific GUI.                                                                                         |
| `/fd reload all\|config\|gui`   | `farmersdelight.admin`          | Hot-reload the matching config slice.                                                                                        |
| `/fd cleanup`                   | `farmersdelight.admin`          | Strip orphaned plugin state from loaded chunks.                                                                              |
| `/fd rug-migrate`               | `farmersdelight.admin`          | Replace pre-0.x canvas\_rug furniture entities with the new block-form rug. Run **once** after upgrading; safe to re-invoke. |

***

## Migration notes

### Legacy canvas\_rug (furniture → block)

The rug was originally a CE `furniture_item`, anchored by an interaction entity + an item-display element. Recent versions converted it to a real CE block (thin slab) whose visual lives on an `ItemDisplay` controlled by `CanvasRugBlockBehavior` args. Existing furniture rugs in pre-upgrade worlds remain as orphan entities until cleaned.

**Upgrade steps**:

1. Update both FarmersDelight and the resource pack to the new version.
2. Restart the server (so the new block definition is loaded).
3. Run `/fd rug-migrate` once. The command sweeps every loaded world, removes legacy `canvas_rug` furniture entities, and places an equivalent block at each position.

Unloaded chunks are untouched until they're loaded again — run `/fd rug-migrate` periodically or after large world tours if you have many pre-upgrade rugs across chunks rarely visited.

### `boost-chance` change for rich\_soil\_farmland

The default boost rate for rich soil farmland was 0.20 in older releases (legacy tuning). The current default is **0.08** to match the original mod. Existing servers with a hand-edited value of 0.20 keep their value — to revert to vanilla rate, set:

```yaml
behavior:
- type: farmersdelight:rich_soil_farmland
  boost-chance: 0.08
```

### Tomato-vine bonemeal climb

The bonemeal climb attempt used to ride a custom listener with a 30% trigger chance. It now rides CE's `BonemealableBlock` dispatch — the climb attempts every successful bonemeal use, with success still gated by light, column height, and rope-presence checks inside `tryClimb`. No config change needed.

### Stove burn damage

Burn used to fire on `PlayerMoveEvent`/`EntityMoveEvent` — so standing still on a lit stove dealt no damage. It now runs from `StoveManager.tickStove` and damages every tick the entity is above a lit stove (rate-limited by Bukkit's per-entity damage cooldown to \~2 dmg/sec, same as vanilla magma). No config change needed.
