---
icon: box
---

# Resource pack

## CraftEngine serves the pack, not FarmersDelight

FarmersDelight ships the models, textures and lang entries for its blocks and items, but it does **not** host
or send a resource pack. It writes those files into `plugins/CraftEngine/resources/farmersdelight/`, and
**CraftEngine** assembles them into the pack and delivers it to players.

That means:

- Everything about **generating, hosting and sending** the pack is configured in CraftEngine, and documented
  in **[CraftEngine's own documentation](https://mo-mi.gitbook.io/xiaomomi-plugins/)**. Follow it there — there
  is nothing FarmersDelight-specific to configure.
- When you change a FarmersDelight resource file, you re-generate the pack the CraftEngine way. Use
  `/ce reload all` (config **and** pack) or `/ce reload pack` (pack only) — plain `/ce reload` reloads only
  configuration and does **not** rebuild the pack. This is exactly how you would refresh any other CraftEngine
  content.

## The two failure modes you will actually hit

### 1. Purple-and-black missing textures

If custom blocks and items appear as the classic purple/black checkerboard, the **server loaded fine but the
client is not using the current pack**. Almost always one of:

- you added or edited resources but did not regenerate the pack — run **`/ce reload all`** (plain
  `/ce reload` does not rebuild the pack);
- the player has not accepted / downloaded the pack, or their client has "Server Resource Packs" set to
  disabled — check the client's server settings;
- a cached older pack is stuck — have the player rejoin, or clear their pack cache.

Missing textures are a **pack delivery** problem. The plugin itself is fine — you can confirm by checking that
`/ce item give <player> farmersdelight:cooking_pot` still hands over an item with the right *name* (the name
comes from lang data, the texture from the pack).

### 2. Pack hosting / players never receive it

If players join and are never prompted for a pack, or the download fails, this is CraftEngine's pack-hosting
configuration — self-hosted, external host, or the built-in host, depending on how you set CraftEngine up.
This is entirely a CraftEngine concern; see CraftEngine's documentation on pack hosting. FarmersDelight has no
setting that affects delivery.

## When to re-run `/ce reload all`

Run it whenever you touch anything under `plugins/CraftEngine/resources/farmersdelight/` — a texture swap, a
model tweak, a block or recipe edit. `/ce reload all` re-reads the configuration and rebuilds the pack so the
next join (or the next in-game pack refresh) picks up the change. (Plain `/ce reload` reloads configuration
only and skips the pack rebuild, so a texture or model change would not reach clients.)

## Next

[First config tweaks →](first-config.md)
