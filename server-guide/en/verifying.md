---
icon: circle-check
---

# Verifying behaviours loaded

## The startup summary

A healthy boot prints a short summary to the console: a *Startup config* line (scheduler, cutting-board mode,
hopper settings, advancements) and a *Content ready* line that counts the cooking-pot and cutting-board
recipes, mob drop rules, pet foods and advancements that loaded. If those counts look right, content parsed.

For a per-subsystem breakdown (language files, block behaviors, tick manager, recipe counts, drop rules) turn
on the `startup` / `recipe` / `loot` debug categories — see [Troubleshooting](troubleshooting.md). You do not
need this on a normal server.

## FarmersDelight fails fast on a misconfigured block

This is the single most important thing to understand when reading the console.

Each custom block declares a **behavior** in its CraftEngine config, and a behavior can require a specific
block **property** to function. For example, `farmersdelight:rich_soil_farmland` needs a moisture property (a
water-level property named by `moisture-property`, defaulting to the vanilla `moisture`). Moisture drives the
entire block — drying out, rehydrating, and the growth boost.

If a block declares such a behavior but its config is **missing the required property**, FarmersDelight does
**not** silently load a broken block. Instead it **aborts that behavior's load** and logs an error that names:

- the **config node** (the block whose config is wrong), and
- the **property name** it looked for and could not find.

The block still exists in the world — it just **loses that behavior** (it would behave like inert farmland
whose moisture never changes, so refusing is the honest outcome).

### How to read it

> A CraftEngine behavior error in your console is a **signal, not a crash**. It is telling you exactly which
> block's config is wrong and which property is missing.

When you see one:

1. Note the block id and the property name in the message.
2. Open that block's definition under `plugins/CraftEngine/resources/farmersdelight/configuration/`.
3. Make sure the named property exists on the block (or that `moisture-property` / the relevant
   `*-property` argument points at a property the block actually declares).
4. Run `/ce reload` and confirm the error is gone.

The most common cause is a hand-edit to a shipped block definition that dropped or renamed a property the
behavior depends on. Restoring the property fixes it.

> **Related warnings that do *not* remove a behavior.** FarmersDelight also emits softer warnings for config
> shape mistakes — e.g. writing a grouped setting as a single value, an unknown key under a section, or a
> block-list entry naming a tag the server doesn't know. Those are logged and the block keeps working with
> defaults; they are worth cleaning up but are not the hard fail above.

## Quick in-game confirmation checklist

- `/ce item give <you> farmersdelight:cooking_pot` delivers a named, textured item.
- The pot placed on a heat source opens a working GUI.
- A `farmersdelight:cutting_board` accepts an item and a knife cut produces a result.
- Console shows the *Content ready* line and **no** CraftEngine behavior error.

If all four hold, the port is loaded correctly.

## Next

[Troubleshooting →](troubleshooting.md)
