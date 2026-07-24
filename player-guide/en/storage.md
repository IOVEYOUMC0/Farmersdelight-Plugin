# Storage & Crates

Farmer's Delight gives you two ways to tidy up a harvest: **crates and bales** that compact produce into solid blocks, and the **basket**, a container that can gather dropped items for you.

## Crates and bales

Crates are the classic "9 items into one block" storage blocks — great for stacking a harvest in a pantry and turning it back into items when you need it. Each is crafted from a full 3×3 of its crop and unpacked shapelessly back into 9 of that item.

| Block | Made from | Unpacks into |
| --- | --- | --- |
| `farmersdelight:carrot_crate` | 9 `minecraft:carrot` | 9 carrots |
| `farmersdelight:potato_crate` | 9 `minecraft:potato` | 9 potatoes |
| `farmersdelight:beetroot_crate` | 9 `minecraft:beetroot` | 9 beetroots |
| `farmersdelight:cabbage_crate` | 9 `farmersdelight:cabbage` | 9 cabbages |
| `farmersdelight:tomato_crate` | 9 `farmersdelight:tomato` | 9 tomatoes |
| `farmersdelight:onion_crate` | 9 `farmersdelight:onion` | 9 onions |

Rice and straw have their own compaction blocks:

| Block | Made from | Unpacks into |
| --- | --- | --- |
| `farmersdelight:rice_bag` | 9 `farmersdelight:rice` | 9 rice |
| `farmersdelight:rice_bale` | 9 `farmersdelight:rice_panicle` | 9 rice panicles |
| `farmersdelight:straw_bale` | 9 `farmersdelight:straw` | 9 straw |

Crates are solid wooden blocks (mine them with an axe). Bales are soft: both the **straw bale** and the **rice bale** are flammable and both cushion your fall like a hay bale.

## Cabinets

Cabinets are proper storage containers with a **27-slot inventory** (three rows), opened by right-clicking like a barrel. There is one for each wood type — `farmersdelight:oak_cabinet`, `farmersdelight:spruce_cabinet`, `farmersdelight:birch_cabinet`, `farmersdelight:jungle_cabinet`, `farmersdelight:acacia_cabinet`, `farmersdelight:dark_oak_cabinet`, `farmersdelight:mangrove_cabinet`, `farmersdelight:cherry_cabinet`, `farmersdelight:bamboo_cabinet`, `farmersdelight:pale_oak_cabinet`, `farmersdelight:crimson_cabinet` and `farmersdelight:warped_cabinet`.

**Crafting:** three matching slabs on the top and bottom rows, with two matching trapdoors on the sides of the middle row:

```
S S S
D   D
S S S
```

(S = wooden slab, D = wooden trapdoor, both of the same wood). They face the way you place them and drop their contents when broken.

## The Basket

The basket (`farmersdelight:basket`) is a storage container that can also **collect dropped items** from the direction it faces — handy for the bottom of an automatic farm.

**Crafting:** bamboo and canvas in this pattern (B = `minecraft:bamboo`, C = `farmersdelight:canvas`):

```
B B
C C
B C B
```

(Canvas is made from straw — 4 straw → 1 canvas — so a basket is ultimately a straw product.)

**As storage:** place it and open it for a **27-slot inventory** (three rows). It works with hoppers (items can be piped in and out) and emits a comparator signal based on how full it is. Break it and it drops its contents.

**Facing:** a basket faces the side you place it against, and that is the side it collects from. Place it on the underside of a block to make it face down and catch items falling onto it.

### Auto-collecting dropped items

{% hint style="warning" %}
**Behaviour being finalised.** The item-collecting behaviour below is how the basket is **intended** to work; this feature is being reconciled in an ongoing fix and may not be fully active in the current build. It is documented here so you know the target behaviour.
{% endhint %}

A placed basket periodically **scans the cell it faces** (its own space plus one block in the facing direction) for dropped item entities and pulls them into its inventory:

- It picks items up a short moment apart (a brief cooldown after each pickup), not all at once.
- It stops collecting once it is completely full.

This makes the basket a simple directional item collector: point one downward under a tomato bush, a cutting board, or a mob/crop drop point, and it quietly stockpiles what lands in front of it — then a hopper underneath can carry the overflow onward.
