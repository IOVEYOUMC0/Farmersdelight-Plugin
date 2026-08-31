
[简体中文](../zh-cn/crops.md)
# Crops

Farmer's Delight adds a handful of new crops on top of the vanilla ones. Each one starts as a **wild plant** you find growing in the world, which you harvest for seeds and then farm the same way you already farm wheat or carrots.

This page covers how to get each crop started, where it will grow, and how to harvest it. For the special soils that make crops grow faster, see [Rich Soil & Compost](soil.md). For the tomato's climbing trick, see [Rope](rope.md).

## Getting started: wild plants

Before you can farm anything new, you need to find the wild version growing in the world. Each wild plant favours particular biomes:

| Wild plant | Where it grows | Break it to get |
| --- | --- | --- |
| `farmersdelight:wild_tomatoes` | Badlands, deserts, jungles, savannas, swamps, warm oceans and similar warm/dry biomes | `farmersdelight:tomato_seeds` (plus a 20% chance of a `farmersdelight:tomato`) |
| `farmersdelight:wild_cabbages` | Beaches and snowy beaches | `farmersdelight:cabbage_seeds` (plus a 20% chance of a `farmersdelight:cabbage`) |
| `farmersdelight:wild_onions` | Forests, taigas, plains, savannas, meadows, cherry groves, jungles, swamps and more | `farmersdelight:onion` |
| `farmersdelight:wild_rice` | Swamps, mangrove swamps, jungles and rivers (growing in shallow water) | `farmersdelight:rice` |

{% hint style="info" %}
Break a wild plant **with shears** and you collect the decorative wild-plant block itself instead of the seeds. Break it by hand (or cut it on a [Cutting Board](cutting-board.md) with a knife) to get seeds and produce.
{% endhint %}

Cutting a wild plant on a cutting board with a knife is the most rewarding way to process it, and can yield extra produce or dye:

- **Wild tomatoes** → `farmersdelight:tomato_seeds` + a 20% chance of a tomato + a 10% chance of green dye
- **Wild cabbages** → `farmersdelight:cabbage_seeds` + a 50% chance of 2 yellow dye
- **Wild onions** → onion (and its own bonus roll)

---

## Tomatoes

Tomatoes are the most involved crop in the pack: they grow through three stages and can climb rope for a bigger harvest.

**Seeds:** `farmersdelight:tomato_seeds`. Obtain them from wild tomatoes (above), or craft them by placing a single `farmersdelight:rotten_tomato` in the crafting grid.

**Planting:** plant tomato seeds on `minecraft:farmland` or `farmersdelight:rich_soil_farmland`. They need a **light level of 9** or higher to grow.

**How they mature:**

1. Seeds first grow as **budding tomatoes** (`farmersdelight:budding_tomatoes`), passing through 4 stages (age 0–3).
2. Once fully grown and lit, a budding tomato turns into a **tomato bush** (`farmersdelight:tomatoes`).
3. A mature tomato bush (age 3) can be **harvested repeatedly**.

**Harvesting the bush:** right-click a fully grown tomato bush to pick **1–2 tomatoes** (with a rare 5% chance of a `farmersdelight:rotten_tomato`). The bush resets to its unripe stage and grows again — no replanting needed. Breaking the bush instead drops tomatoes plus a seed.

**Bone meal:** works on the tomato bush and speeds it toward its next harvest.

**Climbing rope:** if you place a [rope](rope.md) directly above a mature tomato bush, the vine will climb it, producing hanging **`farmersdelight:tomato_crop_on_rope`** blocks. Each hanging tomato is harvested by right-clicking it when ripe, exactly like the bush. See [Rope](rope.md#growing-tomatoes-on-rope) for the full setup.

---

## Cabbage

**Seeds:** `farmersdelight:cabbage_seeds`, from wild cabbages or from cutting them on a cutting board. You also get seeds back when you harvest a mature cabbage.

**Planting:** plant on `minecraft:farmland` or `farmersdelight:rich_soil_farmland`, in a light level of 9 or higher.

**Maturing:** cabbage grows through **8 stages** (age 0–7). Bone meal advances it several stages at once.

**Harvesting:** break a fully grown cabbage (age 7) to collect **1 `farmersdelight:cabbage`** plus one or more cabbage seeds. Break it early and you only recover seeds, so wait for it to ripen.

---

## Onion

**Seeds:** onions have no separate seed item — the `farmersdelight:onion` itself is both the food and the plantable. Gather your first onions from wild onions.

**Planting:** plant an onion on `minecraft:farmland` or `farmersdelight:rich_soil_farmland`, in a light level of 9 or higher.

**Maturing:** onions grow through 4 stages (age 0–3). Bone meal advances them.

**Harvesting:** break a fully grown onion (age 3) to collect an onion plus a Fortune-boosted bonus. Break it early for a single onion.

---

## Rice

Rice is a **water crop** grown in two stacked halves, and is harvested with a knife.

**Planting item:** `farmersdelight:rice` (a harvested `farmersdelight:rice_panicle` also plants rice).

**Where it grows:** rice must be planted on dirt-type blocks or a grass block **that is submerged in / touching water** — treat it like sugar cane's water requirement. It grows in fairly dim light (light level 6 or higher).

**Maturing:** the lower stalk grows through its stages (age 0–4); once it reaches its supporting stage it sends up a second block of **rice panicles** on top, which then ripen (age 0–3). Bone meal helps it along.

**Harvesting:** when the panicles on top are fully grown, harvest them **with a knife** (any item in the `#farmersdelight:tools/knives` tag). You get:

- `farmersdelight:rice` when you cut the ripe top with a knife (or `farmersdelight:rice_panicle` if it is broken another way),
- `farmersdelight:rice` from the lower stalk,
- and a piece of `farmersdelight:straw` (the knife harvest yields one straw).

{% hint style="info" %}
**Rice → panicle → rice.** A `farmersdelight:rice_panicle` can be crafted into 1 `farmersdelight:rice`, or cut on a cutting board into rice + straw. Both `farmersdelight:rice` and `farmersdelight:rice_panicle` can be compacted into storage blocks — see [Storage & Crates](storage.md).
{% endhint %}

---

## Where seeds and straw come from

Two materials underpin much of the pack's farming and crafting:

- **Seeds** for the new crops come from their wild plants (broken by hand, or cut on a cutting board). Tomato seeds can also be crafted from a rotten tomato.
- **Straw** (`farmersdelight:straw`) is the base of rope, canvas and compost. You obtain it by:
  - cutting rice panicles or wild rice on a cutting board,
  - harvesting mature rice,
  - or breaking **short grass, tall grass, or fully grown wheat with a knife** (grass has a 20% chance per break; mature wheat always drops straw).

Straw feeds directly into [Rope](rope.md) (2 straw → 4 rope) and [Organic Compost](soil.md#organic-compost).
