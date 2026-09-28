# FarmersDelight Plugin

**English** | [中文](README.zh-cn.md)

FarmersDelight is a Paper/Folia plugin port of **Farmer's Delight**, powered by CraftEngine. It adds crops, rich soil, cooking stations, knives, food, recipe discovery, advancements and a public API for addons.

## Features

- CraftEngine items, blocks, models, resource packs and loot integration.
- Cooking pot, cutting board, stove and skillet gameplay.
- Configurable crops, farmland, ropes, mushroom colonies and storage blocks.
- Nourishment, Comfort, food effects and recipe-book integration.
- Folia-safe scheduling and a stable `com.huidu.farmersdelight.api` addon API.
- Optional addons for Brewin' And Chewin', End's Delight, Expanded Delight, Crabber's Delight, Barbeque's Delight and Villagers' Delight.

## Requirements

- Paper or Folia 1.21.4 or newer
- Java 21
- CraftEngine 26.8.2 or newer (compiled against 26.9.1; 26.8.2, 26.9 and 26.9.1 verified on a live server)

Install CraftEngine first, then place the FarmersDelight jar in `plugins/`. Use `/fd reload` for plugin configuration and `/ce reload` after changing CraftEngine resources.

## Documentation

The complete player, server, addon and API documentation is maintained in the [FarmersdelightPluginWiKi](https://github.com/IOVEYOUMC0/FarmersdelightPluginWiKi) repository. It can be connected to GitBook through its GitHub integration.

## Building

```text
./gradlew build
```

The build resolves CraftEngine 26.9.1 from its official Maven repository; pass `-PceVersion=<version>` to compile against another release. Addons compile against the generated API-only FarmersDelight jar and must be checked out beside this repository.

## License

GNU Affero General Public License v3.0 only. See [LICENSE](LICENSE).

The plugin is fully open source: the recipe editor, recipe-to-recipe jumps and handheld skillet cooking are all in this repository. You may use, modify and redistribute it, including for a fee, as long as AGPL-3.0 is honoured: keep the copyright and licence notices, ship the complete corresponding source of the version you distribute, license your modified version under AGPL-3.0, and if you run a modified version as a network service, offer its source to the users of that service.

Third-party content (the ported Farmer's Delight assets and the bundled libraries, all MIT) keeps its own notices; see [NOTICE.md](NOTICE.md).

This repository is the only source of the plugin. Builds published by anyone else, with or without added code, are not ours.
