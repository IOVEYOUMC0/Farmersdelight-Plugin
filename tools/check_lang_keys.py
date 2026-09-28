#!/usr/bin/env python3
"""Check the plugin language files: referenced keys exist, both locales stay symmetric, and unused keys surface.

User-visible text resolves through ``src/main/resources/lang/{en_us,zh_cn}.yml`` (the plugin's ``I18n``
wrapper). A key that is read but never defined renders as the raw key in game; a key that exists in one locale
but not the other silently falls back for one client language. This tool walks the Java sources for the literal
keys each helper is called with and compares them with the bundled files.

What it reports:
  * MISSING   - a literal key that no bundled locale defines,
  * ASYMMETRY - a key defined in only one locale,
  * EMPTY     - a defined key whose value is blank,
  * dynamic   - call sites whose key is composed at runtime (shown so they can be checked by hand),
  * unused    - keys nothing references statically (informational; some are reached dynamically).

Repo-scoped: this copy checks this repository's own language files. The workspace-level copy in the monorepo
parent directory additionally checks the addons' language files in one pass; the addon namespaces it scans from
this repository's sources (``*Lang.get("...")`` call sites) are listed in ADDON_CALL_SITES below.

    python tools/check_lang_keys.py [--quiet]

Exit code 1 when a literal key is missing, locales are asymmetric or a value is blank.
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parent.parent

# Java source roots that may reference a language key, and the language directory they resolve against.
JAVA_ROOTS = ["src/main/java", "src/debugTools/java"]
LANG_DIR = "src/main/resources/lang"

# Helper calls whose literal argument is a language key, with the lookup mode the helper applies:
#   "plain"   - the key is looked up as written,
#   "console" - the helper prefixes "console." unless the caller already wrote it (I18n.formatConsole and the
#               log* wrappers built on it, plus the shared FarmersDelightApi.consoleMessage),
#   "shared"  - the key resolves against FarmersDelight's own files on behalf of another plugin.
STATIC_PATTERNS: list[tuple[re.Pattern[str], str]] = [
    # Console helpers
    (re.compile(r'\bI18n\.(?:logInfo|logWarning|logSevere|formatConsole)\(\s*"([^"]+)"'), "console"),
    (re.compile(r'\bI18n\.logDetail\(\s*"[^"]+",\s*"([^"]+)"'), "console"),
    (re.compile(r'\bconsoleMessage\(\s*"([^"]+)"'), "shared"),
    # Player / GUI helpers
    (re.compile(r'\bI18n\.(?:get|getComponent|format|formatNamed|formatNamedArgs|serverText|translatable|'
                r'translatableWithFallback)\(\s*"([^"]+)"'), "plain"),
    (re.compile(r'\bwarnConfig\(\s*"([^"]+)"'), "plain"),
    # Language wrappers that take a literal key.
    (re.compile(r'\b[A-Z]\w*Lang\.(?:get|component|format\w*)\(\s*"([^"]+)"'), "plain"),
]

# Same helpers, but with a key that is not a string literal.
DYNAMIC_PATTERNS = [
    re.compile(r'\b(?:I18n|consoleMessage|warnConfig|[A-Z]\w*Lang|language)\.\w+\(\s*([A-Za-z_]\w*)\s*[,)]'),
    re.compile(r'\b(?:I18n|[A-Z]\w*Lang)\.\w+\(\s*"([^"]*)"\s*\+'),
]

# Keys are also stored in fields/methods and handed to a helper later (SubCommand#helpKey, step keys, debug
# labels). Any dotted lowercase literal is a candidate, so it is checked too and reported when neither it nor
# its "console." form is defined — a few of these are config paths, which the report marks for a quick look.
DOTTED_LITERAL = re.compile(r'"([a-z][a-z0-9_]*(?:\.[a-z0-9_]+)+)"')


def flatten(node, prefix: str = "") -> dict[str, object]:
    out: dict[str, object] = {}
    if isinstance(node, dict):
        for key, value in node.items():
            joined = f"{prefix}.{key}" if prefix else str(key)
            out.update(flatten(value, joined))
    else:
        out[prefix] = node
    return out


def load_lang(directory: Path, locale: str) -> dict[str, object]:
    path = directory / f"{locale}.yml"
    if not path.is_file():
        return {}
    with path.open(encoding="utf-8") as handle:
        return flatten(yaml.safe_load(handle) or {})


def java_files(roots: list[str]) -> list[Path]:
    files: list[Path] = []
    for root in roots:
        base = ROOT / root
        if base.is_dir():
            files.extend(sorted(base.rglob("*.java")))
    return files


def scan() -> tuple[dict[str, str], dict[str, str], list[tuple[str, str, int]]]:
    """Return (literal key -> first file, keys resolved on behalf of addons, dynamic sites)."""
    static: dict[str, str] = {}
    shared: dict[str, str] = {}
    dynamic: list[tuple[str, str, int]] = []
    for path in java_files(JAVA_ROOTS):
        rel = str(path.relative_to(ROOT))
        text = path.read_text(encoding="utf-8", errors="replace")
        for pattern, mode in STATIC_PATTERNS:
            for match in pattern.finditer(text):
                key = match.group(1)
                # "<literal>" + something: the literal is only a prefix, the real key is composed later.
                if text[match.end():match.end() + 3].lstrip().startswith("+"):
                    continue
                target = static
                if mode == "shared":
                    # FarmersDelightApi.consoleMessage resolves against FarmersDelight's own language files.
                    target = shared
                    if not key.startswith("console."):
                        key = f"console.{key}"
                elif mode == "console" and not key.startswith("console."):
                    key = f"console.{key}"
                target.setdefault(key, rel)
        for pattern in DYNAMIC_PATTERNS:
            for match in pattern.finditer(text):
                line = text.count("\n", 0, match.start()) + 1
                dynamic.append((rel, match.group(1), line))
    return static, shared, dynamic


def pack_translation_keys() -> set[str]:
    """Keys shipped in the resource-pack lang JSONs (Component.translatable / <lang:> keys)."""
    keys: set[str] = set()
    for pack_lang in (ROOT / "src/main/resources/craftengine").glob(
            "*/resourcepack/assets/*/lang/*.json"):
        try:
            with pack_lang.open(encoding="utf-8") as handle:
                keys.update(json.load(handle).keys())
        except (OSError, ValueError):
            continue
    return keys


def literal_candidates(en: dict[str, object], zh: dict[str, object]) -> dict[str, str]:
    """Dotted lowercase literals that look like keys but have no language entry (key or console. form)."""
    defined = set(en) | set(zh) | pack_translation_keys()
    candidates: dict[str, str] = {}
    for path in java_files(JAVA_ROOTS):
        rel = str(path.relative_to(ROOT))
        text = path.read_text(encoding="utf-8", errors="replace")
        for match in DOTTED_LITERAL.finditer(text):
            key = match.group(1)
            if {key, f"console.{key}"} & defined:
                continue
            if key.startswith(("minecraft.", "craftengine.")) or key.endswith((".yml", ".json", ".png")):
                continue
            candidates.setdefault(key, rel)
    return candidates


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--quiet", action="store_true", help="only print problems")
    args = parser.parse_args()

    directory = ROOT / LANG_DIR
    en = load_lang(directory, "en_us")
    zh = load_lang(directory, "zh_cn")
    if not en and not zh:
        print(f"no language files under {LANG_DIR}")
        return 1

    static, shared, dynamic = scan()

    problems = 0
    missing = [(key, source) for key, source in sorted(static.items())
               if key not in en or key not in zh]
    only_en = sorted(set(en) - set(zh))
    only_zh = sorted(set(zh) - set(en))
    blank = sorted(key for key in set(en) & set(zh)
                   if not str(en[key] or "").strip() or not str(zh[key] or "").strip())
    # A builders' keys are added programmatically (see *Language), so only report real candidates.
    unused = sorted(key for key in set(en) & set(zh)
                    if key not in static and not key.endswith(".i18n")
                    and ".i18n." not in key)
    problems += len(missing) + len(only_en) + len(only_zh) + len(blank)

    print(f"== FarmersDelight: referenced={len(static)} defined en={len(en)} zh={len(zh)} "
          f"missing={len(missing)} only_en={len(only_en)} only_zh={len(only_zh)} "
          f"blank={len(blank)} dynamic={len(dynamic)}")
    for key, source in missing:
        which = "en+zh" if key not in en and key not in zh else ("en_us" if key not in en else "zh_cn")
        print(f"   MISSING in {which}: {key}  ({source})")
    for key in only_en:
        print(f"   ASYMMETRY: {key} exists only in en_us")
    for key in only_zh:
        print(f"   ASYMMETRY: {key} exists only in zh_cn")
    for key in blank:
        print(f"   EMPTY: {key}")
    if not args.quiet:
        for key in unused[:40]:
            print(f"   unused (check by hand): {key}")
        if len(unused) > 40:
            print(f"   ... and {len(unused) - 40} more unreferenced key(s); run --quiet for problems only")
        for rel, token, line in dynamic:
            print(f"   dynamic key site: {rel}:{line} -> {token}")
        for key, source in sorted(literal_candidates(en, zh).items()):
            print(f"   literal with no language entry (config path or missing key?): {key}  ({source})")

    # Keys handed to the shared consoleMessage helper on behalf of an addon live in this plugin's own files.
    if shared:
        missing_shared = [(key, source) for key, source in sorted(shared.items())
                          if key not in en or key not in zh]
        problems += len(missing_shared)
        print(f"== shared console keys (resolved here on behalf of addons): referenced={len(shared)} "
              f"missing={len(missing_shared)}")
        for key, source in missing_shared:
            which = "en+zh" if key not in en and key not in zh else ("en_us" if key not in en else "zh_cn")
            print(f"   MISSING in {which}: {key}  ({source})")

    print(f"\nproblems: {problems}" + ("" if problems == 0 else "  <-- referenced keys missing, asymmetric or blank"))
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
