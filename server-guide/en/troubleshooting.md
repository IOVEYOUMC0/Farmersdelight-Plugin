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
| FarmersDelight never enables; log says a dependency is missing | CraftEngine not installed | Install a server-compatible CraftEngine **26.8** build; it is a hard `depend`. |
| Plugin fails to load with an unsupported-version / class error | Server below MC 1.21.4 or Java below 21 | Run **Paper/Folia 1.21.4+** on **Java 21+**. |
| Custom items/blocks show purple-and-black textures | Client not using the current resource pack | Run **`/ce reload all`** to rebuild the pack (plain `/ce reload` won't); make sure the player accepted the pack. See [Resource pack](resource-pack.md). |
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
