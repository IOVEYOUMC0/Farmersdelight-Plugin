# Rich Soil & Compost

Farmer's Delight adds a small chain of fertile soils that make your farm grow faster. You compost scraps into **organic compost**, which matures into **rich soil**, which you can till into **rich soil farmland**. Any of them can also grow mushrooms — see [Mushroom Colonies](mushrooms.md).

The chain looks like this:

```
scraps  →  organic compost  →  rich soil  →  (till with a hoe)  →  rich soil farmland
```

## Organic Compost

`farmersdelight:organic_compost` is the entry point. It is a decaying heap that slowly turns into rich soil, and mushrooms can be planted on it.

**Crafting** (shapeless, either recipe):

- `minecraft:dirt` + 2 `farmersdelight:straw` + 2 `minecraft:rotten_flesh` + 4 `minecraft:bone_meal`
- `minecraft:dirt` + 2 `farmersdelight:straw` + 2 `minecraft:bone_meal` + 4 `farmersdelight:tree_bark`

**How it matures:** organic compost slowly "composts" through 8 stages (0–7). On each random tick it has a chance to advance a stage, and once it passes the final stage it **becomes `farmersdelight:rich_soil`**. You do not have to do anything — just place it and wait.

You can speed composting up by surrounding the heap:

- **Activator blocks nearby** each add a little chance. Activators include brown and red mushrooms, mushroom colonies, podzol, mycelium, other compost heaps, and rich soil / rich soil farmland.
- **Being well-lit** (open to the sky / bright) helps more than being in the dark.
- **Water next to the heap** gives an extra boost.

So a lit heap ringed with mushrooms and a water source composts noticeably faster than a lone heap in a dark corner.

## Rich Soil

`farmersdelight:rich_soil` is fully composted, fertile earth. You get it by letting organic compost finish composting (above), or by breaking rich soil farmland.

**What it does:** on its random ticks, rich soil has a chance to **fertilise the plant growing on top of it** (and, if there is nothing above, the plant directly below) — as if bone meal had been applied — showing the usual green sparkle. This makes it an excellent bed for trees, crops, sugar cane, bamboo and the like.

A few things are deliberately **left alone** so the world does not go haywire: grass, ferns, moss, nylium, big dripleaf, tall flowers (sunflowers, lilacs, peonies, rose bushes, pitcher plants), wild crops and mushroom colonies are all skipped and grow at their normal pace.

Rich soil also behaves like dirt for planting purposes (bamboo and saplings can be placed on it), and it can grow mushrooms.

{% hint style="info" %}
Plant a plain brown or red mushroom on rich soil and, on a later random tick, the soil turns it into a young **mushroom colony**. See [Mushroom Colonies](mushrooms.md).
{% endhint %}

## Rich Soil Farmland

`farmersdelight:rich_soil_farmland` is rich soil tilled for crops — the best farmland in the pack.

**How to make it:** **right-click rich soil with any hoe**, with an empty block above it. The soil turns into rich soil farmland (and your hoe takes a little wear).

**What it does:** it works like vanilla farmland, but when it is fully moist it also has a chance on its random ticks to **fertilise the crop planted on it**, so crops on rich soil farmland grow faster than on ordinary farmland.

**What you can plant on it:** the pack's crops (tomatoes, cabbage, onion) accept rich soil farmland as their soil, and so do the vanilla crops — wheat, carrots, potatoes, beetroots, and melon/pumpkin stems all plant and grow on it.

### Staying moist

{% hint style="warning" %}
**Behaviour being finalised.** The rehydration rules below describe how rich soil farmland is **intended** to behave; this exact behaviour is being reconciled in an ongoing fix and may differ slightly in the current build. It is documented here so you know the target.
{% endhint %}

Rich soil farmland gets and keeps its moisture the same way ordinary farmland does:

- It becomes **fully moist** when it is **rained on while open to the sky**, or when there is **water within a few blocks** (a 9×9 area around it, at its own level or one above).
- Without rain or nearby water it slowly **dries out**, losing moisture over time. Dry farmland still grows crops, just without the fertilising bonus.
- Its appearance switches to the darker, wet texture only when it is fully moist.

### Reverting

If a **solid block is placed directly on top** of rich soil farmland, it suffocates and **reverts to plain rich soil**, exactly like vanilla farmland turning back to dirt when covered. Crops, melons, pumpkins, fence gates and moving pistons do **not** count as a cover, so your planted crops are safe.

Breaking rich soil farmland drops `farmersdelight:rich_soil`, so tilling is never a one-way loss.
