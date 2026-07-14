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

The main behavior IDs registered by this plugin:

```text
farmersdelight:cooking_pot
farmersdelight:cutting_board
farmersdelight:skillet
farmersdelight:stove
farmersdelight:tall_crop
farmersdelight:wild_rice
farmersdelight:rope
farmersdelight:tatami
farmersdelight:mushroom_colony
farmersdelight:upper_half_loot_relay
```

In the CE block or furniture configuration you must attach the corresponding behavior to the corresponding resource. For example, attach `farmersdelight:cooking_pot` to the cooking pot, `farmersdelight:cutting_board` to the cutting board, and `farmersdelight:stove` to the stove. The CE configuration syntax follows the CraftEngine 26.5 resource configuration.

### 3.1 Attaching Block Behaviors

Behavior parameters are written under `behavior` in the CraftEngine block/furniture resource. A common form is shown below; the exact indentation follows the CE resource file structure:

```yaml
behavior:
  - type: farmersdelight:cooking_pot
    permission: farmersdelight.use.cooking_pot
```

If the same resource also has a CE-native behavior or another plugin's behavior attached, simply append it to the `behavior` list.

### 3.2 Cooking Pot Behavior

A default cooking pot only needs the behavior attached:

```yaml
behavior:
  - type: farmersdelight:cooking_pot
```

Available parameters:

| Parameter | Default | Effect |
| --- | --- | --- |
| `permission` | `farmersdelight.use.cooking_pot` | Permission required to open the cooking pot. Write an empty string to disable this plugin's permission check. |
| `open-while-sneaking` | `false` | Whether sneak-right-click also opens the cooking pot. When off, sneak interactions are yielded to other behaviors. |
| `place-tray-on-open` | `true` | Whether to automatically sync/place the tray display under the pot when opening the cooking pot. |
| `boil-sound` | Built-in boiling sound | Sound ID for normal cooking boiling. |
| `soup-boil-sound` | Built-in soup boiling sound | Sound ID for soup cooking boiling. |
| `sound-chance` | Main config default | Probability of playing a sound on each cooking tick. |
| `sound-volume` | Main config default | Boiling sound volume. |
| `sound-pitch-min` | Main config default | Lower bound of random pitch. |
| `sound-pitch-max` | Main config default | Upper bound of random pitch. |
| `data-key` | `farmersdelight:cooking_pot` | Data key under which cooking pot contents are stored in the CE BlockEntity. Changing this value on already-placed blocks will make old contents unreadable under the new key, unless you migrate manually. |
| `custom` | Not enabled | Custom cooking pot slots, recipe group, and GUI title. |

Custom cooking pot example:

```yaml
behavior:
  - type: farmersdelight:cooking_pot
    custom:
      id: large_pot
      title: "<image:farmersdelight:large_cooking_pot_gui>"
      input-slots: 9
      pending-output-slots: 2
      output-slots: 3
      container-slots: 2
```

`custom` parameter descriptions:

| Parameter | Effect |
| --- | --- |
| `id` | Custom cooking pot ID. In the recipe file, use `custom_cooking_pot_recipes.<id>` to write recipes specific to this pot; `gui.yml` can override the interface using a cooking pot GUI config of the same name. |
| `title` / `gui-title` | The title used when opening the GUI. Usually written as an image font to override the custom interface texture. |
| `input-slots` | Number of ingredient slots. |
| `pending-output-slots` | Number of pending-output slots. Products that are cooked but not yet moved into the output slots are temporarily held in the pending-output slots. |
| `output-slots` | Number of finished-product output slots. |
| `container-slots` | Number of container slots, e.g. for bowls, bottles, etc. |

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

If a custom pot has dedicated recipes but no `recipe-detail-cooking-pot-guis.<id>`, the plugin outputs a warning to the console when opening the recipe GUI from that pot, and falls back to the default `recipe-detail-cooking-pot`. If a recipe's ingredient count exceeds the number of `ingredient` slots on the detail page, that is also reported to the console when opening that GUI.

### 3.3 Cutting Board Behavior

```yaml
behavior:
  - type: farmersdelight:cutting_board
    tool-tags:
      - farmersdelight:knives
    tool-items:
      - minecraft:shears
    max-stack-amount: 64
```

Available parameters:

| Parameter | Default | Effect |
| --- | --- | --- |
| `tool-tags` | `farmersdelight:knives`, `farmersdelight:axes`, `farmersdelight:pickaxes` | Tool tags that can trigger cutting board recipes. Tag notation with or without `#` is supported. |
| `tool-items` | `minecraft:shears` | Specific item IDs that can directly act as cutting board tools. |
| `knife-sound` | `farmersdelight:block.cutting_board.knife` | Sound played when processing ingredients. |
| `max-stack-amount` | `64` | Maximum number of items the cutting board allows to be placed. The actual amount is also limited by the item's own max stack size. |
| `data-key` | `farmersdelight:cutting_board` | Data key under which cutting board contents are stored in the CE BlockEntity. |

Whether the cutting board enables stacking is controlled by `cutting-board.interaction-mode` in `config.yml`: `stacking` enables stacking, `offhand` enables off-hand interaction.

### 3.4 Stove and Skillet Behaviors

Stove:

```yaml
behavior:
  - type: farmersdelight:stove
    crackle-sound: farmersdelight:block.stove.crackle
```

| Parameter | Default | Effect |
| --- | --- | --- |
| `crackle-sound` | `farmersdelight:block.stove.crackle` | Burning sound played while the stove is cooking. |

Skillet:

```yaml
behavior:
  - type: farmersdelight:skillet
    add-food-sound: farmersdelight:block.skillet.add_food
    sizzle-sound: farmersdelight:block.skillet.sizzle
```

| Parameter | Default | Effect |
| --- | --- | --- |
| `add-food-sound` | `farmersdelight:block.skillet.add_food` | Sound played when adding food. |
| `sizzle-sound` | `farmersdelight:block.skillet.sizzle` | Sizzling sound played while cooking. |

### 3.5 Crop, Rope, Tatami, and Mushroom Colony Behaviors

Tall crop:

```yaml
behavior:
  - type: farmersdelight:tall_crop
    age-property: age
    half-property: half
    upper-block: farmersdelight:tomatoes_top
    max-age-lower: 4
    max-age-upper: 3
    grow-speed: 0.25
    light-requirement: 9
    bottom-block-tags:
      - minecraft:dirt
```

| Parameter | Default | Effect |
| --- | --- | --- |
| `age-property` | `age` | Name of the CE block-state property representing the growth stage. |
| `half-property` | `half` | Name of the CE block-state property representing the upper/lower half. |
| `supporting-property` | `supporting` | Name of the boolean property marking the supporting state. Skipped when the property is absent. |
| `grow-speed` | `0.25` | Random-tick growth probability coefficient. |
| `light-requirement` | `9` | Minimum light level required for growth. |
| `is-bone-meal-target` | `true` | Whether bone meal is allowed to accelerate growth. |
| `random-bone-meal-growth` | `false` | Whether the bone meal growth amount uses a random range. |
| `bone-meal-min` / `bone-meal-max` | `1` / `2` | Minimum/maximum stages of bone meal growth. |
| `max-age-lower` / `max-age-upper` | Inferred from the property | Maximum mature stage of the lower/upper half. Harvesting and drops should be based on the mature state. |
| `half-lower-value` / `half-upper-value` | Inferred from the property | State values for the lower/upper half. |
| `requires-water` | `false` | Whether placement and survival require water. |
| `reset-on-harvest` | `true` | Whether mature harvesting resets to immature instead of breaking the block directly. |
| `upper-block` | Empty | Upper-half block ID automatically generated when the lower half is placed. |
| `harvest-tool-tags` | Empty | Tool tags that can harvest. |
| `harvest-tool-items` | Empty | Specific item IDs that can harvest. |
| `bottom-blocks` | Empty | Blocks that can be planted on / support the crop. Supports vanilla blocks, vanilla BlockData, CE block IDs, and CE state strings. |
| `bottom-block-tags` | Empty | Block tags that can be planted on / support the crop. Supports vanilla block tags and CE block tags. |

Wild rice:

```yaml
behavior:
  - type: farmersdelight:wild_rice
    requires-water: true
    bottom-blocks:
      - minecraft:dirt
      - minecraft:grass_block
      - minecraft:mud
```

`farmersdelight:wild_rice` handles the upper/lower-half structure, water source restoration, and soil validation. `requires-water` defaults to `true`, and the notation for `bottom-blocks` / `bottom-block-tags` is the same as for tall crops.

Mushroom colony:

```yaml
behavior:
  - type: farmersdelight:mushroom_colony
    age-property: age
    max-age: 3
    grow-speed: 0.25
    light-requirement: 0
    mushroom-type: minecraft:red_mushroom
    grow-on-blocks:
      - minecraft:mycelium
```

| Parameter | Default | Effect |
| --- | --- | --- |
| `age-property` | `age` | Name of the growth stage property. |
| `max-age` | Inferred from the property | Maximum mature stage. |
| `grow-speed` | `0.25` | Random-tick growth probability coefficient. |
| `light-requirement` | `0` | Minimum light level required for growth. |
| `harvest-tool-tags` / `harvest-tool-items` | Empty | Tools that can harvest. |
| `mushroom-type` | Empty | Mushroom item ID corresponding to the colony. |
| `grow-on-blocks` / `grow-on-block-tags` | Empty | Blocks or tags it can grow on; also compatible with `bottom-blocks` / `bottom-block-tags`. |

Tatami:

```yaml
behavior:
  - type: farmersdelight:tatami
    block-id: farmersdelight:tatami
    facing-property: facing
    paired-property: paired
    pair-while-sneaking: false
```

| Parameter | Default | Effect |
| --- | --- | --- |
| `block-id` | Built-in tatami ID | Used to determine whether an adjacent block is the same kind of tatami. |
| `facing-property` | `facing` | Name of the facing property. |
| `paired-property` | `paired` | Name of the boolean property for whether it is paired. |
| `pair-while-sneaking` | `false` | Whether sneak-placement also auto-pairs. |

Rope:

```yaml
behavior:
  - type: farmersdelight:rope
```

`farmersdelight:rope` reads the `north`, `south`, `east`, `west` boolean properties from the block state and automatically connects adjacent ropes. This behavior has no additional configuration parameters.

Upper-half loot relay:

```yaml
behavior:
  - type: farmersdelight:upper_half_loot_relay
    lower-half-direction: DOWN
    require-matching-lower-half: true
    require-matching-block: true
    half-property: half
    half-lower-value: lower
    half-upper-value: upper
```

This behavior is used for double-tall blocks: when the player breaks the upper half, it relays the drop determination to the corresponding lower half, preventing the upper half from bypassing maturity or lower-half state validation.

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

Cooking pot product experience reward:

```yaml
cooking-pot:
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

- `knife-drops.<entity>.normal`
- `knife-drops.<entity>.burning`
- `straw-drops.<type>.drop`
- `cooking-pot.container-returns.<item>`
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

Knife recognition:

```yaml
knife-config:
  tags:
    - farmersdelight:knives
  items:
    - farmersdelight:flint_knife
    - farmersdelight:iron_knife
    - farmersdelight:golden_knife
    - farmersdelight:diamond_knife
    - farmersdelight:netherite_knife
```

Extra drops from knife kills:

```yaml
knife-drops:
  pig:
    normal: farmersdelight:ham
    burning: farmersdelight:smoked_ham
    chance: 0.5
    looting-multiplier: 0.1
```

Straw drops:

```yaml
straw-drops:
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

Nourishment effect (clears exhaustion after eating, preventing hunger loss): takes effect when eating a food in the `nourishment-foods` list. `duration` is in seconds, defaulting to the original mod's tiers (30 / 60 / 180 / 300). The comfort effect (periodic regeneration) is deprecated in the original mod, so `comfort-foods` is off by default with an empty list; the mechanism code is retained, and you can enable it and add foods yourself if needed.

```yaml
comfort-foods:
  enabled: false
  foods: {}
nourishment-foods:
  enabled: true
  foods:
    farmersdelight:cooked_rice:
      duration: 30        # 30/60/180/300 second tiers
    farmersdelight:beef_stew:
      duration: 180
    farmersdelight:noodle_soup:
      duration: 300
```

Container returns:

```yaml
cooking-pot:
  container-returns:
    farmersdelight:milk_bottle: minecraft:glass_bottle
```

## 14. Commands and Permissions

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

## 15. Reload Recommendations

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

## 16. FAQ

CE item names showing as IDs: check whether the CE item's display name, language file, and resource pack are loaded. This plugin's recipe page prefers the item's own name.

Ingredient display in the recipe detail looks bad: first confirm whether the CE-side item name is normal, then check the detail page layout and `show-ingredient-ids` in `gui.yml`.

Hoppers not working as expected: first confirm whether `hopper-interactions.enabled` is on. This feature is off by default; once on, confirm the direction—ingredients are pushed in from the top of the cooking pot, products are extracted from the bottom, and containers are pushed in from the side. The skillet does not accept hopper heat conduction by default.

No change after modifying the config: try `/fd reload` first. If you touched CE resources or the resource pack, do a full restart.

## 17. Compilation

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

The obfuscated build (ProGuard) preserves the lifecycle methods of the Bukkit main class, `@EventHandler` methods (kept but allowed to be renamed), enum entries, and the public API package `com.huidu.farmersdelight.api.event.**`; all other classes are renamed and repackaged into `fd`. CraftEngine block behaviors are registered by string key + factory reference and do not rely on class-name reflection, so they can be safely obfuscated. After obfuscation, only the public API package of the plugin's own code remains readable. `build/reports/proguard/*-mapping.txt` is kept solely for internal troubleshooting records (used to reverse-look-up obfuscated stack traces); `build/` is already git-ignored and should not be committed to a public repository.

The debug tools (the `/fd debugtools …` performance testing commands) are not packed into the jar by default; build with `-PdebugTools=true` when needed:

```powershell
.\gradlew.bat clean shadowJar -PdebugTools=true
```

On Folia it is recommended to validate region scheduling, container interaction, display synchronization, and chunk-unload saving on a test server.
