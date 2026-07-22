# FarmersDelight Wiki

**English** | [中文](README.zh.md)

[![bStats](https://bstats.org/signatures/bukkit/FarmersDelight.svg)](https://bstats.org/plugin/bukkit/FarmersDelight/32571)

> This plugin uses [bStats](https://bstats.org) to collect anonymous usage statistics (server / player counts, server software and version distribution, and so on). Server owners can opt out globally in `plugins/bStats/config.yml`.

FarmersDelight is a Farmer's Delight-style gameplay plugin built on CraftEngine 26.5. CraftEngine handles custom items, blocks, furniture, models, font images, and tags, while this plugin wires those resources into actual playable server-side logic.

## 1. What the Plugin Does

This plugin adds and implements the following systems:

- Cooking Pot: 6 ingredient slots, container slot, pending-output slot, output slot, cooking progress, world particles, packed drops, content hints; hopper interaction can be enabled via config.
- Cutting Board: place ingredients, process with knives/tools, probability-based multi-output, recipe viewing; configurable whether off-hand knives / off-hand ingredients are allowed.
- Stove: cooks using vanilla campfire recipes, with CE item display.
- Skillet: cooks using vanilla campfire recipes, with CE item display; by default does not conduct heat through hoppers.
- Crops and harvesting: rice, wild rice, tall crops, mature harvesting, knife harvesting of straw.
- Knife loot: killing adult mobs with a knife yields extra drops such as ham, leather, feathers, etc., with configurable chance and looting bonus.
- Food effects: pet foods, comfort effects, nourishment effects, container returns.
- Recipe viewing GUI: cooking pot and cutting board recipe lists, detail pages, tag/multi-choice ingredient display.
- Recipe editing GUI: admins can visually create, edit, and delete cooking pot and cutting board recipes in-game, with support for tag ingredients (reverse tag lookup by item + exclusion editing), choice (`a|b`) ingredients, and custom cooking pot slots; opening without a recipe ID opens the recipe list, where clicking an entry edits it.
- Advancement system: Farmer's Delight-style advancements based on UltimateAdvancementAPI (optional dependency), with toasts and chat messages localized to the player's client language and using CE item icons; automatically disabled when that plugin is not installed, without affecting other features.

## 2. Installation Requirements

- Java 21
- Paper / Purpur 1.21+
- CraftEngine 26.5
- Optional dependencies: UltimateAdvancementAPI (advancement system), AuraSkills (cooking pot experience rewards). When the corresponding plugin is not installed, that feature is automatically disabled without affecting the rest of the plugin. The Minecraft versions supported by the advancement feature are determined by the installed UltimateAdvancementAPI build (the bundled builds cover 1.21.x and 26.1.x).

The plugin's internal scheduling adapts to Paper and Folia via a scheduler adapter; on Folia it prefers the global, region, or entity scheduler. On Folia it is recommended to validate cooking pot / cutting board containers, block storage, display synchronization, and the async save flow on a test server.

Installation steps:

1. Place `farmersdelight-1.0.0.jar` into the server's `plugins` directory.
2. Install CraftEngine 26.5.
3. Start the server. On first startup, the plugin automatically releases the bundled CraftEngine resource configuration pack to `plugins/CraftEngine/resources/farmersdelight`, and on every subsequent startup it restores any deleted/missing files (see `craftengine-resources.auto-completion` below).
4. Wait for the plugin configuration files to be generated.
5. After modifying the config, you can use `/fd reload`; after modifying the jar, CraftEngine resources, or resource pack, a full server restart is recommended.
6. If you need the advancement system, separately install the UltimateAdvancementAPI plugin (optional).

### 2.1 CraftEngine Resource Auto-Completion

The CraftEngine resources bundled with this plugin are released as a full pack to `plugins/CraftEngine/resources/farmersdelight` on first startup, and on every subsequent startup any deleted/missing files are automatically restored:

```yaml
craftengine-resources:
  auto-completion: true
```

If you manually deleted some of the bundled configs and do not want them added back, set this to `false` (the first full-pack release is not affected by this switch).

Generated files:

```text
plugins/FarmersDelight/config.yml
plugins/FarmersDelight/gui.yml
plugins/FarmersDelight/recipes/cooking_pot_recipes.yml
plugins/FarmersDelight/recipes/cutting_board_recipes.yml
```

Block runtime data for cooking pots, cutting boards, stoves, and skillets is all stored in CraftEngine BlockEntity data.

The default `config.yml` is organized by feature ownership: cooking pot options are under `cooking-pot`, cutting board under `cutting-board`, skillet under `skillet`, and stove under `stove`. The scattered paths from older versions are still read as a compatibility fallback, but new configuration should use the new paths in the default file.

Debug logging is configured under `debug`. Keep it off on a normal server; when troubleshooting, you need to enable both the master switch and specify categories. Leaving `categories` empty produces no category debug logging; to temporarily enable everything, write `all` or `*`.

```yaml
debug:
  enabled: true
  categories:
    - cooking_pot
```

## 3. What to Configure on the CraftEngine Side

The CraftEngine side is responsible for "what the resources look like, what they are called, and which tags they have." This plugin calls these resources by CE ID.

You typically need to prepare in the CraftEngine resource pack:

- Items: knives, ingredients, cooked food, containers, GUI icons, advancement icons, etc.
- Blocks/furniture: cooking pot, cutting board, stove, skillet, tray, rice, wild crops, mushroom colonies, ropes, tatami, etc.
- Tags: e.g. `farmersdelight:knives`, `farmersdelight:heat_sources`.
- Language: item names and lore should be written in CE's language/display configuration; the plugin GUI prefers the item's own display name.
- Image fonts: tags such as `<image:farmersdelight:cooking_pot_gui>` and `<image:farmersdelight:recipelist>` used in `gui.yml` must exist on the CE resource side.

The block behavior IDs this plugin registers:

```text
farmersdelight:cooking_pot
farmersdelight:cutting_board
farmersdelight:skillet
farmersdelight:stove
farmersdelight:tall_crop
farmersdelight:wild_rice
farmersdelight:wild_plant
farmersdelight:tomato_vine
farmersdelight:mushroom_colony
farmersdelight:rich_soil
farmersdelight:rich_soil_farmland
farmersdelight:organic_compost
farmersdelight:rope
farmersdelight:tatami
farmersdelight:upper_half_loot_relay
```

It also registers one **item** behavior, `farmersdelight:conditional_block_planting`, and two **event functions**, `farmersdelight:comfort` and `farmersdelight:nourishment`. Those are covered in 3.10.

In the CE block or furniture configuration you must attach the corresponding behavior to the corresponding resource. For example, attach `farmersdelight:cooking_pot` to the cooking pot, `farmersdelight:cutting_board` to the cutting board, and `farmersdelight:stove` to the stove. The CE configuration syntax follows the CraftEngine 26.5 resource configuration.

Per CraftEngine's design, a custom block inherits **nothing** from the vanilla block it is disguised as — not its block tags, not its behavior. Anything a feature needs (`minecraft:dirt`, `minecraft:climbable`, `farmersdelight:heat_sources`, …) has to be written out explicitly on your own block.

### 3.1 Attaching Block Behaviors

Behavior parameters are written under `behavior` in the CraftEngine block/furniture resource. A common form is shown below; the exact indentation follows the CE resource file structure:

```yaml
behavior:
  - type: farmersdelight:cooking_pot
    permission: farmersdelight.use.cooking_pot
```

If the same resource also has a CE-native behavior or another plugin's behavior attached, simply append it to the `behavior` list. CraftEngine accepts both `behavior:` and `behaviors:` as the section name, and both spellings appear in this plugin's own resource files.

**Parameter names are hyphen-only.** CraftEngine does not normalise `-` and `_`; a key is looked up by its exact spelling. A few CE-native options accept two spellings because their parser explicitly asks for both, but this plugin's behaviors do not, apart from the three exceptions listed below. Writing `boil_sound`, `burn_enabled`, `tool_tags`, `max_stack_amount`, `data_key`, `rich_soil_block`, `pair_while_sneaking`, `bottom_blocks` and so on is **not an error** — the key is ignored and the default is used. Use hyphens everywhere.

The only parameters in this plugin that accept more than one spelling:

| Parameter | Accepted spellings |
| --- | --- |
| `tall_crop` bone meal bonus | `bone-meal-age-bonus`, `bone_meal_age_bonus` |
| `tall_crop` extra planting items | `extra-planting-items`, `extra_planting_items`, `extraPlantingItems` |
| `comfort` / `nourishment` conditions | `condition`, `conditions` |

**Nothing validates unknown keys.** No behavior in this plugin rejects, warns about, or logs a key it does not recognise. A typo produces a block that loads cleanly and quietly behaves as if you had configured nothing, so the only symptom is the default taking effect in game. When a parameter "does not work", suspect its spelling first. The one way a mistyped key can still be fatal is indirect: mistyping a property-name key such as `age-property` falls back to the default property name, which then has to exist on the block — see 3.2.

**How values are read.** Most parameters are lenient about type, but the failure modes point in different directions and none of them produce a message. The exception is the property-name parameters listed in 3.2: their value is resolved against the block's declared properties, and a name that matches none of them is an error that costs the block its whole behavior list — see 3.2 for what that actually looks like.

| What you write | How it is read |
| --- | --- |
| Unquoted `true` / `false` | Always correct. Use this form. |
| Quoted `"true"` / `"false"` | Accepted by most boolean parameters. A few read a strict boolean and discard the string, keeping the default: `requires-water` on `wild_rice`, and `require-matching-lower-half` / `require-matching-block` on `upper_half_loot_relay`. |
| `yes`, `on`, `off` on a boolean parameter | Read as **`false`**, whatever the word means in English. Only the literal `true` is truthy. |
| `0` / `1` on a boolean parameter | A number is the wrong type, so the parameter falls back to **its default**. On a parameter that defaults to `true`, `burn-enabled: 0` therefore means *enabled* — the opposite of what a string gives you. |
| A number that does not parse (`sound-chance: high`) | Silently replaced by the default. The block still loads. |
| An empty value (`grow-speed:` with nothing after it) | Counts as omitted. |

### 3.2 Traps That Cause Silent Misbehaviour

These are the mistakes that produce a working-looking block with wrong behavior. They are worth reading before the parameter tables.

- **Omitting a parameter is not always the same as writing its apparent default.** Several defaults are *derived* rather than constant: `tall_crop`'s `max-age-lower` is the top of the `age` property's declared range, and `max-age-upper` is that minus one — on a `0~7` crop, omitting them gives `7` and `6`, not `4` and `3`. Others make omission genuinely different from writing the number: `sound-chance: 0` silences the cooking pot, while deleting the key gives `0.1`.
- **The shipped values are not always the code defaults.** `farmersdelight:rich_soil` ships with `boost-chance: 0.2`, while the code default is `0.08` — deleting the line slows the shipped block down by more than half.
- **A flag can gate less than its name suggests.** `is-bone-meal-target: false` on `tall_crop` only skips *that behavior's* bone meal branch. If the same block also carries CE's `crop_block`, CE's own bone meal path is untouched and the crop still grows. This is why the shipped `farmersdelight:budding_tomatoes` carries **both** `is-bone-meal-target: false` and `bone-meal-age-bonus: 0` on its `crop_block`; the zeroed bonus is what actually neutralises it, and the redundant-looking pair must not be "cleaned up".
- **`bone-meal-age-bonus: 0` does not disable bone meal on `tall_crop`.** That behavior clamps the bonus to at least 1, so a zero still advances the crop one stage. It is the opposite of CE's `crop_block`, where zeroing is the way to neutralise bone meal.
- **An empty list means "omitted", not "none".** `tool-tags: []` on a cutting board does not remove tag-based tools; it restores the four built-in defaults. There is no supported way to write "no tags" — use a tag ID that matches nothing.
- **Configuring a list replaces the built-in list wholesale.** You cannot add `minecraft:swords` to the cutting board's tools without re-listing the four defaults, and configuring `bottom-blocks` on a crop discards its entire built-in soil fallback.
- **Adding `custom:` to a cooking pot reshuffles the slot indices**, even when the four slot counts match the defaults. The default layout is inputs 0-5, pending 6, container 7, output 8; a custom layout always lays them out input → pending → output → container, so container and output swap. A `gui.yml` layout written against the default ordering will point at the wrong slots.
- **Changing `data-key` orphans everything already stored.** Cooking pots and cutting boards read their contents from that sub-key. Change it on a block type that already exists in the world and every placed one reads as empty. Choose it once, when you define the variant, and never edit it afterwards.
- **Nine behaviors require a state property, and a block that lacks it loses its behavior — not its existence.** This is the single most common way to be sent debugging the wrong thing. CraftEngine catches the error, prints one line naming the file, the config node and the missing property, and then substitutes a plain do-nothing behavior. The block still registers, still places, still shows every state you defined, still drops its loot, still holds its item. What is gone is everything the behavior did. So the in-game symptom is not "my block vanished", it is "my custom stove is just a block now" — it never lights, never burns anyone, never heats a pot above it, and stays that way silently until the next reload. The error at load is the only announcement you get; go and look for it. Only that one block is affected — the rest of the pack loads normally.
  - The substitution replaces the **whole** `behavior:` list, not the entry that failed. A block carrying four behaviors with one bad property loses all four, along with CraftEngine's own automatic `waterlogged` and rotation/mirror handling. One typo can therefore flatten a block that mostly looked fine.
  - *Name configurable, but a property of that name must exist:* `tall_crop` (`age-property`, an int, and `half-property`, a `double_block_half`), `mushroom_colony` (`age-property`, an int), `rich_soil_farmland` (`moisture-property`, an int), `organic_compost` (`composting-property`, an int), `tatami` (`facing-property`, and `paired-property` as a boolean), `upper_half_loot_relay` (`half-property`).
  - *Name fixed, with no parameter to point it elsewhere:* `stove` needs a boolean `fire`, `wild_rice` a `double_block_half` named `half`, and `tomato_vine` an int named `age`.
  - The renaming parameters rename what the behavior looks for; they do not make it optional. A configured name that resolves to no property is an error, **not** a quiet fall back to the default name.
  - Existence and type are both checked, except for the two properties whose values are compared as text: `tatami`'s facing property and `upper_half_loot_relay`'s half property are looked up by name alone, so any property type whose values spell out the direction or half names is accepted.
- **Exactly two property lookups are optional, and no others.** `tall_crop`'s `supporting-property` — the shipped `farmersdelight:rice` declares no such property and expresses its supporting stage as a max age value instead — and `rope`'s four connection properties. In those two places a missing (or wrong-typed) property is skipped rather than reported, so a typo there costs you one feature instead of the whole behavior list — and with no error line at all.
- **A missing item ID can silently disable an interaction.** `mushroom_colony` without a valid `mushroom-type` returns before it changes anything, so the colony simply cannot be harvested.
- **The same parameter name can mean different things on different behaviors.** `light-requirement: 0` on `mushroom_colony` means "skip the light check", while on `tall_crop` it is a real comparison. `requires-water` is lenient on `tall_crop` and strict on `wild_rice`. Read the table for the behavior you are actually configuring.

### 3.3 Cooking Pot Behavior

A default cooking pot only needs the behavior attached:

```yaml
behavior:
  - type: farmersdelight:cooking_pot
```

Available parameters:

| Parameter | Required | Default | Effect |
| --- | --- | --- | --- |
| `permission` | No | `farmersdelight.use.cooking_pot` | Permission required to open the cooking pot. Write an empty string to disable this plugin's permission check. |
| `open-while-sneaking` | No | `false` | Whether sneak-right-click also opens the cooking pot. When off, sneak interactions are yielded to other behaviors, so a held block places normally. |
| `place-tray-on-open` | No | `true` | Whether to sync/place the tray display under the pot when opening it. Also gated by `tray.enabled` in `config.yml`; with that master switch off, the tray never appears. |
| `boil-sound` | No | `farmersdelight:block.cooking_pot.boil` | Ambient sound while cooking with no finished meal waiting. |
| `soup-boil-sound` | No | `farmersdelight:block.cooking_pot.boil_soup` | Ambient sound once a meal is pending or displayed. |
| `sound-chance` | No | `0.1` | Probability of playing the boiling sound on each effect tick. `0` silences it; deleting the key does not. |
| `sound-volume` | No | `0.5` | Boiling sound volume. |
| `sound-pitch-min` | No | `0.9` | Lower bound of the random pitch. |
| `sound-pitch-max` | No | `1.1` | Upper bound. If it is not greater than the minimum, the pitch is fixed at the minimum. |
| `data-key` | No | `farmersdelight:cooking_pot` | Sub-key under which the pot's contents are stored, both in the CE BlockEntity and in the dropped pot item. See the trap in 3.2 before changing it. |
| `custom` | No | Not enabled | Custom slot counts, recipe group, and GUI title. |

The four sound parameters fall back to the built-in sound when the value is empty or unparseable, so there is no way to write a silent pot; use `sound-chance: 0` instead.

The pot itself reads nothing else from the behavior section. Interaction cooldown, progress display, hopper interaction and the tray master switch all live in `config.yml`, and heat comes from the `heat-sources` list (section 9) — the block *under* the pot must be listed there. The shipped pot declares a `facing` property for its model; the behavior does not read it, so a pot without `facing` still works and only loses its orientation. Comparator output is provided automatically and reflects the fill of every slot, not just the meal slot.

As shipped:

```yaml
behavior:
  - type: farmersdelight:cooking_pot
    boil-sound: farmersdelight:block.cooking_pot.boil
    soup-boil-sound: farmersdelight:block.cooking_pot.boil_soup
    sound-chance: 0.1
    sound-volume: 0.5
    sound-pitch-min: 0.9
    sound-pitch-max: 1.1
```

Custom cooking pot example:

```yaml
behavior:
  - type: farmersdelight:cooking_pot
    data-key: farmersdelight:large_pot
    custom:
      id: large_pot
      title: "<image:farmersdelight:large_cooking_pot_gui>"
      input-slots: 9
      pending-output-slots: 2
      output-slots: 3
      container-slots: 2
```

`custom` parameter descriptions:

| Parameter | Required | Default | Effect |
| --- | --- | --- | --- |
| `id` | No | Not set | Custom cooking pot ID. In the recipe file, use `custom_cooking_pot_recipes.<id>` to write recipes specific to this pot; `gui.yml` can override the interface using cooking pot, recipe detail, and recipe editor GUI configs of the same name. |
| `title` / `gui-title` | No | Not set | The title used when opening the GUI, usually written as an image font. `gui-title` is only read when `title` is absent or blank. |
| `input-slots` | No | `6` | Number of ingredient slots. Values below `1` are raised to `1`. |
| `pending-output-slots` | No | `1` | Number of pending-output slots. Products that are cooked but not yet moved into the output slots are temporarily held here. Values below `1` are raised to `1`. |
| `output-slots` | No | `1` | Number of finished-product output slots. Values below `1` are raised to `1`. |
| `container-slots` | No | `1` | Number of container slots, e.g. for bowls or bottles. `0` is allowed and removes the container slot. |

Writing a `custom` section at all switches the pot to a custom layout, even a section that only sets `id`. The slots are then allocated as one run in the order input → pending → output → container, which is not the default ordering — see 3.2.

When `custom` is not written, the default cooking pot layout is used, which does not affect the original cooking pot.

A complete custom large pot configuration typically has four entry points, and `id` must stay consistent. Below uses `large_pot` as an example:

1. The CE block behavior, which determines this pot's actual slots and recipe group ID:

```yaml
behavior:
  - type: farmersdelight:cooking_pot
    custom:
      id: large_pot
      input-slots: 9
      pending-output-slots: 2
      output-slots: 3
      container-slots: 2
```

2. The pot's own GUI in `gui.yml`, which determines the slot layout the player sees when opening the pot:

```yaml
cooking-pot-guis:
  large_pot:
    title: "<white><offset><icon><shift:-1600>Large Cooking Pot"
    fillers-enabled: true
    rows: 5
    layout:
      - "XIIIIIPBX"
      - "XIIIIIPBX"
      - "RXXXXXXXO"
      - "XXHXXCCOO"
      - "XXXXXXXXX"
    legend:
      I: ingredient
      H: heat
      C: container
      P: progress
      B: buffer
      O: output
      R: recipe
      X: decoration
    items:
      background:
        material: GRAY_STAINED_GLASS_PANE
        item_model: "air"
        hide-tooltip: true
        name: " "
      decoration:
        material: BROWN_STAINED_GLASS_PANE
        name: " "
      recipe:
        material: KNOWLEDGE_BOOK
        name: "View cooking recipes"
```

3. The recipe detail GUI in `gui.yml`, which determines how many ingredients the recipe page can display. After expanding the input slots, it is recommended to expand the `ingredient` slots here as well:

```yaml
recipe-view-gui:
  recipe-detail-cooking-pot-guis:
    large_pot:
      title: "<white><offset><icon><shift:-1600>Large Pot Recipe"
      rows: 6
      layout:
        - "B######P#"
        - "###III###"
        - "###III###"
        - "###III###"
        - "#####C#R#"
        - "#########"
      legend:
        I: ingredient
        C: container
        R: result
        B: back
        P: fill
        "#": background
      items:
        background:
          material: GRAY_STAINED_GLASS_PANE
          item_model: "air"
          hide-tooltip: true
          name: " "
        back:
          material: ARROW
          name: "Back"
        fill:
          material: HOPPER
          name: "Fill ingredients"
```

4. The custom recipe group in `recipes/cooking_pot_recipes.yml`:

```yaml
custom_cooking_pot_recipes:
  large_pot:
    large_stew:
      ingredients:
        - "minecraft:beef"
        - "minecraft:carrot"
        - "minecraft:potato"
        - "farmersdelight:onion"
      container: "minecraft:bowl"
      result: "farmersdelight:beef_stew"
      cook-time: 200
```

Setting `custom.id` without adding the matching `cooking-pot-guis.<id>` entry is legal; the plugin falls back to the default cooking pot GUI. If a custom pot has dedicated recipes but no `recipe-detail-cooking-pot-guis.<id>`, the plugin outputs a warning to the console when opening the recipe GUI from that pot, and falls back to the default `recipe-detail-cooking-pot`. If a recipe's ingredient count exceeds the number of `ingredient` slots on the detail page, that is also reported to the console when opening that GUI.

### 3.4 Cutting Board Behavior

```yaml
behavior:
  - type: farmersdelight:cutting_board
    knife-sound: farmersdelight:block.cutting_board.knife
    tool-tags:
      - '#farmersdelight:knives'
      - '#minecraft:axes'
      - '#minecraft:pickaxes'
      - '#minecraft:shovels'
    tool-items:
      - minecraft:shears
```

The shipped configuration above simply re-states the built-in defaults; it is documentation, not a change.

Available parameters:

| Parameter | Required | Default | Effect |
| --- | --- | --- | --- |
| `tool-tags` | No | `farmersdelight:knives`, `minecraft:axes`, `minecraft:pickaxes`, `minecraft:shovels` | Item tags whose members act as cutting tools. A leading `#` is optional here. Writing this parameter replaces all four defaults; writing an empty list restores them. |
| `tool-items` | No | `minecraft:shears` | Individual item IDs that act as cutting tools, in addition to the tags. Written **without** `#` — a tag put here produces a malformed ID that matches nothing. |
| `knife-sound` | No | `farmersdelight:block.cutting_board.knife` | Sound played when processing ingredients. |
| `max-stack-amount` | No | `64` | Maximum number of identical items the board allows on it. It can only ever lower the natural limit — the effective cap is the smallest of this value, the container's stack size, and the item's own max stack size. |
| `data-key` | No | `farmersdelight:cutting_board` | Sub-key under which the board's contents are stored in the CE BlockEntity. Same trap as the pot's `data-key`. |

Whether the cutting board enables stacking is controlled by `cutting-board.interaction-mode` in `config.yml`: `stacking` enables stacking, `offhand` enables off-hand interaction. With stacking disabled, `max-stack-amount` has no effect at all.

The board's permission node is the fixed `farmersdelight.use.cutting_board`; unlike the pot, there is no `permission` parameter. The `facing` property is optional but strongly recommended — without it the placed item renders facing north and does not rotate with the board.

### 3.5 Stove Behavior

```yaml
behavior:
  - type: farmersdelight:stove
    crackle-sound: farmersdelight:block.stove.crackle
states:
  properties:
    facing:
      type: 4-direction
      default: north
    fire:
      type: boolean
      default: true
```

**The `fire` property is required.** The stove reads its lit state from a boolean block-state property named exactly `fire`. A block that declares `farmersdelight:stove` without it still loads and still places, but it loses the stove behavior entirely: the console names the config node and the missing property, and what you get in the world is an ordinary decorative block that never cooks and never burns anyone. The name is fixed — there is no parameter to point it elsewhere. The same property is what the heat-source entry `farmersdelight:stove[fire:true]` in `config.yml` matches against, so such a stove also never heats a pot or skillet placed on top.

| Parameter | Required | Default | Effect |
| --- | --- | --- | --- |
| `crackle-sound` | No | `farmersdelight:block.stove.crackle` | Burning sound played while the stove is lit. An empty value falls back to the default rather than silencing it. |
| `burn-enabled` | No | `true` | Whether a lit stove damages living entities standing on the grilling surface. |
| `burn-damage` | No | `1.0` | Damage per burn tick. Negative values are clamped to `0`, which stops the damage rather than healing. |

The shipped stove sets neither burn parameter and relies on those defaults. Tick budget, cook time, cooling, mob-scan radius, particle rates and the six food-display offsets are in the `stove` section of `config.yml`, and the permission node is the fixed `farmersdelight.use.stove`.

### 3.6 Skillet Behavior

```yaml
behavior:
  - type: farmersdelight:skillet
    add-food-sound: farmersdelight:block.skillet.add_food
    sizzle-sound: farmersdelight:block.skillet.sizzle
```

| Parameter | Required | Default | Effect |
| --- | --- | --- | --- |
| `add-food-sound` | No | `farmersdelight:block.skillet.add_food` | Sound played when adding food. |
| `sizzle-sound` | No | `farmersdelight:block.skillet.sizzle` | Sizzling sound played while cooking. |

These two are the whole behavior section. An empty value falls back to the default, so a silent skillet cannot be configured here. Cook-time multipliers, the Fire Aspect bonus, cooling, hopper input, heat conduction through a hopper, viewer distance and the food display scale/offsets are all in the `skillet` section of `config.yml`, and the permission node is the fixed `farmersdelight.use.skillet`. The shipped skillet declares `facing` for its model and food placement.

### 3.7 Crop Behaviors

Four of these behaviors share the same pair of soil parameters:

| Parameter | Required | Effect |
| --- | --- | --- |
| `bottom-block-tags` | No | Block tags the crop may sit on. A leading `#` is optional. Matched against vanilla block tags **and** against the tags a CE custom block declares in its own `settings.tags`. |
| `bottom-blocks` | No | Individual blocks. Three usable forms: a vanilla ID (`minecraft:farmland`), a vanilla state (`minecraft:farmland[moisture=7]`), or a CE block ID (`your_pack:custom_soil`). A CE ID with a state suffix is **not** supported — see below. |

Three things to know about `bottom-blocks`.

A vanilla state entry is a **subset** match, not an exact one: only the properties you actually wrote are compared, so `minecraft:farmland[moisture=7]` matches every farmland at moisture 7 whatever its other properties say. Writing the bare ID and writing every property out are therefore the same thing.

A CE ID carrying a state suffix — `your_pack:custom_soil[level=2]` — is not merely ignored. Entries are first offered to the vanilla block-data parser, which rejects anything outside `minecraft:`; the parse failure is swallowed and the entry is retried as a plain material name, and that retry rejects `[`, `]` and `=` outright and throws. Nothing catches that second throw, so it escapes as an unknown error during behavior construction and the block **genuinely fails to register** — no block, no item, no states, unlike the recoverable property errors described in 3.2. Match custom soils by their plain ID, or by a tag they declare.

An entry is resolved as a vanilla material by its *path*, ignoring the namespace, so a custom block whose path collides with a vanilla material name (`your_pack:stone`) silently resolves to the vanilla block instead. Give custom soils non-colliding paths.

Configuring either parameter replaces the behavior's built-in soil fallback entirely; configuring neither keeps the fallback. `mushroom_colony` is the exception — it has no built-in fallback, so configuring neither leaves its growth ungated by soil altogether.

Tall crop — the two-half crop used by rice, with growth, bone meal, right-click harvesting of the mature upper half, and a placement check:

```yaml
behavior:
  - type: farmersdelight:tall_crop
    extra_planting_items:
      - farmersdelight:rice_panicle
    grow-speed: 0.25
    light-requirement: 6
    is-bone-meal-target: true
    bone_meal_age_bonus:
      type: uniform
      min: 1
      max: 4
    requires-water: true
    harvest-tool-tags:
      - "#farmersdelight:knives"
    bottom-block-tags:
      - "#minecraft:dirt"
    bottom-blocks:
      - minecraft:grass_block
states:
  properties:
    age:
      type: int
      default: 0
      range: 0~4
    half:
      type: double_block_half
      default: lower
```

(That is the shipped `farmersdelight:rice`. The two underscore keys in it are among the three parameters that accept both spellings.)

| Parameter | Required | Default | Effect |
| --- | --- | --- | --- |
| `age-property` | Yes | `age` | Name of the integer growth-stage property. **Required**: an `int` property of this name must exist, or the block keeps loading but loses this behavior, with the property named in the console. There is no fall back to `age`. |
| `half-property` | Yes | `half` | Name of the property distinguishing the halves. **Required** on the same terms, and it must be of type `double_block_half`. |
| `supporting-property` | No | `supporting` | Optional boolean property set when the lower half matures. Genuinely optional: silently skipped when the block declares no property of that name, or declares one that is not a boolean. The shipped `farmersdelight:rice` declares none. |
| `grow-speed` | No | `0.25` | Per-random-tick chance to advance one stage. |
| `light-requirement` | No | `9` | Minimum light level for random-tick growth. Not checked for bone meal. |
| `is-bone-meal-target` | No | **`true`** | Whether this behavior's own bone meal branch runs. Gates nothing else — see 3.2. |
| `bone-meal-age-bonus` | No | `1` | Stages gained per bone meal. Accepts a plain number or a CE number provider such as `type: uniform`. Clamped to at least `1`, so `0` does not disable bone meal. |
| `max-age-lower` | No | Top of the `age` range | Age at which the lower half matures and spawns the upper half. |
| `max-age-upper` | No | `max-age-lower` minus one | Age at which the upper half becomes harvestable. |
| `half-lower-value` / `half-upper-value` | No | `lower` / `upper` when the property has values by those names, otherwise its first and last value | Raw property values counted as each half. |
| `requires-water` | No | `false` | When true, planting also requires a water source at the planting cell or directly below it. Enforced at placement only — draining the water later does not kill a planted crop. |
| `reset-on-harvest` | No | `true` | When true, right-clicking the mature upper half harvests it and resets the lower half. When **false there is no right-click harvest at all**. |
| `upper-block` | No | Same block as this one | Block definition used for the upper half. |
| `harvest-tool-tags` / `harvest-tool-items` | No | Empty | Item tags / item IDs accepted as harvest tools, on top of the `knife-items` list in `config.yml`, which always works. |
| `extra-planting-items` | No | Empty | Extra item IDs that reuse this crop's planting logic. Accepts a list or a single string. |
| `bottom-block-tags` / `bottom-blocks` | No | Built-in fallback | Soil rules, as described above. The fallback is farmland, dirt, grass block, mud, clay, sand, coarse dirt, rooted dirt, podzol and mycelium, plus any CE block carrying a tag whose name contains `farmland`, `soil` or `dirt`. |

The age and half properties are mandatory outright, not merely advisable: a `tall_crop` block that cannot resolve both keeps its states and its item but is stripped of the crop behavior, so what a mistake here actually buys you is a decorative plant that never grows, never matures and can never be harvested — plus one console line naming the property. Read that line rather than trusting the block's appearance. An item claimed by `extra-planting-items` can map to only one crop block; a second claim is ignored with a console warning. Every successful upper-half harvest also drops the item configured under `drops.straw.mature_rice` in `config.yml`, for every `tall_crop` block, not just rice. If the block declares a `loot` section, the harvest rolls that loot table, falling back to the lower half's table when the upper half yields nothing; a block with no `loot` fires its CE `on: break` event chain instead.

Wild rice — a rules provider with no growth of its own. It decides whether the naturally generated two-block wild rice may be placed and may survive:

```yaml
behavior:
  - type: double_high_block
  - type: farmersdelight:wild_rice
    requires-water: true
    bottom-block-tags:
      - "#minecraft:dirt"
    bottom-blocks:
      - minecraft:grass_block
      - minecraft:mud
      - minecraft:sand
      - minecraft:red_sand
  - type: farmersdelight:upper_half_loot_relay
```

| Parameter | Required | Default | Effect |
| --- | --- | --- | --- |
| `requires-water` | No | **`true`** | When true, the planting cell must be a water **source** block. Read as a strict boolean: a quoted `"false"` is discarded and the default stands. |
| `bottom-block-tags` / `bottom-blocks` | No | Built-in fallback | Blocks the lower half may stand on. The fallback is dirt, grass block, coarse dirt, rooted dirt, podzol, mycelium, mud, sand and red sand. |

There is no `half-property` here; the property name is fixed to `half`, it must be of type `double_block_half`, and it is required — a block declaring this behavior without it still loads, but the behavior is dropped and the plant is left with no placement or survival rules of its own. The two half values are then matched by name against the property's own values. The block ID is fixed too: the plugin looks this behavior up under `farmersdelight:wild_rice`, so attaching it to a block with another ID registers it but nothing ever calls it. The cell above the water must also be air, and the upper half must sit on the same block ID's lower half — neither is configurable.

Wild plant — bone-meal spreading for the static wild crops (wild cabbages, onions, tomatoes). It has no survival logic of its own; that is CE's `bush_block` on the same block:

```yaml
behavior:
  - type: liquid_flowable_block
  - type: bush_block
    bottom-block-tags:
      - minecraft:dirt
      - minecraft:sand
  - type: farmersdelight:wild_plant
    is-bone-meal-target: true
```

| Parameter | Required | Default | Effect |
| --- | --- | --- | --- |
| `is-bone-meal-target` | No | `true` | When false the behavior passes the click straight through, consuming no bone meal and never spreading. |
| `bone-meal-success-chance` | No | `0.8` | Probability that a bone meal use actually attempts a spread. The bone meal is consumed either way, so `0` still eats it on every click. |
| `spread-limit` | No | `10` | Population cap within the surrounding 9×3×9 box. The origin plant counts itself, so `1` means "never spread". Values below `1` are raised to `1`. |
| `bottom-block-tags` / `bottom-blocks` | No | `minecraft:dirt` or `minecraft:sand` tag | Blocks a **spread copy** may land on — not where the plant may exist. If these disagree with the `bush_block` survival rules, the copies pop off on the next block update. |

Tomato vine — one behavior attached to all three tomato blocks, dispatching on whichever block is actually at the position:

```yaml
behavior:
  - type: crop_block
    grow-speed: 0.25
    light-requirement: 9
    is-bone-meal-target: true
    bone_meal_age_bonus:
      type: uniform
      min: 1
      max: 2
  - type: bush_block
    bottom-blocks:
      - farmersdelight:tomatoes
    stackable: true
    max-height: 3
    delay: 1
  - type: farmersdelight:tomato_vine
    budding-block: farmersdelight:budding_tomatoes
    tomatoes-block: farmersdelight:tomatoes
    crop-on-rope-block: farmersdelight:tomato_crop_on_rope
    mature-age: 0
```

| Parameter | Required | Default | Effect |
| --- | --- | --- | --- |
| `budding-block` | No | `farmersdelight:budding_tomatoes` | ID of the budding stage. |
| `tomatoes-block` | No | `farmersdelight:tomatoes` | ID of the ground stage. |
| `crop-on-rope-block` | No | `farmersdelight:tomato_crop_on_rope` | ID of the hanging stage. |
| `rope-block` | No | `farmersdelight:rope` | Block restored when a hanging tomato is removed. |
| `mature-age` | No | `0` | When greater than `0`, climbing requires that age. `0` disables the gate, so writing `0` is identical to omitting it. |
| `min-light` | No | `9` | Minimum light for the budding-to-ground conversion and for any climb. |
| `max-stack-height` | No | `3` | Fallback only, and usually inert — see below. |
| `budding-max-age` | No | `3` | Age at which budding tomatoes convert to ground tomatoes. |
| `tomatoes-max-age` | No | `3` | Max age of the ground stage, and the age at which right-click harvest is ready. |
| `hanging-max-age` | No | `3` | Max age of the hanging stage. |
| `bonemeal-climb-chance` | No | `0.3` | Chance a bone meal on a ground or hanging tomato attempts a rope climb. |
| `bonemeal-bonus-min` / `bonemeal-bonus-max` | No | `1` / `4` | Inclusive range of the budding stage's bone meal age bonus. |

All three IDs must agree across all three blocks; the behavior compares the block at the position against them, and a block whose own copy lists different IDs simply does nothing on tick. `max-stack-height` is overridden whenever the hanging block carries a `bush_block` with `max-height` greater than `0`, which the shipped config does — change the `bush_block` value instead. Climbing also requires the cell above to carry the `farmersdelight:rope` behavior, not merely a block with the ID in `rope-block`; that parameter is only used to restore a rope afterwards. There is no `is-bone-meal-target` here — bone meal is always accepted. Each of the three blocks needs an integer `age` property named exactly `age` and `is-randomly-ticking: true`; the age property is checked when the behavior loads, so a block missing it is stripped of the behavior at load — named in the console — rather than placing normally and ticking uselessly forever.

Mushroom colony — an age-based cluster with shears/knife harvesting, random-tick growth and bone meal:

```yaml
behavior:
  - type: bush_block
    bottom-block-tags:
      - minecraft:mushroom_grow_block
    bottom-blocks:
      - farmersdelight:rich_soil
      - farmersdelight:organic_compost
  - type: farmersdelight:mushroom_colony
    mushroom-type: "minecraft:brown_mushroom"
    light-requirement: 0
    grow-on-blocks:
      - farmersdelight:rich_soil
      - farmersdelight:organic_compost
states:
  properties:
    age:
      type: int
      default: 3
      range: 0~3
```

| Parameter | Required | Default | Effect |
| --- | --- | --- | --- |
| `age-property` | Yes | `age` | Name of the integer growth-stage property. **Required**: an `int` property of this name must exist, or the block loads without this behavior, with the property named in the console. |
| `max-age` | No | Top of the `age` range | Age cap for growth and bone meal. |
| `grow-speed` | No | `0.25` | Per-random-tick chance to advance one age. |
| `light-requirement` | No | `0` | Minimum light for random-tick growth. **`0` skips the light check entirely** rather than requiring light level 0. |
| `bonemeal-min-age-bonus` / `bonemeal-max-age-bonus` | No | `1` / `2` | Inclusive range of the bone meal age gain. Note the prefix is `bonemeal-`, not `bone-meal-`; this is a different parameter from the one on `tall_crop` and the spellings are not interchangeable. |
| `mushroom-type` | Yes | Empty | Item dropped on harvest. **Effectively mandatory** — without a valid ID the colony cannot be harvested at all. |
| `harvest-tool-tags` | No | Empty | Item tags counted as a shears-style tool. |
| `harvest-tool-items` | No | Empty | Exact item IDs. Only `minecraft:shears` counts as shears-style; anything else is knife-style. |
| `grow-on-blocks` / `grow-on-block-tags` | No | Falls through to `bottom-blocks` / `bottom-block-tags`, and to no soil check at all if those are absent too | Blocks the colony must sit on **to keep growing**. |

A shears-style tool drops one mushroom and lowers the age by one; a knife-style tool drops as many mushrooms as the current age and resets the age to 0. The knife list in `config.yml` always works as a fallback, with or without `harvest-tool-items`. Bone meal cannot be disabled through this behavior. `mushroom-type` also decides the particle colour: an ID containing `red` renders red particles, anything else brown.

Do not mix the two soil naming pairs on this behavior. Writing `grow-on-blocks` together with `bottom-block-tags` makes the `grow-on-*` pair win and discards your `bottom-block-tags`. Pick one pair and use it for both keys. Omitting all four is not the same as omitting soil rules on the other three behaviors: this behavior has no built-in soil fallback, so an unconfigured colony grows on anything its `bush_block` lets it stand on. Note also that these parameters gate growth only — where the colony may *exist* is the `bush_block` on the same block, which the shipped config deliberately keeps wider.

### 3.8 Soil Behaviors

Rich soil — on random tick it converts a mushroom sitting directly above into a mushroom colony, and otherwise rolls a chance to bone-meal the plant above (or, failing that, the block below):

```yaml
behavior:
  - type: farmersdelight:rich_soil
    boost-chance: 0.2
    brown-mushroom-colony: farmersdelight:brown_mushroom_colony
    red-mushroom-colony: farmersdelight:red_mushroom_colony
    unaffected-blocks:
      - minecraft:grass_block
      - minecraft:short_grass
      - minecraft:sunflower
      - farmersdelight:brown_mushroom_colony
      - farmersdelight:red_mushroom_colony
      - farmersdelight:wild_rice
```

| Parameter | Required | Default | Effect |
| --- | --- | --- | --- |
| `boost-chance` | No | `0.08` | Per-random-tick probability of a boost attempt. `0` or less disables boosting; the mushroom conversion still runs. The shipped block uses `0.2`. |
| `brown-mushroom-colony` | No | `farmersdelight:brown_mushroom_colony` | Block placed when a brown mushroom above is converted. |
| `red-mushroom-colony` | No | `farmersdelight:red_mushroom_colony` | Block placed when a red mushroom above is converted. |
| `unaffected-blocks` | No | Empty | Blocks the boost must skip. Each rich soil block carries its own list. |

Entries in `unaffected-blocks` come in three shapes. A leading `#` marks a **tag**, an entry beginning with `minecraft:` or with no namespace at all is a vanilla block, and anything else is a CraftEngine block ID.

Tags are **not** split by namespace. Every `#` entry, whatever its namespace, is added to the tag set that custom blocks are tested against — a custom block matches if it declares that tag in its own `settings.tags`. Each `#` entry is *additionally* resolved against the server's block tag registry (datapack tags included), and that resolved tag is what vanilla blocks are tested against. So `#minecraft:dirt` matches both a genuine dirt block and any custom block declaring `minecraft:dirt` in its settings — which is not a hypothetical, since the shipped `farmersdelight:rich_soil` declares exactly that. A tag in a non-vanilla namespace usually resolves to nothing on the vanilla side and so behaves as CraftEngine-only in practice, but that is a consequence of the registry lookup failing, not a rule about namespaces. A `minecraft:` tag the server does not know is reported as a console warning at load; a tag in any other namespace stays silent, because CraftEngine publishes no tag index to check it against.

Matching asks CraftEngine first, so nothing ever matches a custom block through the material it is disguised as: a `minecraft:` block entry cannot reach a custom block, and a `#minecraft:` tag reaches one only through the tag list that block declares for itself, never through its disguise. To exclude a custom block, list its CE ID or a tag it actually declares.

The boost is a plain bone meal application, so anything the server considers bone-mealable is affected, including other plugins' and datapacks' crops. The conversion recognises both vanilla mushrooms and this plugin's look-alike custom mushrooms, and always places the colony at age 0.

The block needs `settings.is-randomly-ticking: true`, or the behavior never runs at all. The shipped block also carries the `minecraft:dirt`, `minecraft:bamboo_plantable_on` and `minecraft:mushroom_grow_block` tags; without them, crops, bamboo and mushrooms refuse the soil.

Rich soil farmland — the vanilla farmland moisture cycle, plus a boost at full moisture, plus reverting to rich soil when covered:

```yaml
behavior:
  - type: farmersdelight:rich_soil_farmland
    boost-chance: 0.08
    rich-soil-block: farmersdelight:rich_soil
states:
  properties:
    moisture:
      type: int
      default: 0
      min: 0
      max: 7
```

| Parameter | Required | Default | Effect |
| --- | --- | --- | --- |
| `boost-chance` | No | `0.08` | Probability, per random tick **at full moisture only**, of bone-mealing the plant above. `0` or less disables the boost; moisture still cycles. |
| `rich-soil-block` | No | `farmersdelight:rich_soil` | Block this reverts to when a solid block is placed on top. |
| `unaffected-blocks` | No | The list of the block named by `rich-soil-block` | Blocks the boost must skip. Same entry syntax as above. |
| `moisture-property` | Yes | `moisture` | Name of the integer moisture property. **Required**: an `int` property of this name must exist, or the block loads without this behavior, with the property named in the console. |

The block must declare an integer moisture property with the range `0~7`. The name may be changed with `moisture-property`, but a property of whatever name is configured has to exist — otherwise the block places as inert farmland with no moisture cycle, no boost and no revert-on-cover, and the reason is in the console rather than in the world. Moisture is capped at 7 and is not configurable. Rain, or water within the 9×9×2 box around the block, snaps it straight to 7; otherwise it drops by one per random tick. Because the boost only rolls at full moisture, a dry farm never boosts.

The exclusion list falls back to the rich soil block's own list when this block declares none, which is how the shipped pair keeps both blocks in sync from a single list. Writing an explicitly empty `unaffected-blocks: []` here is different from omitting it: it disables the exclusions for this block instead of borrowing them.

The revert-on-cover check uses collision, with melons, pumpkins, moving pistons and fence gates exempted. Crops do not collide, so planting never reverts the soil.

Organic compost — scans the 3×3×3 box around itself each random tick, sums a chance from activators, water and sky light, and on success advances one stage or, at the top stage, turns into rich soil:

```yaml
behavior:
  - type: farmersdelight:organic_compost
    rich-soil-block: farmersdelight:rich_soil
    max-stage: 7
    activator-bonus-per-neighbor: 0.02
    water-bonus: 0.10
    light-high-bonus: 0.10
    light-low-bonus: 0.05
    light-threshold: 12
    activators:
      - minecraft:brown_mushroom
      - minecraft:red_mushroom
      - minecraft:podzol
      - minecraft:mycelium
      - farmersdelight:brown_mushroom_colony
      - farmersdelight:red_mushroom_colony
      - farmersdelight:organic_compost
      - farmersdelight:rich_soil
      - farmersdelight:rich_soil_farmland
states:
  properties:
    composting:
      type: int
      default: 0
      range: 0~7
```

| Parameter | Required | Default | Effect |
| --- | --- | --- | --- |
| `max-stage` | No | `7` | Stage at or above which the block converts instead of advancing. Keep it inside the declared `composting` range: a `max-stage` above the range's maximum makes the block try to advance past its last state. |
| `rich-soil-block` | No | `farmersdelight:rich_soil` | Block placed on completion. |
| `brown-mushroom-colony` / `red-mushroom-colony` | No | The shipped colony IDs | Exposed for other parts of the plugin; this behavior does not place them itself. |
| `activator-bonus-per-neighbor` | No | `0.02` | Chance added per activator block found in the 3×3×3 box. |
| `water-bonus` | No | `0.10` | Chance added if any water is in the box. Flat, not per block. |
| `light-high-bonus` | No | `0.10` | Chance added when the highest sky light in the box is **above** `light-threshold`. |
| `light-low-bonus` | No | `0.05` | Chance added otherwise. One of the two always applies. |
| `light-threshold` | No | `12` | Sky-light cutoff between the two. |
| `activators` | No | Empty | Blocks that count as activators. Same entry syntax as `unaffected-blocks`. |
| `composting-property` | Yes | `composting` | Name of the integer stage property. **Required**: an `int` property of this name must exist, or the block loads without this behavior, with the property named in the console. |

The per-tick chance is `activator count × activator-bonus-per-neighbor`, plus the applicable light bonus, plus the water bonus if any water is in the box.

Two things surprise people here. The 3×3×3 scan includes the compost block itself, so listing `farmersdelight:organic_compost` among the activators — as the shipped config does, mirroring the original mod — gives every compost block a permanent bonus and makes a stack of them compost faster; removing that one entry measurably slows composting. And there is no "no light bonus" branch: omitting the two light parameters still adds `0.10` or `0.05`. To make light irrelevant, set both to the same value; to stop dark compost progressing on light alone, set `light-low-bonus: 0`.

The block must declare an integer stage property. The name may be changed with `composting-property`, but a property of whatever name is configured has to exist — otherwise the block still places and still looks like compost, but never composts and never converts, with the reason confined to the load log. It also needs `settings.is-randomly-ticking: true`.

`max-stage` is compared against the live property value with "at or above", so it should equal the top of the property's declared range. Set it lower and the higher stages become unreachable. Set it higher and the block never converts — instead it tries to advance to a stage the property does not have, which fails on the tick that reaches the top of the range.

### 3.9 Rope, Tatami, and Upper-Half Loot Relay

Rope:

```yaml
behavior:
  - type: farmersdelight:rope
states:
  properties:
    north: {type: boolean, default: false}
    east: {type: boolean, default: false}
    south: {type: boolean, default: false}
    west: {type: boolean, default: false}
```

**This behavior takes no parameters at all.** Anything written under it is inert. The four connection properties are looked up by fixed name; a missing one is tolerated rather than reported, so a typo shows up as a rope that never connects on that one side.

Connections are decided when the rope is placed and are never re-derived upward afterwards. Clicking a horizontal face lets the rope tie to anything presenting a full sturdy face; clicking a vertical face restricts the tie to ropes, iron bars, glass panes and walls. A later neighbour update only ever re-applies the restricted test, so a tie to a plain solid block lasts only until something on that side changes. Right-clicking a rope while holding the same rope item reels one down into the first replaceable slot below; right-clicking with an empty hand walks up the rope column and rings the first bell it finds, within the distance set by `rope.bell-ring-max-distance` in `config.yml`.

The shipped rope carries the `minecraft:climbable`, `minecraft:fall_damage_resetting`, `minecraft:mineable/shears` and `minecraft:sword_efficient` tags. None of them are inherited from the backing block, so a custom rope must declare its own.

Tatami:

```yaml
behavior:
  - type: farmersdelight:tatami
    block-id: farmersdelight:tatami
    facing-property: facing
    paired-property: paired
    pair-while-sneaking: false
states:
  properties:
    facing:
      type: direction
      default: down
    paired:
      type: boolean
      default: false
```

| Parameter | Required | Default | Effect |
| --- | --- | --- | --- |
| `block-id` | No | `farmersdelight:tatami`, or whatever a previous parse left | Block ID treated as "a tatami" by the pairing check. |
| `facing-property` | Yes | `facing` | Name of the direction property. |
| `paired-property` | Yes | `paired` | Name of the boolean pairing property. |
| `pair-while-sneaking` | No | `false` | `false` means sneak-placing suppresses pairing, matching vanilla. `true` means pairing always happens. |

Placement sets `facing` to the opposite of the clicked face, so placing on the ground gives a lone flat mat while placing against another tatami's side gives a horizontal facing, which is what drives the woven long-mat texture. Pairing then rewrites both mats.

These three names are **server-wide, and the last configuration parsed wins**: only one block ID is recognised as a tatami at a time. The defaults are also self-referential — after a reload in which you deleted the key, the previously set value persists rather than reverting. Once you have written a value, omitting it later is not the same as writing the default. There is no validation that `block-id` names an existing block.

**Both properties are required.** A block declaring this behavior that cannot resolve either one keeps loading and keeps placing, but without the tatami behavior: no facing on placement, no pairing, no woven long mat. The missing property is named in the console, and that message is the only place the failure is visible — the block itself looks fine. `paired` must be a boolean, since the pairing flag is written as one, and a property of that name with any other type costs you the behavior just as a missing one does. `facing` is looked up by name only: its value is read and written as text, so any property type whose values spell out direction names is accepted. What that type must cover is the values actually written — placement writes the opposite of the clicked face, so a floor placement writes `down`. A value the property does not have is dropped and the state is left unchanged, which is why a `4-direction` property, having no `down`, leaves floor-placed mats on their default facing.

Upper-half loot relay — for double-tall blocks, so that breaking the upper half drops the *lower* half's loot table instead of the upper half's usually empty one:

```yaml
behavior:
  - type: double_high_block
  - type: farmersdelight:wild_rice
    requires-water: true
  - type: farmersdelight:upper_half_loot_relay
```

The defaults already match CE's `double_high_block` naming, so the bare form above is how this plugin uses it. For a plant whose halves are laid out unusually:

| Parameter | Required | Default | Effect |
| --- | --- | --- | --- |
| `lower-half-direction` | No | `DOWN` | Direction from this block to its lower half. An unrecognised name falls back to `DOWN` silently. |
| `require-matching-lower-half` | No | `true` | Whether the block in that direction must really be a valid lower half. Read as a strict boolean. |
| `require-matching-block` | No | `true` | Whether the lower half must be the same block ID. `false` accepts any custom block with a matching half value. Read as a strict boolean. |
| `half-property` | Yes | `half` | Name of the property holding lower/upper. **Required**: a property of this name must exist, or the block loads without this behavior — the upper half then simply drops its own loot table — with the property named in the console. Looked up by name only, with no type check, because the half values are compared as text. |
| `half-lower-value` / `half-upper-value` | No | `lower` / `upper` | Property values meaning each half. Compared case-insensitively. |

Setting both `require-*` parameters to `false` does not make the relay unconditional: the state must still be recognised as the upper half first. And when the block also carries `farmersdelight:tall_crop`, an immature pairing suppresses drops entirely before the relay is even considered — which is how unripe tall crops avoid dropping their mature loot. If the resolved lower-half loot is empty, the relay does nothing and the normal drops stand.

### 3.10 Item Behavior and Food Effect Functions

`farmersdelight:conditional_block_planting` is an **item** behavior: it makes one item place different blocks depending on what it is clicked on. Note that an item's `behavior` is a single map, not a list:

```yaml
items:
  minecraft:brown_mushroom:
    behavior:
      type: farmersdelight:conditional_block_planting
      rules:
        - target: farmersdelight:rich_soil
          block: farmersdelight:brown_mushroom
        - target: farmersdelight:organic_compost
          block: farmersdelight:brown_mushroom
```

| Parameter | Required | Default | Effect |
| --- | --- | --- | --- |
| `rules` | Yes | **Required** | List of rules. There is no default and no pass-through fallback: a missing list, an empty list, or a list whose every entry was discarded as malformed throws at load. CraftEngine keeps the item and drops only its behavior. |
| `rules[].target` | Yes | Required | Block that must be clicked: a CE block ID, or `minecraft:<block>` for vanilla. |
| `rules[].block` | Yes | Required | CE block placed in the slot above. |

An entry that is not a map, and an entry missing either key, is skipped — but not silently: each one logs its own warning at load naming the item. Two rules with the same `target` collapse to the last one, and that is the one case with no warning at all. A rule naming a block CraftEngine does not know is not caught at load either; it is only noticed when someone clicks the matching target, and it then logs a warning **per click** and passes the click through. The click is likewise handed back to CraftEngine and vanilla untouched when the clicked face is not the top face, when no rule matches, when the slot above is not air, or when a protection plugin denies building.

That pass-through is the useful part: the behavior can be attached to a vanilla item without breaking it. In the shipped config, a brown mushroom clicked on plain dirt still places the vanilla mushroom, while the same item clicked on rich soil or organic compost places this plugin's custom mushroom. Keep the placed block's own survival rules pointing back at the same soils, or it pops off on the next block update.

`farmersdelight:comfort` and `farmersdelight:nourishment` apply this plugin's two food effects. They are **event functions, not loot functions** — they belong in an event list such as a food item's `on: consume`, and writing them inside a loot table's `functions` list does not resolve:

```yaml
farmersdelight:beef_stew:
  events:
    - on: consume
      functions:
        - type: farmersdelight:nourishment
          duration: 180
          level: 1
```

| Parameter | Required | Default | Effect |
| --- | --- | --- | --- |
| `duration` | No | `90` | Effect duration in **seconds**, not ticks. Accepts a CE number provider. Clamped to at least 1. |
| `level` | No | `1` | Effect level, counted from 1. Accepts a CE number provider. Clamped to at least 1. |
| `condition` / `conditions` | No | None | Standard CE predicates. This is one of the few parameters that accepts both spellings. |

A dose stacks against an active one by the vanilla potion rule: stronger replaces and refreshes, equal extends to the longer remaining time, weaker is ignored.

These functions bypass the per-effect switches in `config.yml`. `buff.comfort.enabled` and `buff.nourishment.enabled` only gate the config-driven food lists described in section 13; a food carrying `type: farmersdelight:comfort` grants Comfort even with `buff.comfort.enabled: false`. The only switch that silences them is `buff.enabled: false`. Note also that `buff.comfort.heal-interval-ticks: 0` leaves the effect display intact while disabling its healing, so the effect can look applied and do nothing.

This plugin's own resources do not use either function — its foods are wired through `config.yml` instead. They exist for pack authors and addons.

## 4. Configuration File Responsibilities

`config.yml` is the gameplay rules configuration. The default file writes out the configurable gameplay options, with common items listed first and advanced items later.

`gui.yml` is solely responsible for GUI: titles, layouts, buttons, backgrounds, icons, progress bars, and recipe pages. The main config no longer reads GUI sub-sections.

`recipes/cooking_pot_recipes.yml` holds cooking pot recipes.

`recipes/cutting_board_recipes.yml` holds cutting board recipes.

## 5. Common Configuration

Language:

```yaml
language: ''
```

Leaving it empty follows the JVM/system language; you can also write `zh_cn` or `en_us` to force the language of server logs and console text. Player interface text is still displayed by the player's client language by preference.

Cutting board interaction mode:

```yaml
cutting-board:
  interaction-mode: stacking
```

`stacking` enables 64 stacking and disables off-hand interaction. `offhand` enables off-hand interaction and disables 64 stacking.

Skillet hopper heat conduction:

```yaml
skillet:
  heat:
    allow-conductors: false
```

Off by default. The cooking pot can still use heat-conducting blocks to determine heat sources, while the skillet will not be treated as heated just because a hopper is below it.

Cooking pot break drop:

```yaml
cooking-pot:
  pack-contents-on-break: true
```

`true` means that when the cooking pot is broken, its internal ingredients, container, pending-output slot, and output slot are saved into the dropped cooking pot item; `false` means dropping an empty pot and scattering the pot's contents into the world.

Station product experience reward (top level: the cooking pot and every addon station that credits experience through the API share it):

```yaml
experience-reward:
  mode: vanilla
  auraskills:
    skill: farming
    multiplier: 1.0
    raw: false
```

`mode` can be `vanilla`, `auraskills`, `both`, or `none`. The reward is settled when the player takes the product from the cooking pot's output slot, or right-clicks with a container to take out a pending-output product; hopper auto-extraction has no player context, so it does not trigger AuraSkills experience. `multiplier` uses the recipe's `experience` times the multiplier, while writing `amount` instead gives a fixed amount of experience per extraction.

## 6. Item IDs and Clearing Notation

Item IDs can be vanilla or CE items:

```yaml
minecraft:bowl
farmersdelight:flint_knife
```

Optional item outputs can use any of the following values to clear:

```yaml
""
none
null
empty
air
minecraft:air
```

Applicable locations:

- `drops.mob-extra.<entity>.normal`
- `drops.mob-extra.<entity>.burning`
- `drops.straw.<type>.drop`
- `container-returns.<item>`
- Optional display items in `gui.yml`

A recipe's `result` should not be cleared; if you do not want a particular recipe, simply delete that recipe.

## 7. Cooking Pot

The cooking pot by default has 6 ingredient slots, 1 pending-output slot, 1 output slot, and 1 container slot. Cooking begins when a recipe matches and a heat source is present; upon completion, the product preferentially goes into the output slot, and is only temporarily held in the pending-output slot when a container is needed or the output slot temporarily cannot hold it.

Hopper logic is off by default. The plugin uses the CraftEngine `WorldlyContainerHolder` container route to handle hopper interactions, no longer using Bukkit events to simulate the hopper route. Enable it when you need hopper input/output:

```yaml
hopper-interactions:
  enabled: false
cooking-pot:
  hopper-interactions: true
cutting-board:
  hopper-interactions: true
```

Design logic once enabled:

- Top hopper: pushes ingredients into the ingredient slots.
- Side hopper: mainly pushes containers into the container slot, and also allows interaction per the container/output rules.
- Bottom hopper: extracts products from the output slot; pending-output items move into the output slot once container/space conditions are met.

When right-clicking the cooking pot with a container to take out products from the pending-output slot, only the extracted product and the held container are processed, and the whole pot's data is not refreshed in a way that would lose other contents.

While the cooking pot is cooking, it preferentially keeps the current recipe. As long as the current recipe can still be completed by the pot's contents, continuing to add rice, other ingredients, or extra-batch materials does not reset the cooking progress; only when the current recipe is no longer satisfied and it switches to another recipe does the progress restart.

Cooking pot recipe example:

```yaml
cooking_pot_recipes:
  vegetable_soup:
    ingredients:
      - "minecraft:carrot"
      - "minecraft:potato"
      - "minecraft:beetroot"
      - "farmersdelight:cabbage|farmersdelight:cabbage_leaf"
    container: "minecraft:bowl"
    result: "farmersdelight:vegetable_soup"
    result-count: 1
    experience: 1.0
    cook-time: 200
    category: meals
```

Ingredient notation:

- Plain item: `minecraft:carrot`
- CE item: `farmersdelight:cabbage`
- Tag: `#farmersdelight:vegetables`
- Any-of in the same slot: `farmersdelight:cabbage|farmersdelight:cabbage_leaf`
- Exclusion: `#namespace:tag,!namespace:item,!#namespace:other_tag`

## 8. Cutting Board

The cutting board can have items placed on it, then processed with a configured knife or tool. The output supports multiple results and probabilities.

Cutting board recipe example:

```yaml
cutting_board_recipes:
  raw_pasta:
    input: "farmersdelight:wheat_dough"
    tool: "#farmersdelight:knives"
    results:
      - item: "farmersdelight:raw_pasta"
        count: 1
```

Multiple probability outputs:

```yaml
cutting_board_recipes:
  wild_tomatoes:
    input: "farmersdelight:wild_tomatoes"
    tool: "#farmersdelight:knives"
    results:
      - item: "farmersdelight:tomato_seeds"
      - item: "farmersdelight:tomato"
        chance: 0.2
      - item: "minecraft:green_dye"
        chance: 0.1
```

You can write a single `tool`, or multiple `tools`.

## 9. Heat Sources

Heat sources can come from vanilla blocks, vanilla tags, CE blocks, or CE tags.

```yaml
heat-sources:
  tags:
    - farmersdelight:heat_sources
  vanilla-blocks:
    - minecraft:magma_block
    - minecraft:lava_cauldron
    - minecraft:lava
    - minecraft:fire
    - minecraft:soul_fire
  vanilla-tags:
    - minecraft:campfires
  custom-blocks:
    - farmersdelight:stove[fire:true]
  conductors:
    - minecraft:hopper
  conductor-tags: []
```

When a custom block has already been given the `farmersdelight:heat_sources` tag on the CE resource side, you do not need to also write it into `custom-blocks`.

## 10. World Display and Model Adaptation

After replacing a CE model, common issues are item display offset, too large, too small, or floating text occlusion. Prefer adjusting the display parameters in `config.yml`; modifying code directly is not recommended.

### 10.1 Cutting Board Item Display

The cutting board's global display parameters are under `cutting-board`:

```yaml
cutting-board:
  default-display-offset: 0,0,0
  display-item-spread: 0.15
```

- `default-display-offset`: the overall offset for all cutting board items, in the format `x,y,z`. Adjust this when all items shift to the same side after replacing the cutting board model.
- `display-item-spread`: the spread range when multiple items pile up on the cutting board. `0.15` means a maximum of about `+-0.075` blocks, close to the original mod. If the display is too spread out, lower it, e.g. to `0.08`.

When a single item displays incorrectly, override it individually with `display-overrides`:

```yaml
cutting-board:
  display-overrides:
    farmersdelight:tomato:
      display-item: farmersdelight:tomato_display
      style: item
      position: 0,0.02,0
      translation: 0,0,0
      rotation: 90,180,0
      scale: 0.55
```

- `display-item`: use another CE/vanilla item as the display model.
- `style`: `auto` to decide automatically; `item` to force a flat item; `block` to force a block/3D item.
- `position`: continue to move on top of the global offset.
- `translation`: the ItemDisplay's internal translation. Usually kept at default, adjusted only when the model's pivot is off.
- `rotation`: overrides the rotation angle, in degrees.
- `scale`: overrides the scale, can be written as `0.55` or `0.55,0.55,0.55`.

### 10.2 Skillet Item Display

Skillet parameters are under `skillet.display`:

```yaml
skillet:
  display:
    scale: 0.5
    y-offset: 0.1
    item-spread: 0.15
```

- `scale`: food size.
- `y-offset`: the food's base height. Raise it if the food sinks into the pan, lower it if it floats noticeably.
- `item-spread`: the random spread range for multiple food items. The default `0.15` is close to the original mod, i.e. a maximum of about `+-0.075` blocks.

### 10.3 Stove Item Display

Stove parameters are under `stove.display`:

```yaml
stove:
  display:
    scale: 0.375
    slot-offsets:
      - "0.3,1.02,0.2"
      - "0.0,1.02,0.2"
      - "-0.3,1.02,0.2"
      - "0.3,1.02,-0.2"
      - "0.0,1.02,-0.2"
      - "-0.3,1.02,-0.2"
```

- `scale`: the size of food on the stove.
- `slot-offsets`: the coordinates of the 6 slots, in the format `x,y,z`. The coordinates are based on the north facing, and the plugin automatically rotates them according to the stove's facing.
- Slot order: front-right, front-center, front-left, back-right, back-center, back-left.
- When food does not land on the cooking surface after replacing the stove model, adjust `x/z` in `slot-offsets` first; adjust the `y` of each entry when the height is off.

### 10.4 Cooking Pot Progress Text

The cooking pot's floating progress text is under `cooking-pot.progress-display`:

```yaml
cooking-pot:
  progress-display:
    enabled: true
    show-recipe-name: false
    y-offset: 1.2
    scale: 0.5
    visibility-distance: 10.0
    look-dot-threshold: 0.95
    update-interval-ticks: 8
    disable-above-active-pots: 512
```

- `enabled`: whether to enable the floating TextDisplay while the cooking pot is cooking. When stress-testing large numbers of cooking pots, you can disable it first for a baseline comparison.
- `show-recipe-name`: whether to display the name of the recipe currently being cooked.
- `y-offset`: the text height. Adjust this after replacing a tall or short pot model.
- `scale`: the text scale.
- `visibility-distance`: how many blocks the player must be within to possibly see it.
- `look-dot-threshold`: the look-at threshold; the closer to `1`, the more accurately the player must be looking. If the hint is too hard to trigger, lower it to `0.90`.
- `update-interval-ticks`: the refresh interval for the floating progress text. Affects only the TextDisplay update frequency, not the actual cooking time.
- `disable-above-active-pots`: automatically disables the floating TextDisplay when the number of active cooking pots exceeds this value; `0` means no automatic disabling by count.

## 11. GUI

GUI configuration is in `gui.yml`. Every interface has:

- `title`: the title, supporting MiniMessage and CE image fonts.
- `title-layout`: works with CE image fonts to create a title background.
- `rows`: number of rows.
- `layout`: each character represents a slot.
- `legend`: the mapping from characters to slot types.
- `items`: buttons or decoration items.

GUI item notation:

```yaml
items:
  recipe:
    material: KNOWLEDGE_BOOK
    name: "View cooking recipes"
    lore:
      - "Click here to view recipes"
  arrow:
    item: farmersdelight:cooking_time
    name: "Cooking process"
```

`material` uses Bukkit vanilla materials, while `item` uses CraftEngine items. The recipe detail page tries to read the display item's own name, so once CE-side item names are set up properly, the recipe page will also look more natural.

### 11.1 The Fill Button on the Cooking Pot Recipe Detail

The `fill` button on the cooking pot recipe detail page is used to move ingredients from the player's inventory that match the current recipe into the cooking pot:

- Normal click: fills at most 1 matching item per recipe ingredient.
- Shift click: cycles matching by recipe ingredient, trying to fill up the cooking pot's input slots.
- When there are no matching ingredients at all, the pot's contents are not moved and the cooking pot interface is not returned to.
- If the pot's original items cannot be safely returned to the player's inventory, the fill is not performed, to avoid swallowing items.
- When refreshing the recipe detail page in place, the detail page's cooking progress bar animation is preserved, and the progress bar is not reset to the start due to a change in the fill button state.
- Filling ingredients itself does not forcibly zero out the cooking pot's actual cooking progress; while the recipe being cooked can still complete, the progress continues.

The button display is fully configured in `gui.yml`. The four states are:

| Config item | When it is displayed |
| --- | --- |
| `fill` | The default state, before the player clicks the fill button. |
| `fill-success` | The state after successfully filling at least one ingredient or container. The default flow returns to the cooking pot interface, so it is usually only shown briefly; this item is kept to uniformly configure the material and text of the success state. |
| `fill-missing` | There are no ingredients or containers in the inventory matching the current recipe. |
| `fill-inventory-full` | The pot's original items cannot be safely returned to the player's inventory. |

Example:

```yaml
recipe-view-gui:
  recipe-detail-cooking-pot:
    items:
      fill:
        item: farmersdelight:recipe_fill
        name: "Fill ingredients"
        lore:
          - "&eMove matching ingredients from your inventory."
          - "&7Shift click to fill up with matching ingredients."
      fill-missing:
        item: farmersdelight:recipe_fill_missing
        name: "No ingredients to fill"
        lore:
          - "&cThere are no ingredients in your inventory matching the current recipe."
      fill-inventory-full:
        item: farmersdelight:recipe_fill_blocked
        name: "Not enough inventory space"
        lore:
          - "&cPlease clear your inventory first, then try filling ingredients."
```

These buttons can also use vanilla materials:

```yaml
fill:
  material: HOPPER
  custom-model-data: 10001
  name: "Fill ingredients"
```

After modifying `gui.yml`, use `/fd reload gui` to apply it; if the button uses a new CraftEngine item or resource pack texture, a full server restart and having players reload the resource pack are still recommended.

### 11.2 In-Game Recipe Editor

Admins can use `/fd recipe edit pot|board [id]` to visually edit recipes in-game without manually editing YAML. All editor interface text comes from the editor sub-sections of `gui.yml` and the language files, with nothing hardcoded.

Interaction uses a "cursor brush" model: clicking an item in the inventory places a copy of it on the cursor, then clicking an editor slot places it in; the actual inventory item is not consumed.

- Ingredient cell: left-click to place the cursor item as a plain ingredient; continuing to place on an existing ingredient merges it into a choice (`a|b`).
- Ingredient cell right-click + holding an item on the cursor: pops up a tag picker based on the tags the item belongs to (see below).
- Ingredient cell Shift click or right-click a multi-choice ingredient: opens the choice builder, where you can delete items one by one or "clear all" at once.
- Tag picker: lists all tags the item belongs to (CraftEngine custom tags + vanilla tags). Left-click a tag to use it directly; right-click to enter exclusion editing, which lists the items contained in that tag, where clicking toggles between "exclude/include".
- Saving, deleting (with a confirmation page), and count/cook-time/experience/priority/category, etc. are all adjusted within the interface.

The editor-related `gui.yml` sub-sections: `recipe-editor-gui`, `recipe-editor-cooking-pot-guis.<id>` (custom large pot override), `recipe-cutting-board-editor-gui`, `recipe-editor-confirm-delete-gui`, `recipe-choice-builder-gui`, `recipe-tag-picker-gui`. Saving writes to the corresponding recipe file and automatically reloads the recipes.

When overriding a vanilla item with a custom config on the CE side, it is recommended to also give the CE item a display name pointing to the vanilla language key, to avoid the GUI, recipe page, or item tooltip showing only the ID. For example, when overriding a vanilla item ID, you can use this in the CE item display name:

```yaml
name: "<l10n:item.minecraft.${__ID__}>"
```

`${__ID__}` represents the path portion of the current item ID. For example, `minecraft:cooked_beef` maps to the vanilla language key `item.minecraft.cooked_beef`. The player's client will display the localized name such as "熟牛肉 / Cooked Beef" according to its own language, rather than showing `minecraft:cooked_beef` or the CE internal ID.

If you are overriding a custom-namespace item, you can write your own language key the same way:

```yaml
name: "<l10n:item.farmersdelight.${__ID__}>"
```

This requires the corresponding translation to exist in the resource pack's language file. When there is no translation, the client usually falls back to displaying the language key itself.

## 12. Knife Drops and Straw

Knife recognition (top level: the cutting board, the skillet, mushroom colonies, rice harvesting, straw drops and the recipe viewer read the same list):

```yaml
knife-items:
  tags:
    - farmersdelight:knives
  items:
    - farmersdelight:flint_knife
    - farmersdelight:iron_knife
    - farmersdelight:golden_knife
    - farmersdelight:diamond_knife
    - farmersdelight:netherite_knife
```

Extra drops from killing a mob with one of the tools in `drops.mob-extra-tools`:

```yaml
drops:
  mob-extra-tools:
    tags:
      - farmersdelight:knives
    items:
      - farmersdelight:flint_knife
  mob-extra:
    pig:
      normal: farmersdelight:ham
      burning: farmersdelight:smoked_ham
      chance: 0.5
      looting-multiplier: 0.1
```

`drops.mob-extra-tools` is the default trigger list for these drops only; it does not make those items count as knives elsewhere. An individual rule can override it with `tool-item` / `tool-items` / `tool-tags`.

Straw drops:

```yaml
drops:
  straw:
    mature_wheat:
      drop: farmersdelight:straw
      min-amount: 1
      max-amount: 2
```

## 13. Food Effects

Pet food:

```yaml
pet-foods:
  farmersdelight:dog_food:
    entities:
      - WOLF
    require-tamed: true
    restore-health: true
    effects:
      - type: SPEED
        duration: 6000
        amplifier: 0
        ambient: false
        particles: true
```

Horse feed temptation:

```yaml
pet-foods:
  farmersdelight:horse_feed:
    entities:
      - HORSE
      - DONKEY
      - MULE
    require-tamed: false
    restore-health: true
    effects: []
    sound: ENTITY_GENERIC_EAT
    sound-volume: 0.8
    sound-pitch: 0.8
    particles: true
    particle-type: HEART
    particle-count: 4
    tempt:
      enabled: true
      range: 10.0
      move-speed: 1.25
      tick-interval: 10
      ignore-owned-tamed: true
```

Each key under `pet-foods` is a food item ID, supporting both CraftEngine item IDs and vanilla item IDs. `entities` uses Bukkit `EntityType` names, e.g. `HORSE`, `DONKEY`, `MULE`, `LLAMA`, `CAMEL`. `tempt` is the held-item temptation config; `tick-interval` is in ticks, and 10 ticks is about 0.5 seconds. When you need more temptable foods, simply add a new item entry under `pet-foods`.

Nourishment effect (clears exhaustion after eating, preventing hunger loss): takes effect when eating a food in the `buff.nourishment.foods` list. `duration` is in seconds, defaulting to the original mod's tiers (30 / 60 / 180 / 300). The comfort effect (periodic regeneration) is deprecated in the original mod, so `buff.comfort` is off by default with an empty list; the mechanism code is retained, and you can enable it and add foods yourself if needed. Both report themselves through the buff display configured under `buff.display`.

```yaml
buff:
  comfort:
    enabled: false
    heal-interval-ticks: 80
    heal-amount: 1.0
    foods: {}
  nourishment:
    enabled: true
    foods:
      farmersdelight:cooked_rice:
        duration: 30        # 30/60/180/300 second tiers
      farmersdelight:beef_stew:
        duration: 180
      farmersdelight:noodle_soup:
        duration: 300
```

Container returns (top level: the cooking pot and every addon station that returns containers through the API share it):

```yaml
container-returns:
  farmersdelight:milk_bottle: minecraft:glass_bottle
```

## 14. World Data: Composting, Furnace Fuel, and Trades

`world-data` is where this plugin plugs its items into vanilla world mechanics that CraftEngine cannot reach on its own: the composter, the furnace fuel slot, and the two merchant trade pools. It has three subsections, `composting`, `furnace-fuel` and `trades`, and `trades` splits again into `villager` and `wandering-trader`, so there are four independently switchable features:

```yaml
world-data:
  composting:
    enabled: true
    items:
      farmersdelight:straw: 0.3
      farmersdelight:cabbage: 0.65
      farmersdelight:apple_pie: 1.0
  furnace-fuel:
    enabled: true
    items:
      farmersdelight:straw: 100
      farmersdelight:tree_bark: 200
      farmersdelight:straw_bale: 1000
  trades:
    villager:
      enabled: true
      trades:
        - profession: farmer
          level: 1
          ingredient: farmersdelight:onion
          ingredient-amount: 26
          result: minecraft:emerald
          result-amount: 1
          max-uses: 16
          villager-xp: 2
          price-multiplier: 0.05
          chance: 0.14285714285714285
    wandering-trader:
      enabled: true
      generic-trade-count: 5
      trades:
        - ingredient: minecraft:emerald
          ingredient-amount: 1
          result: farmersdelight:cabbage_seeds
          result-amount: 1
          max-uses: 1
          villager-xp: 12
          price-multiplier: 0.05
          chance: 0.014705882352941176
```

Every `enabled` flag defaults to `true`. Setting one to `false` turns that whole feature off while leaving its list in the file, which is the reversible way to disable a feature; deleting a single entry from a list is how you disable that one item or that one offer (see "Deleting an entry" below).

### 14.1 Composting

A composter refuses this plugin's items on its own, because a CraftEngine item is a plain vanilla material underneath and vanilla only recognises vanilla compostables. The fill is therefore carried out by the plugin.

`items` is keyed by item ID, and the value is the chance between `0.0` and `1.0` that one item raises the composter by one level — the same meaning the number has in vanilla. Vanilla always accepts the first item into an empty composter, and that still applies here. A key that is not a valid item ID, or a value outside `0.0`–`1.0`, is skipped with a console warning and the rest of the list still loads.

### 14.2 Furnace Fuel

`items` is keyed by item ID, and the value is the burn time in ticks, which must be positive. Smelting one item takes 200 ticks, so `200` is worth exactly one smelt. A non-positive value or an invalid item ID is skipped with a console warning.

These burn times are also pushed into the CraftEngine item definition, which is what lets the stack be moved into a furnace's fuel slot at all. `/fd reload config` re-pushes them, so an edited list takes effect without waiting for a CraftEngine reload.

### 14.3 Villager and Wandering Trader Trades

The vanilla merchant listing pools cannot be joined from a plugin, so an offer here is substituted for a drawn vanilla one at the probability the mod's listing would have been picked. `chance` is that probability: it is the share this plugin's listings hold in the pool. A datapack that changes the vanilla pools changes those shares, so `chance` has to be retuned by hand when you use one.

`trades` is a **list of offers**, not a map keyed by ID. Each entry accepts:

| Key | Default | Effect |
| --- | --- | --- |
| `profession` | Required for villager offers | Villager profession ID path, e.g. `farmer`. Unused by the wandering trader, which has no profession. |
| `level` | `1` | Villager level whose pool the listing joins. Unused by the wandering trader. |
| `ingredient` | Required | Item ID the player hands over. |
| `ingredient-amount` | `1` | How many of it. Values below `1` are raised to `1`. |
| `result` | Required | Item ID the player receives. |
| `result-amount` | `1` | How many of it. Values below `1` are raised to `1`. |
| `max-uses` | `16` | How many times the trade works before restock. Values below `1` are raised to `1`. |
| `villager-xp` | `1` | Merchant experience the trade awards. |
| `price-multiplier` | `0.05` | The vanilla demand and reputation multiplier. |
| `chance` | `1.0` | Probability this listing wins one draw from its pool. Values above `1.0` are clamped; an entry with `0` or less is dropped. |

An entry without a `profession` (villager list only), or without a valid `ingredient` and `result`, is skipped with a console warning.

`wandering-trader` additionally has `generic-trade-count`, defaulting to `5`: how many of a trader's offers are drawn from the generic pool these listings compete in. Its remaining offer comes from a separate rare pool this plugin does not add to.

### 14.4 Deleting an Entry Is How You Disable It

Every list in `world-data` ships with defaults that reproduce the original mod's data exactly, so the feature works on a `config.yml` that predates these sections. Once your file carries a list, that list replaces the built-in one wholesale: only the entries your file still has are kept.

Deleting an entry is therefore the way to disable that one item or offer — remove `farmersdelight:straw` from `composting.items` and straw no longer composts, remove an offer from `trades.villager.trades` and no farmer ever lists it. Updating the plugin will not add it back: these four lists are treated as content registries by the config updater, which fills in a section only when your file has none of it at all, and never merges individual entries into a section you already have.

`/fd reload config` rebuilds the whole of `world-data`.

## 15. Commands and Permissions

The main command is dynamically registered through code, with the command name `/farmersdelight` and the alias `/fd`. All subcommands first check the base permission `farmersdelight.command`; without this permission, you cannot enter a subcommand even if you have that subcommand's permission.

Command overview:

```text
/fd help
/fd recipe
/fd recipe cooking_pot
/fd recipe cutting_board
/fd recipe edit pot [recipeID] [customGroup]
/fd recipe edit board [recipeID]
/fd reload
/fd reload all
/fd reload config
/fd reload gui
/fd reload lang
/fd reload recipes
/fd reload advancements
/fd cleanup
```

Command descriptions:

| Command | Alias/Args | Permission | Default grant | Description |
| --- | --- | --- | --- | --- |
| `/fd` | `/farmersdelight` | `farmersdelight.command` | All players | Displays the help entries the player currently has permission to view. |
| `/fd help` | `/fd ?` | `farmersdelight.command` | All players | Displays help information. |
| `/fd recipe` | `/fd recipes` | `farmersdelight.command` + `farmersdelight.command.recipe` | All players | Opens the main recipe viewing interface. Player-only. |
| `/fd recipe cooking_pot` | `pot`, `cookingpot` | `farmersdelight.command` + `farmersdelight.command.recipe` | All players | Directly opens the cooking pot recipe list. Player-only. |
| `/fd recipe cutting_board` | `board`, `cuttingboard` | `farmersdelight.command` + `farmersdelight.command.recipe` | All players | Directly opens the cutting board recipe list. Player-only. |
| `/fd recipe edit pot [id] [group]` | `pot`, `cookingpot` | `farmersdelight.command` + `farmersdelight.command.recipe` + `farmersdelight.admin` | OP | Opens the cooking pot recipe editor. With an ID, edits that recipe directly (creates it if it does not exist); with a custom group, edits a custom large pot recipe; without an ID, opens the recipe list to click and edit. Player-only. |
| `/fd recipe edit board [id]` | `board`, `cuttingboard` | `farmersdelight.command` + `farmersdelight.command.recipe` + `farmersdelight.admin` | OP | Opens the cutting board recipe editor. With an ID, edits directly (creates it if it does not exist); without an ID, opens the recipe list to click and edit. Player-only. |
| `/fd reload` | Defaults to the same as `/fd reload config` | `farmersdelight.command` + `farmersdelight.admin` | OP | Reloads `config.yml`. |
| `/fd reload config` | - | `farmersdelight.command` + `farmersdelight.admin` | OP | Reloads only the regular gameplay config. |
| `/fd reload gui` | - | `farmersdelight.command` + `farmersdelight.admin` | OP | Reloads `gui.yml`, used to refresh GUI titles, layouts, buttons, icons, etc. |
| `/fd reload lang` | `language`, `languages` | `farmersdelight.command` + `farmersdelight.admin` | OP | Reloads the language files. |
| `/fd reload recipes` | `recipe` | `farmersdelight.command` + `farmersdelight.admin` | OP | Reloads cooking pot and cutting board recipes, and refreshes the CE item cache. |
| `/fd reload advancements` | `advancement` | `farmersdelight.command` + `farmersdelight.admin` | OP | Reloads advancements and rebuilds the advancement tree (requires UltimateAdvancementAPI; skipped if not installed). |
| `/fd reload all` | - | `farmersdelight.command` + `farmersdelight.admin` | OP | Fully reloads config, GUI, language, recipes, and advancements. A restart is still recommended after modifying CE resources or the jar. |
| `/fd cleanup` | - | `farmersdelight.command` + `farmersdelight.admin` | OP | Cleans up the CE proxy item display entities generated by the plugin, and cleans up auto-trays that no longer meet the conditions. |

Additional commands in debug builds:

| Command | Permission | Default grant | Description |
| --- | --- | --- | --- |
| `/fd debugtools place <cooking_pot\|skillet\|stove\|stove_blocked\|all> [count] [spacing] [layers]` | `farmersdelight.command` + `farmersdelight.admin` | OP | Bulk-places blocks for performance testing. Only appears in jars built with `-PdebugTools=true`. |
| `/fd debugtools activate <cooking_pot\|skillet\|stove\|all>` | `farmersdelight.command` + `farmersdelight.admin` | OP | Activates the cooking/display logic of the test blocks. Only appears in debug builds. |
| `/fd debugtools status` | `farmersdelight.command` + `farmersdelight.admin` | OP | Outputs the current TickManager performance sampling, active queues, and cooking pot entity count. Only appears in debug builds. |
| `/fd debugtools profile [ticks]` | `farmersdelight.command` + `farmersdelight.admin` | OP | Resets TickManager sampling and outputs avg/max/last timing after the specified number of ticks, defaulting to 200 ticks. Only appears in debug builds. |

Normal builds do not include the `debugtools`, `debug`, or `perf` subcommands, nor do they pack the debug tooling classes into the final jar. A normal server does not need to configure any debug permissions for players.

Permissions overview:

| Permission | Default grant | Scope | What it specifically controls |
| --- | --- | --- | --- |
| `farmersdelight.command` | All players | Command entry | Allows the use of the `/fd` or `/farmersdelight` main command. All subcommands check it first. |
| `farmersdelight.command.recipe` | All players | Recipe viewing | Allows the use of `/fd recipe`, `/fd recipe cooking_pot`, `/fd recipe cutting_board` to open the recipe GUI. |
| `farmersdelight.use.cooking_pot` | All players | Cooking pot interaction | By default allows opening the cooking pot GUI, placing ingredients/containers into the cooking pot, taking products out of the cooking pot, and triggering cooking pot interaction logic. |
| `farmersdelight.use.stove` | All players | Stove interaction | Allows placing food into the stove, taking food out, and triggering stove cooking and display logic. |
| `farmersdelight.use.skillet` | All players | Skillet interaction | Allows placing food into the skillet, taking food out, and triggering skillet cooking and display logic. |
| `farmersdelight.use.cutting_board` | All players | Cutting board interaction | Allows placing items onto the cutting board, removing items, and processing cutting board recipes with a knife or configured tool. |
| `farmersdelight.admin` | OP | Admin commands | Allows the use of `/fd reload ...` and `/fd cleanup`. In debug builds, also used for `/fd debugtools`. |
| `farmersdelight.*` | OP | All permissions | Includes all public permission nodes of this plugin. Suitable for admin groups; not recommended to give directly to regular players. |

The cooking pot permission can be overridden in the CraftEngine block behavior parameters:

```yaml
behavior:
  type: farmersdelight:cooking_pot
  permission: farmersdelight.use.cooking_pot
```

When `permission` is not written, it defaults to `farmersdelight.use.cooking_pot`. When written as an empty string, this cooking pot does not perform a plugin-side permission check; this notation is only recommended when claims, WorldGuard, or another permission system already restricts interaction. Custom cooking pots can also assign different permissions to different IDs, e.g.:

```yaml
behavior:
  type: farmersdelight:cooking_pot
  permission: farmersdelight.use.large_cooking_pot
  custom:
    id: large
    input-slots: 9
    pending-output-slots: 2
    output-slots: 3
    container-slots: 2
```

Such custom permissions are not automatically written into `plugin.yml`; the permission plugin can still recognize and assign them directly. If you need Bukkit's permission list to show it, you need to register it in the server permission plugin or the plugin description.

## 16. Reload Recommendations

`/fd reload` by default only reloads the regular gameplay config, equivalent to `/fd reload config`. Available reload commands:

- `/fd reload config`: reloads `config.yml`.
- `/fd reload gui`: reloads `gui.yml`.
- `/fd reload lang`: reloads the language files.
- `/fd reload recipes`: reloads cooking pot and cutting board recipes, and refreshes the CE item cache.
- `/fd reload advancements`: reloads advancements and rebuilds the advancement tree (requires UltimateAdvancementAPI).
- `/fd reload all`: fully reloads the plugin's config, GUI, language, recipes, and advancements.

A full restart is recommended for:

- Updating the jar
- Updating CraftEngine
- Modifying CE resource configuration
- Modifying resource pack fonts, models, or textures

Hot-reloading this plugin and CraftEngine with PlugMan or Bukkit reload is not recommended.

## 17. FAQ

CE item names showing as IDs: check whether the CE item's display name, language file, and resource pack are loaded. This plugin's recipe page prefers the item's own name.

Ingredient display in the recipe detail looks bad: first confirm whether the CE-side item name is normal, then check the detail page layout and `show-ingredient-ids` in `gui.yml`.

Hoppers not working as expected: first confirm whether `hopper-interactions.enabled` is on. This feature is off by default; once on, confirm the direction—ingredients are pushed in from the top of the cooking pot, products are extracted from the bottom, and containers are pushed in from the side. The skillet does not accept hopper heat conduction by default.

No change after modifying the config: try `/fd reload` first. If you touched CE resources or the resource pack, do a full restart.

## 18. Compilation

The development environment resolves the CraftEngine 26.5 dependency through a Maven repository, and `build.gradle.kts` already uses the `net.momirealms:craft-engine-*` coordinates. If the dependency repository is temporarily unavailable, you can first install the corresponding CraftEngine artifacts into `mavenLocal()`.

The build artifact is a **single universal jar** that supports both Paper and Folia (`folia-supported: true` is hardcoded in `plugin.yml` / `paper-plugin.yml`, no longer split by platform).

Common build:

```powershell
.\gradlew.bat clean shadowJar
```

Output:

```text
build/libs/farmersdelight-1.0.0.jar
```

Optional obfuscated build:

```powershell
.\gradlew.bat buildObfuscated
```

Obfuscated output:

```text
build/libs/farmersdelight-1.0.0-obf.jar
build/reports/proguard/farmersdelight-1.0.0-mapping.txt
```

The obfuscated build (ProGuard) preserves the lifecycle methods of the Bukkit main class, `@EventHandler` methods (kept but allowed to be renamed), enum entries, and the whole public API tree `com.huidu.farmersdelight.api.**` (events, facades, registries, snapshots — the same set the api-only jar ships); all other classes are renamed and repackaged into `fd`. CraftEngine block behaviors are registered by string key + factory reference and do not rely on class-name reflection, so they can be safely obfuscated. After obfuscation, only the public API package of the plugin's own code remains readable. `build/reports/proguard/*-mapping.txt` is kept solely for internal troubleshooting records (used to reverse-look-up obfuscated stack traces); `build/` is already git-ignored and should not be committed to a public repository.

The debug tools (the `/fd debugtools …` performance testing commands) are not packed into the jar by default; build with `-PdebugTools=true` when needed:

```powershell
.\gradlew.bat clean shadowJar -PdebugTools=true
```

On Folia it is recommended to validate region scheduling, container interaction, display synchronization, and chunk-unload saving on a test server.
