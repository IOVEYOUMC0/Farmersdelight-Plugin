
[简体中文](../zh-cn/knife-drops.md)
# Knife Drops

Package: `com.huidu.farmersdelight.api.loot`
Class: `FarmersDelightKnifeDrops` — `final`, private constructor, `@ApiStatus.NonExtendable`.

Registration of extra mob drops harvested with a knife: the rule set behind FarmersDelight's ham,
leather, feather and string drops. Server owners configure these in `config.yml`; this facade is for
plugins that need to add rules at runtime — an addon shipping its own butchering items, a quest plugin
adding a conditional drop.

## How a rule fires

A rule fires when a player kills an **adult** entity of the matching type while holding a matching tool,
and a chance roll passes.

The rolled item is **contributed to the death event's drop list** rather than spawned directly, so other
plugins' loot handling sees it like any vanilla drop.

## API

```java
public static boolean register(String entityType, String normalItemId, String burningItemId,
                               double chance, double lootingMultiplier,
                               List<String> toolItems, List<String> toolTags);

public static boolean register(String entityType, String normalItemId, String burningItemId,
                               double chance, double lootingMultiplier);

public static boolean     unregister(String entityType);
public static Set<String> entityTypesWithRules();
public static boolean     hasRule(String entityType);
```

### register

| Parameter | Meaning |
| --- | --- |
| `entityType` | Bukkit `EntityType` name, case-insensitive — `"pig"`, `"COW"` both work. Trimmed and lowercased internally |
| `normalItemId` | item id to drop, `"ns:id"`. `"minecraft:air"` or `null` disables the drop |
| `burningItemId` | item id to drop instead when the entity dies on fire, or `null` to always use `normalItemId` |
| `chance` | base drop probability, 0..1 |
| `lootingMultiplier` | added to `chance` per Looting level on the killing tool; 0 to ignore Looting |
| `toolItems` | item ids that count as the harvesting tool. Empty or `null` falls back to the globally configured knife item list |
| `toolTags` | item tag ids that count as the harvesting tool, written **without** the leading `#`. Empty or `null` falls back to the global knife tag list |

Returns `false` when FarmersDelight is unavailable (not loaded, or not enabled) or `entityType` is null
or blank. Otherwise `true`.

**There is one rule per entity type, not a list.** Registering for a type that already has a rule —
including a built-in one — replaces it.

The five-argument overload is the seven-argument one with `null, null` for the tool matchers, so the
rule uses the globally configured knife items and tags.

```java
import com.huidu.farmersdelight.api.loot.FarmersDelightKnifeDrops;

// Uses whatever the server has configured as "a knife".
FarmersDelightKnifeDrops.register("rabbit", "myaddon:rabbit_cutlet", null, 0.35D, 0.10D);

// Only this addon's own butchering tools trigger it, and burnt mobs drop the cooked version.
FarmersDelightKnifeDrops.register(
        "sheep",
        "myaddon:raw_mutton_strips",
        "myaddon:seared_mutton_strips",
        0.5D,
        0.05D,
        List.of("myaddon:cleaver"),
        List.of("myaddon:butchering_tools"));
```

### unregister

Removes a rule previously added by `register`. Returns `true` when a rule registered *by this facade*
existed for that entity type.

It does not touch rules that came from `config.yml` or the built-in defaults — and it does not need to,
because those come back on the next reload anyway.

Call it on your plugin's disable.

### Queries

`entityTypesWithRules()` — the lowercase entity type names that currently have a knife drop rule, **from
any source** (built-in, config, or facade). Returns an empty set when FarmersDelight is unavailable.

`hasRule(entityType)` — true when a rule exists for that type, from any source. Case-insensitive.

Both reflect the currently live rule map, so a rule your addon registered and a rule the server owner
wrote in `config.yml` are indistinguishable through these two methods. Track your own registrations if
you need to tell them apart.

## Surviving `/fd reload`

Rules registered here persist across `/fd reload`. Verified at the reload site: the reload rebuilds the
whole rule map from the built-in defaults plus `config.yml`, and then re-applies everything registered
through this facade **on top**, publishing the finished map in a single write.

So a registration outlives an admin's reload without your addon having to listen for
`FarmersDelightReloadEvent`.

Two consequences of "on top":

* Your rule wins over a `config.yml` rule for the same entity type, permanently, until you unregister.
  Be conservative about registering for entity types FarmersDelight already covers (pig, cow, chicken)
  unless overriding is exactly what you mean.
* After `unregister`, the config/default rule for that type is restored at the *next* reload, not
  immediately.

## Threading

`register` / `unregister` write to a `ConcurrentHashMap` and are safe to call from `onEnable`, a reload
handler, or `onDisable`. The queries read the live map.

UNKNOWN: whether registering from an async thread mid-tick can be observed by a death event handler in
the same tick — the map is concurrent, so it will not corrupt, but the exact visibility ordering
relative to a concurrent kill is not specified by the source. Register at startup or on reload and this
does not arise.

## Related pages

* [Items](items.md)
* [Events](events.md)
