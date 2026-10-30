"""Check that every config.yml key an addon reads is actually defined in that addon's shipped config.yml.

Why this matters: the addon readers fall back to a compiled-in default, so a key that was renamed, or that only
ever existed in the documentation, produces a feature that silently ignores the operator's value. The main plugin
has had this check for its own config for a while; the addons read the same shape of file and had no equivalent.

Scope: only reads bound to the plugin's own config (`getConfig().getX("key")` and `ConfigSectionReader.*(section,
"key")` where the section came from `getConfig()`). A reader handed a section from another file (gui.yml, a recipe
file, a loot table) is not a reachability question against config.yml, so those calls are not judged here.

The addon repositories are separate git repositories, so they are checked only when they are checked out beside
this one; a missing checkout is reported and does not fail the run.

Usage:
    python tools/check_addon_config_keys.py            # from FarmersDelight/
    python tools/check_addon_config_keys.py --quiet
    python tools/check_addon_config_keys.py --update    # rewrite tools/addon-config-allow.txt
Exit code 0 when clean, 1 when a read key reaches no shipped definition.
"""
from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

TOOLS = Path(__file__).resolve().parent
REPO = TOOLS.parent
WORKSPACE = REPO.parent
ALLOW = TOOLS / "addon-config-allow.txt"
ADDONS = ("CrabbersDelight", "BarbequesDelight", "BrewinAndChewin", "EndsDelight", "FDAddonTemplate")

# Reads bound to the plugin's own config.yml.
READ = re.compile(r'getConfig\(\)\s*\.\s*get(?:Boolean|Int|Long|Double|String|StringList|ConfigurationSection)'
                  r'\(\s*"([^"]+)"')
# ConfigSectionReader.x(<section>, "key") is judged only when the section is the plugin config itself; a local
# YamlConfiguration loaded from another file (bottle_messages.yml, a recipe file) is not a config.yml question.
SECTION_READ = re.compile(r'ConfigSectionReader\.\w+\(\s*(?:(?:plugin\.)?getConfig\(\))\s*,\s*"([^"]+)"')

HEADER = """# Keys an addon reads but its config.yml deliberately does not define, one `addon: key` per line.
# Each entry is a documented legacy fallback; the check fails on a new miss and on a stale entry.
"""


def yaml_keys(text: str) -> set[str]:
    """Flatten config.yml into dotted paths for the document's own mappings."""
    keys: set[str] = set()
    stack: list[tuple[int, str]] = []
    for raw in text.splitlines():
        if not raw.strip() or raw.lstrip().startswith("#"):
            continue
        indent = len(raw) - len(raw.lstrip())
        match = re.match(r"([A-Za-z0-9_.-]+)\s*:", raw.strip())
        if not match:
            continue
        key = match.group(1)
        while stack and stack[-1][0] >= indent:
            stack.pop()
        prefix = ".".join(name for _, name in stack)
        keys.add(f"{prefix}.{key}" if prefix else key)
        if raw.strip().endswith(":"):
            stack.append((indent, key))
    return keys


def reachable(key: str, defined: set[str]) -> bool:
    parts = key.split(".")
    return any(".".join(parts[:i]) in defined for i in range(len(parts), 0, -1))


def scan() -> dict[str, set[str]]:
    misses: dict[str, set[str]] = {}
    for addon in ADDONS:
        base = WORKSPACE / addon
        if not base.is_dir():
            continue
        config = base / "src" / "main" / "resources" / "config.yml"
        defined = yaml_keys(config.read_text(encoding="utf-8")) if config.is_file() else set()
        for path in (base / "src/main/java").rglob("*.java"):
            text = path.read_text(encoding="utf-8", errors="replace")
            for pattern in (READ, SECTION_READ):
                for match in pattern.finditer(text):
                    key = match.group(1)
                    if not reachable(key, defined):
                        misses.setdefault(addon, set()).add(key)
    return misses


def parse_allow(text: str) -> set[tuple[str, str]]:
    allowed: set[tuple[str, str]] = set()
    for line in text.splitlines():
        line = line.strip()
        if not line or line.startswith("#") or ":" not in line:
            continue
        addon, _, key = line.partition(":")
        allowed.add((addon.strip(), key.strip()))
    return allowed


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--quiet", action="store_true")
    parser.add_argument("--update", action="store_true", help="rewrite the allow list from the current state")
    args = parser.parse_args()

    misses = scan()
    found = {(addon, key) for addon, keys in misses.items() for key in keys}
    present = [addon for addon in ADDONS if (WORKSPACE / addon).is_dir()]

    if args.update:
        lines = [HEADER.rstrip("\n")] + [f"{addon}: {key}" for addon, key in sorted(found)]
        ALLOW.write_text("\n".join(lines) + "\n", encoding="utf-8", newline="\n")
        if not args.quiet:
            print(f"addon config keys: wrote {len(found)} allowance(s) to {ALLOW.name}")
        return 0

    if not present:
        if not args.quiet:
            print("no addon checkout beside this repository; nothing to check")
        return 0

    allowed = parse_allow(ALLOW.read_text(encoding="utf-8")) if ALLOW.is_file() else set()
    problems = []
    for addon, key in sorted(found - allowed):
        problems.append(f"{addon}: reads '{key}' which its config.yml does not define")
    for addon, key in sorted(allowed - found):
        problems.append(f"{addon}: allowance '{key}' no longer matches a read")
    if problems:
        for line in problems:
            print(line)
        print(f"problems: {len(problems)}")
        return 1
    if not args.quiet:
        print(f"addon config keys: {len(present)} addon(s) checked, {len(allowed)} documented allowance(s)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
