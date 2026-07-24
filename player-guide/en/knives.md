# Knives

The knife is Farmer's Delight's signature tool. It is both a **kitchen tool** (used on the
cutting board and read by several harvesting mechanics) and a **light weapon** that hits
fast for modest damage — and it makes animals drop extra ingredients when you kill with it.

## The knives

The port ships five knife tiers, one per material:

| Knife | Attack damage | Attack speed |
|-------|---------------|--------------|
| `farmersdelight:flint_knife` | 2.5 | 2.0 |
| `farmersdelight:golden_knife` | 1.5 | 2.0 |
| `farmersdelight:iron_knife` | 3.5 | 2.0 |
| `farmersdelight:diamond_knife` | 4.5 | 2.0 |
| `farmersdelight:netherite_knife` | 5.5 | 2.0 |

All knives swing at the same attack speed of 2.0 — faster than a sword, slower than nothing
— trading raw damage for a quicker recovery. Damage climbs with the material, so the flint
knife is your first weapon and the netherite knife is the top tier.

## Crafting

Four of the knives are crafted from a single material over a stick, in a vertical pattern:

- `farmersdelight:flint_knife` — flint + stick
- `farmersdelight:iron_knife` — iron ingot + stick
- `farmersdelight:golden_knife` — gold ingot + stick
- `farmersdelight:diamond_knife` — diamond + stick

The netherite knife is not crafted directly. Upgrade a `farmersdelight:diamond_knife` at a
smithing table with a netherite upgrade template and a netherite ingot, exactly like a
netherite tool. (Iron and golden knives can also be melted back down into a nugget in a
furnace or blast furnace.)

## Using a knife

Beyond being a weapon, the knife is the tool that drives Farmer's Delight's harvesting and
prep mechanics:

- **Cutting board** — place a knife-cuttable item on a cutting board, then use a knife on it
  to process it (for example splitting meat and fish into cuts, or slicing produce). This
  is the main way to get the "cut" foods.
- **Skillet** — cooking on the skillet uses the same knife detection.
- **Harvesting** — a knife is used to harvest mushroom colonies and rice.
- **Straw** — breaking grass, tall grass, mature wheat or mature rice with a knife yields
  `farmersdelight:straw`, an early crafting material.

Any of the five knives counts for all of these — the server tracks them as a group (the
`farmersdelight:knives` tag), so a flint knife works everywhere a netherite knife does.

## Knife mob drops

Killing an **adult** animal with a knife produces an extra drop on top of the mob's normal
loot. The full list is set by the server, but the port's defaults are:

| Mob | Extra drop | Chance |
|-----|-----------|--------|
| Pig | `farmersdelight:ham` (`farmersdelight:smoked_ham` if on fire) | 50% (+10% per Looting level) |
| Hoglin | `farmersdelight:ham` (`farmersdelight:smoked_ham` if on fire) | 100% |
| Cow, Mooshroom | `minecraft:leather` | 100% |
| Horse, Donkey, Mule, Llama, Trader Llama | `minecraft:leather` | 100% |
| Chicken | `minecraft:feather` | 100% |
| Spider, Cave Spider | `minecraft:string` | 100% |
| Rabbit | `minecraft:rabbit_hide` | 100% |
| Shulker | `minecraft:shulker_shell` | 100% |

Ham is the standout: a knife is the way to get `farmersdelight:ham`, which you then cook
into the very filling `farmersdelight:smoked_ham` — or, if you kill the pig while it is on
fire, it drops the smoked ham directly.

A server owner can add other tools as drop triggers, and can retune or disable any of these
drops; what your server drops may differ if it has been reconfigured.
