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
- **Writes its own config.** `plugins/FarmersDelight/config.yml`, `gui.yml` and `lang/*.yml` appear.

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
