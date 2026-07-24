# Food

Farmer's Delight adds a large kitchen's worth of new food to the game — well over a
hundred items, from raw ingredients you grow or cut, to finished dishes you cook. This
page explains how the food works so you know what to eat and why. It does not list every
item; it groups them into families and gives verified examples.

## How nutrition and saturation work

Every food restores two things when eaten:

- **Nutrition** — refills your hunger bar. Each point is half a drumstick, so a food with
  nutrition `6` fills three full drumsticks.
- **Saturation** — a hidden reserve that drains *before* your visible hunger bar does. The
  higher a food's saturation, the longer you can run, jump and fight before you start
  getting hungry again. This is why a good cooked meal keeps you full far longer than
  its hunger bar refill alone suggests.

A quick, low-effort snack refills a little hunger with little saturation; a proper cooked
meal refills a lot of both. For example:

| Food | Nutrition | Saturation |
|------|-----------|------------|
| `farmersdelight:tomato` | 1 | 0.6 |
| `farmersdelight:fried_egg` | 4 | 3.2 |
| `farmersdelight:hamburger` | 11 | 17.6 |
| `farmersdelight:beef_stew` | 12 | 19.2 |

Most foods can only be eaten when your hunger bar is not full, exactly like vanilla. A few
are marked "can always eat" and go down even on a full bar — for example
`farmersdelight:melon_popsicle` and `farmersdelight:glow_berry_custard`, and every drink.

## Food families

### Raw crops and ingredients

The plants you grow or gather, eaten straight or used in recipes. Low nutrition on their
own — they shine once cooked. Examples: `farmersdelight:cabbage` (2 / 1.6),
`farmersdelight:tomato` (1 / 0.6), `farmersdelight:onion` (2 / 1.6), plus `rice`,
`rice_panicle` and the seed items.

### Knife cuts

Cutting animal food or produce on a **cutting board** with a knife splits it into smaller
portions — you get more meals out of one drop, at a lower value per piece. Examples:
`farmersdelight:minced_beef` (2 / 1.2), `farmersdelight:chicken_cuts` (1 / 0.6),
`farmersdelight:bacon` (2 / 1.2), `farmersdelight:cod_slice` (1 / 0.2),
`farmersdelight:salmon_slice`, `farmersdelight:mutton_chops`, `farmersdelight:cabbage_leaf`.

Raw cuts of chicken carry the same risk as raw chicken: eating raw
`farmersdelight:chicken_cuts` has a chance to give you Hunger. Cook the cuts first.

### Cooked staples

Cuts cooked in a furnace, smoker, campfire or the **skillet** become filling staples:
`farmersdelight:cooked_bacon` (4 / 6.4), `farmersdelight:beef_patty` (4 / 6.4),
`farmersdelight:cooked_chicken_cuts` (3 / 3.6), `farmersdelight:cooked_cod_slice`,
`farmersdelight:cooked_salmon_slice`, `farmersdelight:cooked_mutton_chops`,
`farmersdelight:fried_egg` (4 / 3.2). Ham is a knife drop from pigs and hoglins:
`farmersdelight:ham` (5 / 3) cooks into `farmersdelight:smoked_ham` (10 / 16).

### Handhelds and sandwiches

Fast, high-value meals assembled at a crafting table — good travel food.
`farmersdelight:hamburger` (11 / 17.6), `farmersdelight:chicken_sandwich` (10 / 16),
`farmersdelight:bacon_sandwich` (10 / 16), `farmersdelight:egg_sandwich` (8 / 12.8),
`farmersdelight:mutton_wrap` (10 / 16), `farmersdelight:dumplings` (8 / 12.8),
`farmersdelight:barbecue_stick` (8 / 14.4, hands back a stick),
`farmersdelight:kelp_roll` (12 / 12).

### Soups, stews and cooked meals

The heart of the mod, made in the **cooking pot** and served in a bowl (the bowl is
returned to you when eaten). These are the most filling foods in the game and most of them
grant the **Nourishment** effect — see the [Effects](effects.md) page. Examples:
`farmersdelight:beef_stew` (12 / 19.2), `farmersdelight:vegetable_soup` (12 / 19.2),
`farmersdelight:noodle_soup` (14 / 21), `farmersdelight:mushroom_rice`,
`farmersdelight:cooked_rice` (6 / 4.8).

### Salads

Bowl meals with a bonus effect. `farmersdelight:mixed_salad` and
`farmersdelight:fruit_salad` (both 6 / 7.2) give a short burst of Regeneration.
`farmersdelight:nether_salad` (5 / 4) is edible but has a chance to give Nausea.

### Sweets and baked goods

Pies, cakes and cookies. `farmersdelight:sweet_berry_cookie` and
`farmersdelight:honey_cookie` (both 2 / 0.4, crafted 8 at a time),
`farmersdelight:cake_slice` (2 / 0.4, gives a short Speed boost),
`farmersdelight:pie_crust` (2 / 0.8, the base for pies).

### Drinks

Bottled drinks, drunk like a potion, that stack to 16 and can always be consumed:

- `farmersdelight:apple_cider` — grants Absorption.
- `farmersdelight:melon_juice` — instantly restores a little health.
- `farmersdelight:hot_cocoa` — removes several negative effects (Poison, Weakness,
  Slowness, Mining Fatigue, Nausea, Blindness, Hunger, Wither).
- `farmersdelight:glow_berry_custard` — filling (7 / 8.4) and gives brief Glowing.
- `farmersdelight:milk_bottle` — a lighter milk. Removes one random status effect (see
  [Effects](effects.md)); leaves an empty glass bottle.

### Animal feed

Special foods used on animals rather than eaten yourself. `farmersdelight:dog_food` fed to
a tamed wolf grants it Speed and Strength; `farmersdelight:horse_feed` used on a horse,
donkey or mule grants Speed and Jump Boost and can tempt them to follow you.

## Placeable feast foods

Some dishes are big enough to place in the world as a shared **feast** block that several
players can eat from. Sneak or right-click the placed block with an empty hand (holding a
bowl for the stews) to take a serving; each feast holds four servings before it is used up.

The feast blocks are:

- `farmersdelight:roast_chicken_block`
- `farmersdelight:stuffed_pumpkin_block`
- `farmersdelight:honey_glazed_ham_block`
- `farmersdelight:shepherds_pie_block`
- `farmersdelight:gleaming_salad_block`
- `farmersdelight:rice_roll_medley_block`

Pies are also placeable and are cut into slices: `farmersdelight:apple_pie`,
`farmersdelight:sweet_berry_cheesecake`, `farmersdelight:chocolate_pie`, and the vanilla
`minecraft:pumpkin_pie` (placeable while sneaking). A whole pie yields four slices; each
slice (for example `farmersdelight:apple_pie_slice`) restores 3 / 1.8 and gives a brief
Speed boost.

Items that can be placed show a **Placeable** hint in their tooltip.

## Where food comes from

- **Grow it** — cabbage, tomatoes, onions and rice are new crops; see how to plant and
  harvest them in the crops section of this guide.
- **Cut it** — a [knife](knives.md) on a cutting board turns raw meat and fish into cuts.
- **Cook it** — the cooking pot (soups and stews), the skillet (fast pan-frying) and the
  stove (a heat source you can cook on top of) turn ingredients into finished meals.
- **Kill for it** — a knife produces extra drops from animals, including ham from pigs.
- **Trade for it** — farmer villagers and the wandering trader deal in the new crops and
  seeds.
