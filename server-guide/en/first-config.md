---
icon: sliders
---

# First config tweaks

The defaults in `plugins/FarmersDelight/config.yml` are tuned to behave like the original mod, so a fresh
install is playable untouched. This page lists only the handful of settings **most owners change on day one**
and links into the **[Admin Wiki](/)** for the full explanation of each. It does not re-document the config —
the Admin Wiki's *Master config (`config.yml`)* section is the reference.

Apply changes with `/fd reload config` (or restart). Do **not** `/reload` the whole server.

## Console / log language

```yaml
language: ''
```

Empty follows the JVM/system language. Force it with `zh_cn` or `en_us`. This affects **server logs, console
text and messages with no player context** — individual players still see GUIs and chat in their own client
language. See [Troubleshooting](troubleshooting.md) for how the console language resolves.

## Buff display (bossbars)

```yaml
buff:
  enabled: true
  display:
    enabled: true
    channels:
      - bossbar
    layout-mode: stacked
    styles:
      nourishment: {color: GREEN, overlay: PROGRESS}
      comfort:     {color: BLUE,  overlay: PROGRESS}
```

Controls how the Nourishment/Comfort buffs (and any addon buffs) are shown. Switch `channels` to `actionbar`
or `tab_footer` if another plugin already owns the boss-bar area, or set `display.enabled: false` to keep the
effects but hide the bars. Full detail: the Admin Wiki's *Effects* and *Buff bossbar API* sections.

## Effects: Nourishment / Comfort

```yaml
events:
  - on: consume
    functions:
      - type: farmersdelight:nourishment
        duration: 180
```

Food assignments now live in each item's CraftEngine configuration. Use `farmersdelight:nourishment` or
`farmersdelight:comfort` as an `on: consume` function; `duration` is in seconds. The built-in assignments are in
`plugins/CraftEngine/resources/farmersdelight/configuration/items.yml`. Global buff display, persistence and
Comfort healing settings remain in `plugins/FarmersDelight/config.yml`.

## Hopper interactions

```yaml
hopper-interactions:
  enabled: true
```

The master switch for the hopper ↔ station bridge (cooking pot, cutting board, skillet). If hoppers misbehave
with your other plugins, **turn this master switch off first** to isolate the problem, then re-enable and tune
the per-station `hopper-interactions:` toggles. Details in the Admin Wiki's `cutting-board` and `cooking-pot`
notes.

## Recipe discovery (locked recipe books)

```yaml
recipes:
  discovery:
    enabled: false
```

Off by default — every recipe is visible in the books immediately. Turn it on to make recipes start **locked**
in FarmersDelight's own cooking-pot / cutting-board viewers and reveal per-player as they unlock. Locking only
affects the book display; it never blocks crafting at a station. See the Admin Wiki (and the developer *Recipe
discovery* page) for `locked-display`, `unlock-on-obtain` and `notify`.

> Recipe unlock progress is keyed by **recipe id**. Renaming a recipe id resets discovery for it. See
> [Migration & upgrades](migration.md).

## Vanilla loot injection

```yaml
loot-injection:
  install-datapack: true
```

On first enable, FarmersDelight installs a datapack that injects its items into vanilla chest / mob / grass
loot tables. Set to `false` if you manage loot tables yourself or with another plugin. The datapack needs a
restart (or `/reload` of data packs) to take effect, and your later edits to the datapack files are preserved
across plugin updates.

## Advancements

```yaml
advancements:
  enabled: true
```

Turn the whole advancement tree off with `enabled: false`. Left on, `auto-disable-missing: true` hides
advancements whose CraftEngine content you deleted so the tree stays completable. Reference: the Admin Wiki's
commands/migration notes and the developer *Advancements* page.

## Where the deeper knobs live

Performance budgets, particle/sound effects, display offsets, heat sources, mob-extra drops,
villager/wandering-trader trades, composting and furnace-fuel values are in `config.yml`. Pet food and food-buff
assignments are in the CraftEngine item configuration. Both files contain inline comments and are documented in
the Admin Wiki. You will rarely need them on day one.

## Next

[Verifying behaviours loaded →](verifying.md)
