#!/usr/bin/env python3
"""Check the client-side translation keys: every key the pack must resolve exists in every shipped locale.

Player-facing text resolves in two places. ``I18n`` reads ``src/main/resources/lang/{en_us,zh_cn}.yml`` on the
server, while ``Component.translatable`` and the ``<lang:...>`` tag in the shipped CraftEngine configurations
resolve on the *client* from the resource-pack language JSON under
``src/main/resources/craftengine/*/resourcepack/assets/*/lang/``. A client-bound key that the pack does not
define renders as the raw key in game, and a key missing from one locale only falls back for that language.

This tool collects the keys that reach the client:

  * ``<lang:key>`` and the quoted ``<lang:'key':'arg'>`` form in the shipped configuration YAML,
  * ``Component.translatable("key")`` string literals in the Java sources.

and compares them with every pack language file. Vanilla namespaces the server never ships (``minecraft.``,
``attribute.``, ``effect.``, ``potion.``, ``item.modifiers.``) and keys carrying a CraftEngine placeholder
(``${...}``) are skipped: the client already has the vanilla ones, and the placeholders are expanded before
the key is looked up.

    python tools/check_pack_client_keys.py [--quiet]

Exit code 1 when a client-bound key is absent from one or more shipped locale files.
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

# Shipped configurations and sources that hand a key to the client.
CONFIG_DIR = "src/main/resources"
JAVA_ROOT = "src/main/java"
PACK_GLOB = "src/main/resources/craftengine/*/resourcepack/assets/*/lang/*.json"

# The quoted form carries its arguments after the key: <lang:'gui.recipe.page':'{page}':'{total}'>.
LANG_TAG = re.compile(r"<lang:'?([^'>]+)'?(?::[^>]*)?>")
TRANSLATABLE = re.compile(r'Component\.translatable\(\s*"([^"$]+)"')

# Keys the client resolves from its own jar, or that CraftEngine expands before the lookup: not our pack.
VANILLA_PREFIXES = ("minecraft.", "attribute.", "effect.", "potion.", "item.modifiers.")


def pack_locales() -> dict[str, set[str]]:
    """locale -> keys defined by that locale's pack language file."""
    locales: dict[str, set[str]] = {}
    for path in sorted(ROOT.glob(PACK_GLOB)):
        try:
            with path.open(encoding="utf-8") as handle:
                locales.setdefault(path.stem, set()).update(json.load(handle).keys())
        except (OSError, ValueError):
            continue
    return locales


def is_ours(key: str) -> bool:
    return not key.startswith(VANILLA_PREFIXES) and "${" not in key


def config_keys() -> dict[str, list[str]]:
    """key -> config sites, from the <lang:...> tags of the shipped YAML."""
    found: dict[str, list[str]] = {}
    directory = ROOT / CONFIG_DIR
    if not directory.is_dir():
        return found
    for path in sorted(directory.rglob("*.yml")):
        if "resourcepack" in path.as_posix():
            continue
        rel = str(path.relative_to(ROOT))
        text = path.read_text(encoding="utf-8", errors="replace")
        for match in LANG_TAG.finditer(text):
            key = match.group(1)
            if not is_ours(key):
                continue
            line = text.count("\n", 0, match.start()) + 1
            found.setdefault(key, []).append(f"{rel}:{line}")
    return found


def java_keys() -> dict[str, list[str]]:
    """key -> Java sites, from the literal Component.translatable("...") call sites."""
    found: dict[str, list[str]] = {}
    directory = ROOT / JAVA_ROOT
    if not directory.is_dir():
        return found
    for path in sorted(directory.rglob("*.java")):
        rel = str(path.relative_to(ROOT))
        text = path.read_text(encoding="utf-8", errors="replace")
        for match in TRANSLATABLE.finditer(text):
            key = match.group(1)
            if not is_ours(key):
                continue
            line = text.count("\n", 0, match.start()) + 1
            found.setdefault(key, []).append(f"{rel}:{line}")
    return found


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--quiet", action="store_true", help="only print problems")
    args = parser.parse_args()

    locales = pack_locales()
    if not locales:
        print(f"no resource-pack language files under {PACK_GLOB}")
        return 1

    keys: dict[str, list[str]] = {}
    for key, sites in config_keys().items():
        keys.setdefault(key, []).extend(sites)
    for key, sites in java_keys().items():
        keys.setdefault(key, []).extend(sites)

    problems = 0
    rows: list[tuple[str, str, list[str]]] = []
    for key, sites in sorted(keys.items()):
        missing = sorted(locale for locale, defined in locales.items() if key not in defined)
        if missing:
            problems += 1
            rows.append((key, ", ".join(missing), sites))

    print(f"== FarmersDelight: client-bound keys={len(keys)} pack locales={len(locales)} "
          f"missing_in_a_locale={len(rows)}")
    for key, missing, sites in rows:
        where = sites[0] if sites else "no site recorded"
        print(f"   MISSING from {missing}: {key}  ({where})")
    if not args.quiet:
        for key, sites in sorted(keys.items()):
            print(f"   client key: {key}  ({len(sites)} site(s), first {sites[0]})")

    print(f"\nproblems: {problems}" + ("" if problems == 0 else "  <-- client-bound keys missing from a pack locale"))
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
