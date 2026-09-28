#!/usr/bin/env python3
"""Keep the cooking pot meal icons in sync with the recipes that produce meals.

Each pack ships ``configuration/meal_icons.yml`` with one ``images:`` entry per cooking pot result, named
``<namespace>:meal_<item>``; the plugin derives that id from the meal item's own CraftEngine id (see
``BlockBreakListener.mealGlyphId``) and falls back to ``farmersdelight:meal_<material>`` for vanilla results.

This tool derives the entries from the recipes that actually exist, so deleting an item (or its recipe, or its
texture) prunes the now-invalid icon instead of leaving a dead entry behind:

* results are read from the plugin recipe files (root key ``cooking_pot_recipes``) and from the CraftEngine pack
  under ``src/main/resources/craftengine/`` (root key ``cooking_recipes``, the CraftEngine section id),
  including ``custom_cooking_pot_recipes``;
* a result whose texture is missing or is not 16x16 is reported and skipped;
* entries whose item is no longer produced by that pack are removed, existing entries keep their order and new
  ones are appended;
* vanilla results (``minecraft:*``) are registered by the FarmersDelight pack and reference the client's own
  vanilla texture, so no local file is required for them.

Repo-scoped: this repository ships only the farmersdelight pack. The standalone packs under ``packs/`` and the
addons' packs live in the monorepo parent directory (``tools/meal_icons.py`` there covers them).

Usage:
    python tools/meal_icons.py            # rewrite the meal_icons.yml files
    python tools/meal_icons.py --check    # report differences, change nothing (exit 1 when out of sync)
"""

from __future__ import annotations

import argparse
import struct
import sys
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parent.parent

# pack namespace -> repo-relative pack root (the directory holding configuration/ and resourcepack/)
PACKS = {
    "farmersdelight": "src/main/resources/craftengine/farmersdelight",
}

# pack namespace -> font the meal glyphs are registered in
FONTS = {
    "farmersdelight": "farmersdelight:custom",
}

# Every file that may declare pot recipes. The list is discovered rather than hardcoded: a missed file makes
# every result of that pack look "no longer produced" and would prune valid meal icons. Files without any of
# the root keys are ignored by result_ids().
RECIPE_FILES = sorted(
    {str(path.relative_to(ROOT)) for pattern in (
        "src/main/resources/recipes/*.yml",
        "src/main/resources/craftengine/**/*.yml",
    ) for path in ROOT.glob(pattern)}
)

HEIGHT = 16
ASCENT = 8


def png_size(path: Path) -> tuple[int, int]:
    data = path.read_bytes()[:24]
    if len(data) < 24 or data[:8] != b"\x89PNG\r\n\x1a\n":
        raise ValueError("not a PNG")
    width, height = struct.unpack(">II", data[16:24])
    return width, height


def result_ids(recipe_file: Path) -> list[str]:
    """Every result item id the file's pot recipes produce, in file order."""
    if not recipe_file.exists():
        return []
    data = yaml.safe_load(recipe_file.read_text(encoding="utf-8")) or {}
    ids: list[str] = []
    for section in ("cooking_pot_recipes", "cooking_recipes", "custom_cooking_pot_recipes"):
        for recipe in (data.get(section) or {}).values():
            if not isinstance(recipe, dict):
                continue
            results = recipe.get("result")
            for entry in results if isinstance(results, list) else [results]:
                if isinstance(entry, str):
                    ids.append(entry)
                elif isinstance(entry, dict) and isinstance(entry.get("item"), str):
                    ids.append(entry["item"])
    return ids


def collect_results() -> tuple[dict[str, list[tuple[str, str]]], list[str]]:
    """icon namespace -> [(item value, texture namespace)], plus warnings collected while reading the recipes.

    A vanilla result is registered by the pack that owns the pot tooltip (FarmersDelight) and keeps
    ``minecraft`` as its texture namespace; every custom result registers under its own namespace.
    """
    by_namespace: dict[str, list[tuple[str, str]]] = {}
    warnings: list[str] = []
    for rel in RECIPE_FILES:
        for item_id in result_ids(ROOT / rel):
            if ":" not in item_id:
                warnings.append(f"{rel}: result '{item_id}' has no namespace, skipped")
                continue
            namespace, value = item_id.split(":", 1)
            icon_namespace = "farmersdelight" if namespace == "minecraft" else namespace
            produced = by_namespace.setdefault(icon_namespace, [])
            if (value, namespace) not in produced:
                produced.append((value, namespace))
    return by_namespace, warnings


def texture_for(texture_namespace: str, value: str) -> tuple[str | None, str | None]:
    """The `file:` value for a meal icon, or a warning explaining why it cannot be built."""
    if texture_namespace == "minecraft":
        # Vanilla results: the client resolves its own texture; CraftEngine needs the explicit height because
        # there is no server-side PNG to measure.
        return f"minecraft:item/{value}.png", None
    pack = PACKS.get(texture_namespace)
    if pack is None:
        return None, f"no pack root registered for namespace '{texture_namespace}' (add it to PACKS)"
    path = ROOT / pack / "resourcepack" / "assets" / texture_namespace / "textures" / "item" / f"{value}.png"
    if not path.exists():
        return None, f"{texture_namespace}:{value}: texture missing ({path.relative_to(ROOT).as_posix()})"
    size = png_size(path)
    if size != (HEIGHT, HEIGHT):
        return None, f"{texture_namespace}:{value}: texture is {size[0]}x{size[1]}, expected {HEIGHT}x{HEIGHT}"
    return f"{texture_namespace}:item/{value}.png", None


def read_existing(path: Path) -> tuple[list[str], list[tuple[str, str]]]:
    """The file's header lines and its (id, file) entries, in file order."""
    if not path.exists():
        return [], []
    lines = path.read_text(encoding="utf-8").split("\n")
    header: list[str] = []
    entries: list[tuple[str, str]] = []
    current: str | None = None
    in_images = False
    for line in lines:
        if not in_images:
            if line.strip() == "images:":
                in_images = True
            else:
                header.append(line)
            continue
        if line.startswith("  ") and line.rstrip().endswith(":") and not line.startswith("    "):
            current = line.strip()[:-1]
        elif current and line.strip().startswith("file:"):
            entries.append((current, line.strip().split("file:", 1)[1].strip()))
    while header and header[-1] == "":
        header.pop()
    return header, entries


def render(header: list[str], entries: list[tuple[str, str]], font: str) -> str:
    # CE configuration files carry no comments (see the wiki's block-behavior page): the file is plain data and
    # the conventions are documented for addon authors instead.
    out = list(header)
    out.append("images:")
    for image_id, file in entries:
        out.append(f"  {image_id}:")
        out.append(f"    file: {file}")
        out.append(f"    height: {HEIGHT}")
        out.append(f"    ascent: {ASCENT}")
        out.append(f"    font: {font}")
    return "\n".join(out) + "\n"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="report differences without writing")
    args = parser.parse_args()

    by_namespace, warnings = collect_results()

    changed = 0
    for namespace, pack in sorted(PACKS.items()):
        path = ROOT / pack / "configuration" / "meal_icons.yml"
        header, existing = read_existing(path)
        font = FONTS[namespace]
        wanted_by_id: dict[str, str] = {}
        for value, texture_namespace in by_namespace.get(namespace, []):
            file, warning = texture_for(texture_namespace, value)
            if warning:
                warnings.append(warning)
                continue
            wanted_by_id[f"{namespace}:meal_{value}"] = file
        # The file keeps its current order for entries that are still valid (so a prune or an addition is a
        # small diff), and new entries are appended in the order the recipes declare them.
        wanted: list[tuple[str, str]] = []
        for image_id, _file in existing:
            if image_id in wanted_by_id:
                wanted.append((image_id, wanted_by_id[image_id]))
        kept = {image_id for image_id, _ in wanted}
        for image_id, file in wanted_by_id.items():
            if image_id not in kept:
                wanted.append((image_id, file))
        # Entries this pack produced before but no longer does are dead config: the plugin only ever looks up
        # `<namespace>:meal_<item>` for a meal item that exists, so they can never render, and they are the
        # first thing to become wrong when a food is deleted or its texture moves.
        for image_id, _file in existing:
            if image_id not in wanted_by_id:
                warnings.append(f"{namespace}: {image_id} is no longer a cooking pot result of this pack "
                                f"(its entry will be removed)")
        rendered = render(header, wanted, font) if (wanted or path.exists()) else None
        if rendered is None:
            continue
        current = path.read_text(encoding="utf-8") if path.exists() else ""
        if current == rendered:
            print(f"{namespace}: {len(wanted)} icon(s), up to date")
            continue
        changed += 1
        if args.check:
            print(f"{namespace}: OUT OF SYNC ({len(existing)} -> {len(wanted)} icon(s))")
        else:
            path.write_text(rendered, encoding="utf-8", newline="")
            print(f"{namespace}: wrote {len(wanted)} icon(s)")

    for warning in warnings:
        print(f"warning: {warning}")
    return 1 if (args.check and changed) else 0


if __name__ == "__main__":
    sys.exit(main())
