# Rope

Rope (`farmersdelight:rope`) is a climbable cord you can string up walls, hang down shafts, use to ring a distant bell, and grow tomatoes on.

**Crafting:** stack 2 `farmersdelight:straw` vertically to make **4 rope**. (Straw comes from rice, grass and wheat — see [Crops](crops.md#where-seeds-and-straw-come-from).)

## Placing and climbing

Place rope like a normal block. It shows as a hanging post and will visually **connect** to whatever is next to it — other ropes, walls, iron/copper bars, glass panes, and any solid block face it is placed against.

Rope is **climbable**: stand in it and hold jump to go up, or look down to descend, exactly like a ladder or vine. It also **cancels fall damage** — landing in a rope column resets your fall, so it doubles as a safe way down a shaft.

## Extending a rope downward

You do not have to place ropes one at a time down a hole. **Right-click an existing rope while holding rope** and it reels straight **down**, placing rope from the bottom of the column to the first block it hits:

- It fills every empty (or replaceable) space below the rope you clicked.
- It stops at the first solid block it cannot pass, and it will **not** reel through lava.
- It uses one rope from your hand per block placed.

This makes it quick to drop a rope ladder down a ravine or mineshaft — place one rope at the top, then click it with a stack of rope.

## Ringing a bell through rope

You can wire a bell to a rope pull. Build a **continuous rope column hanging below a bell**, then **right-click the rope with an empty hand** (while not sneaking). The rope rings the first bell it finds going upward.

- The bell can sit up to **24 blocks** above where you click (a server may tune this limit).
- The rope column must be unbroken — any gap or non-rope block stops the search.

This lets you ring a village or base bell from ground level without reaching the bell itself.

## Growing tomatoes on rope

Rope is what lets tomatoes grow **upward** for a bigger harvest.

1. Plant and grow a tomato bush (`farmersdelight:tomatoes`) on farmland — see [Crops → Tomatoes](crops.md#tomatoes).
2. Place a **rope directly above** the mature bush.
3. As the vine grows (on its own over time, or when you bone-meal it), it **climbs the rope**, consuming one rope and replacing it with a hanging tomato (`farmersdelight:tomato_crop_on_rope`).
4. Keep a rope above the topmost hanging tomato and the vine keeps climbing, up to a stack of hanging tomatoes above the ground bush (about 3 hanging tomatoes tall by default).

Each hanging tomato ripens and is **harvested by right-clicking it** for 1–2 tomatoes, just like the bush. Climbing needs a light level of 9 or higher.

{% hint style="info" %}
If you remove a hanging tomato, the rope it was occupying is put back, so the column stays intact for the tomatoes above it.
{% endhint %}
