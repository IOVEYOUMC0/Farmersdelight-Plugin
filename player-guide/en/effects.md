# Effects: Nourishment & Comfort

Farmer's Delight adds two custom food buffs on top of vanilla potion effects:
**Nourishment** and **Comfort**. They are not brewed — you get them by eating the right
foods. Unlike vanilla effects they are shown on a **boss bar** rather than in the inventory
effect list.

## Nourishment

**What it does:** while Nourishment is active, your hunger stops draining. In game terms it
keeps resetting the exhaustion that would normally eat into your saturation and hunger, so
a well-fed player stays full for the whole duration. (If your health is regenerating from
saturation, that regeneration is left alone — Nourishment does not fight vanilla healing.)

**How you get it:** eat one of the hearty cooked meals. Nourishment is the reward for
actually cooking, and the better the dish the longer it lasts. The duration follows the
original mod's tiers:

| Duration | Example dishes |
|----------|----------------|
| 30 s | `farmersdelight:cooked_rice` |
| 60 s | `farmersdelight:bone_broth`, `farmersdelight:bacon_and_eggs`, `farmersdelight:ratatouille` |
| 3 min | `farmersdelight:beef_stew`, `farmersdelight:vegetable_soup`, `farmersdelight:chicken_soup`, `farmersdelight:mushroom_rice`, `farmersdelight:steak_and_potatoes`, and more |
| 5 min | `farmersdelight:pumpkin_soup`, `farmersdelight:noodle_soup`, `farmersdelight:roast_chicken`, `farmersdelight:honey_glazed_ham`, `farmersdelight:shepherds_pie`, and the other feasts |

A few vanilla soups also grant it — `minecraft:mushroom_stew`, `minecraft:beetroot_soup`
and `minecraft:rabbit_stew` all give 3–5 minutes. Eating another Nourishment food while the
buff is active refreshes it to whichever remaining time is longer.

## Comfort

**What it does:** Comfort slowly heals you over time while you are not otherwise being
healed. It is the original mod's older healing buff.

**Availability:** the original mod deprecated Comfort in favour of Nourishment, and this
port ships with Comfort **turned off** — no food grants it by default. The mechanic is
fully built in, so a server owner can switch it on and assign foods to it in the
configuration if they want it back. If your server has enabled it, it will behave and
display exactly like Nourishment, on its own boss bar.

## The boss bar display

Active Nourishment / Comfort appears as a boss bar at the top of your screen showing the
effect name and the time remaining, with the bar draining as the effect runs out:

- **Nourishment** — green bar.
- **Comfort** — blue bar.

Each active buff gets its own bar. The buff also survives relogging and server restarts —
if you log off with Nourishment running, you get the remaining time back when you return.

Server owners can restyle the bars, move the display to the action bar or the player-list
footer, or turn the display off entirely; these are configuration choices and do not change
what the effects do.

## Clearing effects with milk

Milk clears effects here just as it does in vanilla, and it works on these custom buffs too:

- **`minecraft:milk_bucket`** — clears **everything**: all your active custom buffs
  (Nourishment, Comfort, and any effects added by companion content) alongside the vanilla
  effects milk normally removes.
- **`farmersdelight:milk_bottle`** — the lighter option. It removes **one** effect at
  random from among your active vanilla potion effects and buffs, and hands you back an
  empty glass bottle. Use it when you want to gamble off a single bad effect without wiping
  your good ones too.

So if you want to keep your Nourishment, do not drink a full milk bucket.
