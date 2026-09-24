---
icon: wrench
---

[简体中文](../zh-cn/troubleshooting.md)

# Troubleshooting

## Where the logs are

Everything FarmersDelight prints goes to the normal server console and `logs/latest.log`. There is no separate
log file.

### Console / log language

The language of FarmersDelight's own log lines is controlled by `language:` in `config.yml`:

- **empty (`language: ''`)** — follows the JVM / system language;
- **`en_us` or `zh_cn`** — forces that language.

This governs **logs, console output and any text with no player context**. Individual players still get GUIs
and chat in their own client language regardless of this setting.

Resolution is graceful: if the configured language is not installed, FarmersDelight falls back to the
CraftEngine/JVM locale, then to a loaded fallback language, logging which fallback it used. On startup it also
merges any **new** language keys a plugin update introduced into your existing `lang/*.yml`, so your files stay
complete across upgrades. The bundled language files live in `plugins/FarmersDelight/lang/`.

### Debug logging

Off by default. When you are chasing a specific subsystem, enable it narrowly:

```yaml
debug:
  enabled: true
  categories: [cooking_pot, stove, skillet, tray]
```

Useful categories: `cooking_pot`, `stove`, `skillet`, `tray`, plus `startup`, `recipe`, `loot` for the
per-subsystem boot breakdown. Set `categories: []` (or leave `enabled: false`) for a quiet server; use `all`
or `*` only temporarily. Everything in these categories is also written at `FINE`, so raising the logger level
surfaces it without turning debug on.

## Symptom → cause → fix

| Symptom | Likely cause | Fix |
| --- | --- | --- |
| FarmersDelight never enables; log says a dependency is missing | CraftEngine not installed | Install a server-compatible CraftEngine **26.9.1** build; it is a hard `depend`. |
| Plugin fails to load with an unsupported-version / class error | Server below MC 1.21.4 or Java below 21 | Run **Paper/Folia 1.21.4+** on **Java 21+**. |
| Custom items/blocks show purple-and-black textures | Client not using the current resource pack | Run **`/ce reload all`** to rebuild the pack (plain `/ce reload` won't); make sure the player accepted the pack. See [Resource pack](resource-pack.md). |
| Handheld skillet interaction reports `ObfuscatedItemModelProcessor` / `NoClassDefFoundError` | CraftEngine 26.9.1 no longer ships the old client model processor | Install this FarmersDelight build and fully restart; the plugin falls back to the server-side item model and logs one warning, then run **`/ce reload all`** to rebuild the resource pack. |
| `/ce item give ... farmersdelight:cooking_pot` says unknown item | CraftEngine content didn't parse | Check console for a CraftEngine behavior error ([Verifying](verifying.md)); fix the named block config; `/ce reload`. |
| Console shows a CraftEngine behavior error naming a property | A block's config is missing a required property | Not a crash — a signal. Restore the named property on that block, then `/ce reload`. See [Verifying](verifying.md). |
| A block places but does nothing (e.g. farmland never changes moisture) | Its behavior aborted its load on a missing property | Same as above — read the behavior error, fix the property. |
| Cooking pot / skillet on a heat source won't cook | Block below isn't a recognised heat source | Confirm it against `heat-sources` in `config.yml` (lit campfire, `farmersdelight:stove[fire:true]`, magma, lava, fire). |
| Hoppers eat items / duplicate containers around stations | Hopper bridge conflict | Set `hopper-interactions.enabled: false` to isolate, then re-tune per-station toggles. See [First config](first-config.md). |
| A recipe you deleted came back after an update | `recipes.merge-missing-bundled` re-added it | Keep `merge-missing-bundled: false` if you delete recipes on purpose. See [Migration](migration.md). |
| A new setting from an update seems to do nothing | Reading a stale value | Config auto-migration reconciles keys on startup; if you hand-edited, confirm the key name matches the shipped `config.yml`. |
| Edited a shipped CE resource but the change didn't apply | Edit not picked up | Run `/ce reload all` (config edits alone can use `/ce reload`, but model/texture changes need the `all` pack rebuild). If you *deleted* a file expecting it to stay gone, note that `craftengine-resources.auto-completion: true` restores deleted files — set it `false` to keep deletions. |
| Console warns about active block / cooking-pot counts | Performance thresholds crossed | Warnings only, nothing is blocked. Tune `performance.*` thresholds or the per-station `tick-budget`. |
| Errors after a `/reload` or PlugMan action | Hot-reload tore down live references | Never hot-reload. `/stop` and start again, or use `/fd reload` / `/ce reload`. |

## Multiplayer skillet load

Handheld cooking checks only active cooking sessions and stops its timer when idle. Recipes use a material index; models are generated during CE pack builds. At normal TPS, N continuous sessions require about 20N checks and at most about 5N proactive progress updates per second, plus start/stop packets and ordinary inventory sync. These are code operation counts, not measured server MSPT, GC, or bandwidth results.

Disabling `skillet.handheld.progress-display.enabled` stops periodic display construction and packets while the model stays unchanged; ordinary inventory sync still preserves the cooking appearance. Disabling `skillet.handheld.enabled` stops handheld cooking and skips subsequent automatic model generation.

Placed skillets poll up to 512 tracked pans every 4 ticks by default. Exceeding `skillet.tick-budget` delays processing and may slow cooking. Smoke and sound are rolled before querying chunk viewers; the default probabilities skip this query on about 87.3% of polls. `performance.chunk-effect-packet-budget` caps effect broadcasts per chunk per tick; each broadcast still reaches multiple nearby players, so this is not a total network packet cap. For dense cooking areas, reduce `skillet.effects.viewer-distance`, lower effect probabilities, or disable effects, then measure the result with a profiler.

## Profile individual features

A player with `farmersdelight.admin` can run `/fd stats profile 200 all`, approximately 10 seconds at normal TPS. `/fd perf` aliases `/fd stats`. Both release and debug builds support sampling without enabling verbose debug logs. A second profile is refused while one is running; `/fd stats` shows current or last results. Moving or logging out does not prevent automatic stopping.

| Feature argument | Timed work |
| --- | --- |
| `all` | All features below |
| `cooking_pot` | One cooking pot update on its owning region |
| `handheld` | One active handheld session check, progress update and completion |
| `handheld_display` | Display refresh including copy construction and submission, excluding network-thread sending |
| `skillet` | One placed skillet update including called effects and serving logic |
| `stove` | One stove cooking update, excluding entity burns |

For example, use `/fd stats profile 600 handheld` for handheld load, then `/fd stats profile 600 handheld_display` for display refreshes. Duration is clamped to 20-12000 ticks; defaults are 200 ticks and `all`. Results include calls, calls per second, total/average/maximum time and P95. Times are milliseconds; call rates use actual elapsed time. Zero calls means the feature did not execute during that window, not that it is free.

Each feature retains the latest 4096 calls for percentiles; counts, totals and maxima cover the entire profile. Pot hotspots include world and coordinates, retain the first 4096 pots encountered, and report omitted calls. When sampling is off, no timer calls, sample recording, additional full-player scans or per-tick logs are added.

Feature timing runs on the executing thread and counts calls that start and finish within the profile. Timings include callees: `handheld_display` may already be included in `handheld`, so rows are not additive. Pot dispatch passes include synchronous updates on Paper but mainly task submission on Folia. Elapsed-time measurements include thread pauses and are not CPU usage, server MSPT or total network traffic. Use the server's existing spark profiler for call stacks, GC and server-wide bottlenecks.

Building with `-PdebugTools=true` produces `farmersdelight-1.0.2-debug.jar` with the `/fd debugtools` and `/fd debug` scene tools. Release builds produce `farmersdelight-1.0.2.jar`. These are the same plugin: install only one.

For a small manual load, use `/fd debugtools test cooking_pot 64 200`, `skillet`, `stove` or `all`. It creates nearby test stations in slices and starts sampling; each slice handles at most 16 positions. Use `/fd debugtools undo` to clean up or `/fd debugtools stop` to stop an unfinished batch. Test the handheld path with `/fd debugtools test handheld 1 200`; hold a skillet, cookable food and stand near a heat source. It uses the current held items and does not replace the inventory.

## The right reload for the job

| You changed… | Run |
| --- | --- |
| `config.yml` values | `/fd reload config` |
| `gui.yml` layout | `/fd reload gui` |
| Both, plus recipes/lang | `/fd reload all` |
| CraftEngine resources — models/textures (needs a pack rebuild) | `/ce reload all` (or `/ce reload pack`) |
| CraftEngine config only — blocks/items/recipes, no pack rebuild | `/ce reload` |
| Anything that needs a clean class load | `/stop`, then start the server |

`/fd cleanup` strips orphaned plugin state from loaded chunks if you ever need it (requires
`farmersdelight.admin`).

## Next

[Migration & upgrades →](migration.md)
