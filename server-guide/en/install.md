---
icon: download
---

[简体中文](../zh-cn/install.md)

# Installation

Five steps take you from a downloaded jar to a working cooking pot in your hand.

## 1. Place the jars

Drop both plugins into your server's `plugins/` folder:

```
plugins/
  CraftEngine-<version>.jar
  farmersdelight-<version>.jar
```

You do not set load order — CraftEngine is a hard dependency, so the server always enables it first (see
[Requirements](requirements.md)).

## 2. First server start

Start the server. On this first boot FarmersDelight does two things worth knowing about:

- **Extracts its CraftEngine resources.** The full set of bundled block / item / recipe / loot definitions is
  written to `plugins/CraftEngine/resources/farmersdelight/`. This always happens on the very first start.
- **Writes its own config.** `plugins/FarmersDelight/config.yml`, `display-overrides.yml` (the board display
  tables), `drops.yml`, `world-data.yml`, `gui.yml` and `lang/*.yml` appear. Older versions kept those tables in
  `config.yml`; an upgrade moves them into the new file and logs one migration line.

The console prints a compact startup summary — roughly two lines reporting the scheduler, cutting-board mode,
hopper setting, advancement state, and a *Content ready* line counting the cooking-pot and cutting-board
recipes, mob drop rules, pet foods and advancements that loaded. Seeing that *Content ready* line is your
first confirmation the content parsed.

## 3. Build and host the resource pack

CraftEngine — not FarmersDelight — owns the resource pack. FarmersDelight only supplies the resource files
that CraftEngine bundles into the pack. Follow CraftEngine's own documentation to generate and serve the pack;
there is nothing FarmersDelight-specific about it.

See the [Resource pack](resource-pack.md) page for the short version and the two failure modes you are likely
to hit.

## 4. `/ce reload all`

After the resources are on disk and the pack is being served, run:

```
/ce reload all
```

`/ce reload all` reloads CraftEngine's configuration **and** regenerates the resource pack in one step — that
pack rebuild is what makes the newly extracted FarmersDelight models and textures reach clients. Plain
`/ce reload` only re-reads configuration (blocks, items, recipes) and does **not** rebuild the pack;
`/ce reload pack` rebuilds only the pack. Run `/ce reload all` any time you add or edit files under
`plugins/CraftEngine/resources/farmersdelight/`.

### FarmersDelight sections inside a CraftEngine pack

Besides CraftEngine's own sections, FarmersDelight registers four sections of its own. Any CraftEngine pack —
not just `plugins/CraftEngine/resources/farmersdelight/`, but also packs you or a third party drop into
`resources/` — can declare them in any YAML under `configuration/`, and CraftEngine hands the content to
FarmersDelight:

| Root key | Purpose |
| --- | --- |
| `cooking_recipes` | Cooking-pot recipes |
| `cutting_recipes` | Cutting-board recipes |
| `special_recipes` | Special-recipe cards in the recipe view |
| `farmersdelight_advancements` | Addon advancement trees |

```yaml
# Example: plugins/CraftEngine/resources/corndelight/configuration/farmersdelight/cooking_pot_recipes.yml
cooking_recipes:
  corndelight_boiled_corn:
    ingredients:
      - '#c:crops/corn'
    result: corndelight:boiled_corn
    experience: 0.15
    cook-time: 100
    category: meals
```

- Entry fields are written exactly like in `plugins/FarmersDelight/recipes/*.yml`; only the root key differs
  (table above).
- Pack content loads after the plugin's own recipes: on an id clash the plugin's entry wins and a skip line is
  logged. A pack loaded earlier likewise wins over a pack loaded later.
- File names are free — only root keys matter, and one root key may be split across several files.
- An advancement tree's namespace is the pack's `namespace` from its `pack.yml`. To publish trees for several
  namespaces from one pack, use CraftEngine's `farmersdelight_advancements#<namespace>:` form. CraftEngine
  already claims the `advancements` and `advancement` root keys (its parser is an empty stub), so advancement
  trees have to use `farmersdelight_advancements`.
- CraftEngine reads these sections while it loads packs, so run `/ce reload all` (or restart) after editing.
  `/fd reload recipes` only re-reads `plugins/FarmersDelight/recipes/*.yml`, not the packs.
- The old layouts are no longer read: an `advancements.yml` at the pack root, and `<pack>/farmersdelight/*.yml`.
  Move them under `<pack>/configuration/` and switch to the root keys above.
- The four bundled addons already use these sections: the `cooking_recipes` / `cutting_recipes` /
  `special_recipes` sections under `plugins/CraftEngine/resources/<addon>/configuration/farmersdelight/`
  (CrabbersDelight, BrewinAndChewin, BarbequesDelight, EndsDelight). Their former
  `plugins/<addon>/recipes/{cooking_pot_recipes,cutting_board_recipes,special_recipes}.yml` files are no longer
  read — see the [migration notes](migration.md).

## 5. Verify blocks and items exist

Give yourself a station item using CraftEngine's give command:

```
/ce item give <your-name> farmersdelight:cooking_pot
```

If you receive a cooking pot with the correct texture and name, item resolution and the resource pack are both
working. Place it on top of a heat source (a lit campfire, a lit `farmersdelight:stove`, magma, or lava —
see the Admin Wiki's `heat-sources` section) and right-click to open its GUI.

A few more ids worth spot-checking:

```
/ce item give <your-name> farmersdelight:cutting_board
/ce item give <your-name> farmersdelight:skillet
/ce item give <your-name> farmersdelight:stove
```

If the item is delivered but shows a **purple-and-black missing texture**, the plugin loaded correctly but the
resource pack did not reach your client — that is a pack problem, not a FarmersDelight problem. Go to
[Resource pack](resource-pack.md).

If the command reports an unknown item, the CraftEngine content did not parse — check the console for a
CraftEngine behavior error (see [Verifying behaviours loaded](verifying.md)) and run `/ce reload`.

## Next

[Resource pack →](resource-pack.md)
