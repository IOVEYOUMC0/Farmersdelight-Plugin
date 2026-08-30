---
icon: list-check
---

[简体中文](../zh-cn/requirements.md)

# Requirements & load order

## Server software

FarmersDelight runs on **Paper** and on **Folia**. Folia is fully supported — the plugin declares
`folia-supported: true` and routes its scheduling through Folia-aware helpers.

| Requirement | Value |
| --- | --- |
| Server | Paper or Folia |
| Minecraft | **1.21.1** (the 1.21.x line) |
| `api-version` | `1.21` |
| Java | **21 or newer** |

The plugin is built and tested against **Paper 1.21.1**. Its declared `api-version` is `1.21`, so the 1.21 /
1.21.1 line is the floor. Newer 1.21.x builds are fine; running below 1.21 is not supported.

Java 21 is a hard requirement — the jar is compiled to the Java 21 bytecode level and will not load on an
older JRE. Use the same Java 21+ runtime CraftEngine and modern Paper already need.

## CraftEngine is a hard dependency

FarmersDelight is a **CraftEngine port**. Every block, item, recipe and model it ships is defined as
CraftEngine content. CraftEngine is listed under `depend:` in the plugin's `plugin.yml`, which means:

- CraftEngine **must** be installed, or FarmersDelight will not enable at all;
- the server loads CraftEngine **before** FarmersDelight automatically — you do not configure load order
  yourself;
- on startup FarmersDelight waits for CraftEngine to finish parsing its items and blocks before it registers
  recipes and content.

Install a CraftEngine 26.8 build compatible with your server. FarmersDelight is pinned to the **CraftEngine
26.8** API; do not substitute a 26.7.x build.

## Optional integrations (soft dependencies)

These are **not required**. FarmersDelight detects them at runtime and lights up the matching feature only when
the plugin is present. Everything works without any of them.

- **PlaceholderAPI** — exposes the `%farmersdelight_buff_...%` placeholders (see the Admin Wiki's *Buff
  bossbar API + PAPI placeholders* section).
- **AuraSkills** — lets cooking experience be credited as a skill instead of vanilla XP orbs
  (`experience-reward.mode` in `config.yml`).
- **UltimateAdvancementAPI** — drives the advancement tab UI.
- **Land / claim plugins** — WorldGuard, GriefPrevention, Lands, Towny, Residence, and a long list of others
  are recognised through a bundled protection layer, so station use and harvesting respect claims. No setup is
  needed beyond installing the claim plugin.

None of these change the install steps. If you have none of them, skip straight to
[Installation](install.md).

## Do not hot-reload the plugin

FarmersDelight, CraftEngine and any addon keep live references into their own class loaders (block behaviors,
per-chunk state, scheduled tasks). A `/reload` or a PlugMan-style unload tears those down mid-flight and leads
to errors. To apply changes, **stop and start the server** (`/stop`), or use the targeted
`/fd reload` / `/ce reload` commands described in [First config tweaks](first-config.md). The plugin
actively refuses hot management of itself to protect your world data.

## Next

[Installation →](install.md)
