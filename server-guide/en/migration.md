---
icon: arrows-rotate
---

[简体中文](../zh-cn/migration.md)

# Migration & upgrades

Whether you are coming fresh from the original mod or upgrading an existing FarmersDelight install, these are
the things that behave differently from "just overwrite the files". For the block-level migration reference
(legacy canvas_rug, `boost-chance` defaults, tomato-vine and stove-burn changes) see the **[Admin Wiki's
Migration notes](/#migration-notes)** — this page covers the operational rules.

## Coming from the mod

This is a **port**, not the mod. Intended gameplay matches Farmer's Delight, but only what these CraftEngine
configs actually ship exists on your server. Worlds are ordinary Paper/Folia worlds — there is no mod-world
import step. Install the plugins, generate the pack, and the content appears. Crops, stations, foods and
recipes are placed and crafted in-game exactly as a player would expect.

## Recipe ids are save keys

Recipe unlock progress (when `recipes.discovery.enabled: true`) is stored **per recipe id**. Treat a recipe's
id as a stable save key:

- **Renaming** a recipe id makes discovery see it as a brand-new recipe — players lose the "unlocked" state on
  the old id.
- Keep ids stable across your own edits if you use discovery, or accept that a rename resets that recipe's
  unlock state.

## Config auto-migrates renamed keys

On every startup FarmersDelight reconciles your `config.yml` against the one the running build ships, **without
changing any value you set**:

- keys the new build **renamed** are moved to their new name, carrying your value;
- keys the new build **retired** are removed;
- settings **added** by the update are merged in, with their explanatory comments.

So after a plugin update you do **not** need to hand-migrate `config.yml` — your tuned values survive, new
settings appear at their defaults, and dead keys are cleaned up. (A backup failure during this process is
reported in the log rather than aborting the update.)

Villager and wandering-trader offers now live in `world-data.yml`. Mob-extra and straw drop rules now live in
`drops.yml`. The cutting board's per-item and per-tag display tables now live in `display-overrides.yml`
(`items` / `tags`). When a legacy `config.yml` still contains `world-data:`, `drops:`,
`cutting-board.display-overrides` or `cutting-board.display-tag-overrides`, that section is moved to the matching
file automatically. Renamed settings inside `config.yml` (`hopper-interactions` per station became `allow-hopper`;
the `performance.*` knobs grouped under `warnings` / `budgets` / `proxy-display`) are rewritten in place. The
affected files are backed up before the move; edit the standalone files after that point.

## Shipped files are installed only when absent

Three different "install only if missing" rules matter when upgrading, because they mean **your edits to
shipped content survive updates but new shipped content may not arrive on its own**:

### CraftEngine resources

The full built-in resources are extracted **once**, on the first start. After that:

```yaml
craftengine-resources:
  auto-completion: true
```

`auto-completion: true` (default) **restores deleted/missing** resource files on later startups — but never
overwrites files that are present. If you intentionally deleted some shipped configs and don't want them
re-added, set this to `false`. Either way, **files you edited in place are never overwritten**, so a plugin
update that changes a shipped block/recipe file will *not* land on a server that already has that file —
merge such changes by hand (or delete the file and let auto-completion restore the new version).

### Recipe files

```yaml
recipes:
  merge-missing-bundled: false
```

A recipe file is written **only when it is entirely missing**. Recipes added by an update never reach a server
that already has `recipes/*.yml`. On startup the ids that exist in the jar but not on disk are listed once in
the console. To pull those new ids in, set `merge-missing-bundled: true` — but leave it **false** if you
deleted recipes on purpose, because merging brings every deleted recipe back. An id already on disk is never
overwritten under either setting. The same switch covers the bundled cards in `recipes/special_recipes.yml`,
and deleting a card from that file disables it while the switch stays off.

### Addon cooking-pot, cutting-board and special recipes moved into the packs

CrabbersDelight, BrewinAndChewin, BarbequesDelight and EndsDelight no longer ship their cooking-pot,
cutting-board and special recipes as `plugins/<addon>/recipes/*.yml`. The recipes now travel with each addon's
CraftEngine pack, at `plugins/CraftEngine/resources/<addon>/configuration/farmersdelight/`, under the
`cooking_recipes`, `cutting_recipes` and `special_recipes` root keys. Ids and entry fields are unchanged; what
changed is where they live and how they take effect:

- The old `plugins/<addon>/recipes/{cooking_pot_recipes,cutting_board_recipes,special_recipes}.yml` files are
  **no longer read** and can be deleted. If you edited one, move those edits into the pack directory above and
  run `/ce reload all` (or restart). Upgrading releases the new pack files automatically; an existing file of
  the same name is never overwritten.
- Editing these recipes takes `/ce reload all` (or a restart) instead of `/fd reload`: CraftEngine reads pack
  content while it loads packs.
- Each addon's own recipes (Brewin' And Chewin's keg fermenting and pouring, BarbequesDelight's grilling and
  skewering) moved into its pack too, at
  `plugins/CraftEngine/resources/<addon>/configuration/recipes/`. They keep one extra layer:
  `plugins/<addon>/recipes/<same file>.yml` is **still read on top** and wins for the ids it defines — that is
  the file Brewin' And Chewin's in-game keg recipe editor writes. So the old file may stay as an override layer
  (identical to the shipped defaults, so nothing changes) or be deleted in favour of the pack copy; editing the
  pack copy takes `/ce reload all` as well.

### Loot-injection datapack

Installed into each world on first enable and then left alone — your later edits to the datapack files survive
plugin updates. See [First config](first-config.md).

## Upgrade checklist

1. Stop the server (`/stop`) — do not hot-swap the jar.
2. Replace both the FarmersDelight jar and, if updated, the resource pack.
3. Start the server. Let `config.yml`, `world-data.yml` and `drops.yml` auto-migrate and CraftEngine resources auto-complete.
4. Read the console: check the *Content ready* line and the one-time "missing bundled recipe" notice; decide
   whether you want those recipes (`merge-missing-bundled`).
5. If the update changed shipped CE resource files you had edited, merge those changes by hand.
6. Regenerate the pack (`/ce reload all`) and confirm textures in-game.
7. Skim the [Admin Wiki Migration notes](/#migration-notes) for any block-specific one-time migration steps
   your upgrade calls for.

## Back to the guide

[Server Owner Guide home →](README.md)
