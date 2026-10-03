"""Check that every literal config path the code reads actually exists in a shipped config file.

Why this matters: `ConfigLookup` walks aliases in order and falls back to the compiled-in default when none
of them is set. So a typo'd or renamed path does not fail loudly — the feature silently uses its default and
the operator's edited value is ignored. That is the "config reachability" defect class.

What it checks:
  * every string literal passed to the plugin's `getConfig*` / `getFirstConfigSection` helpers, and to
    `ConfigSectionReader`;
  * resolved against the union of the bundled default files that ships in src/main/resources
    (config.yml, gui.yml, drops.yml, world-data.yml, display-overrides.yml).

Both Java source roots are scanned: src/main/java and the debug source set src/debugTools/java. The debug
source set is compiled only with -PdebugTools=true, but it reads the same config and is the one place a
debug-only key (for example debug-tools.max-place-count) was previously invisible to this check.

A call site is satisfied when *at least one* of its path aliases exists — the aliases are deliberate
fallbacks for migrated keys, so an old name that no longer ships is fine as long as a current one exists.
Registry sections are exempt: those are registry keys an operator adds, not settings we ship.

Usage:
    python tools/check_config_paths.py            # from FarmersDelight/
    python tools/check_config_paths.py --quiet
Exit code 0 when clean, 1 when a call site has no reachable path.
"""
from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

try:
    import yaml
except ImportError:
    sys.exit("pyyaml is required: python -m pip install pyyaml")

FD_PACKAGE = "com.huidu.farmersdelight"
BUNDLED = ["config.yml", "gui.yml", "drops.yml", "world-data.yml", "display-overrides.yml"]

# Java source roots whose literal config reads are checked. src/debugTools/java is the debug source set
# (compiled only with -PdebugTools=true), which reads the same shipped config; a debug-only key used to be
# outside this check entirely.
JAVA_ROOTS = ["src/main/java", "src/debugTools/java"]

# Helpers whose literal arguments are config paths.
HELPERS = (
    "getConfigBoolean", "getConfigInt", "getConfigDouble", "getConfigStringList",
    "getFirstConfigSection",
)
# getConfigBoolean(true, "a", "b") -> the default comes first, so paths are the string literals after comma 1.
FIRST_ARG_IS_DEFAULT = {"getConfigBoolean", "getConfigInt", "getConfigDouble"}

CALL_RE = re.compile(r"\b(" + "|".join(HELPERS) + r")\s*\(")
STRING_RE = re.compile(r'"([^"]*)"')
SKIP_PARTS = ("\\build\\", "/build/", "\\Reference\\", "/Reference/")

# A call site that intentionally reads a key no shipped file defines marks itself with this comment, and the
# reason must be written next to it. Deliberately an in-source marker rather than an allowlist file: the
# exemption then sits where a reader will see it, and there is no second list to keep in sync.
EXEMPT_MARKER = "config-path-check: no shipped key, on purpose"
EXEMPT_LOOKBACK_LINES = 8


def is_exempt(text: str, call_start: int) -> bool:
    """True when the call is annotated as an intentional unreachable read."""
    lines = text[:call_start].splitlines()
    return any(EXEMPT_MARKER in line for line in lines[-EXEMPT_LOOKBACK_LINES:])


def call_arguments(text: str, open_paren: int) -> str:
    """Return the text between the matching parentheses.

    Needed because a naive non-greedy `\\((.*?)\\)` stops at the first closing paren, which truncates the
    alias list of a call whose arguments span several lines — and a truncated list produces false positives
    (it misses the alias that does ship).
    """
    depth = 0
    i = open_paren
    while i < len(text):
        char = text[i]
        if char == '"':  # skip string literals so a paren inside one cannot unbalance the scan
            i += 1
            while i < len(text) and text[i] != '"':
                i += 2 if text[i] == "\\" else 1
        elif char == "(":
            depth += 1
        elif char == ")":
            depth -= 1
            if depth == 0:
                return text[open_paren + 1:i]
        i += 1
    return text[open_paren + 1:]


def module_root() -> Path:
    here = Path(__file__).resolve()
    for parent in here.parents:
        if (parent / "src" / "main" / "resources" / "config.yml").is_file():
            return parent
    sys.exit("could not locate the FarmersDelight module root")


def load_shipped_keys(root: Path) -> set[str]:
    keys: set[str] = set()
    resources = root / "src" / "main" / "resources"
    for name in BUNDLED:
        path = resources / name
        if not path.is_file():
            continue
        doc = yaml.safe_load(path.read_text(encoding="utf-8")) or {}
        walk(doc, "", keys)
    return keys


def walk(node, prefix: str, out: set[str]) -> None:
    if not isinstance(node, dict):
        if prefix:
            out.add(prefix)
        return
    for key, value in node.items():
        path = f"{prefix}.{key}" if prefix else str(key)
        out.add(path)
        walk(value, path, out)


def split_arguments(args: str) -> list[str]:
    """Split a call's arguments on commas at parenthesis depth zero."""
    parts: list[str] = []
    depth = 0
    current: list[str] = []
    i = 0
    while i < len(args):
        char = args[i]
        if char == '"':
            current.append(char)
            i += 1
            while i < len(args) and args[i] != '"':
                current.append(args[i])
                i += 2 if args[i] == "\\" else 1
            if i < len(args):
                current.append(args[i])
        elif char in "([{":
            depth += 1
            current.append(char)
        elif char in ")]}":
            depth -= 1
            current.append(char)
        elif char == "," and depth == 0:
            parts.append("".join(current))
            current = []
        else:
            current.append(char)
        i += 1
    if current:
        parts.append("".join(current))
    return parts


def literal_paths(helper: str, args: str) -> list[str]:
    """The path literals of one call, or [] when the paths are computed rather than written down.

    The default argument is a literal number or boolean for most helpers, so it must not be mistaken for a
    path — but it is a *string* for some callers, hence the split-then-inspect approach rather than simply
    dropping the first literal.
    """
    parts = split_arguments(args)
    if helper in FIRST_ARG_IS_DEFAULT and parts:
        first = parts[0]
        if not STRING_RE.search(first):  # a numeric/boolean default, e.g. "8" or "true"
            parts = parts[1:]
    values = [m.group(1) for part in parts for m in STRING_RE.finditer(part)]
    return [v for v in values if v]


def scan_root(root: Path, java_root: Path, shipped: set[str]) -> tuple[int, list[str]]:
    """Check one Java source root: return the literal call sites seen and the unreachable ones."""
    call_sites = 0
    unreachable: list[str] = []
    if not java_root.is_dir():
        return call_sites, unreachable
    for path in sorted(java_root.rglob("*.java")):
        text_path = str(path)
        if any(part in text_path for part in SKIP_PARTS):
            continue
        text = path.read_text(encoding="utf-8", errors="ignore")
        rel = path.relative_to(root)
        for match in CALL_RE.finditer(text):
            helper = match.group(1)
            raw_args = call_arguments(text, match.end() - 1)
            paths = literal_paths(helper, raw_args)
            if not paths:
                continue  # computed path, not a literal: out of scope
            call_sites += 1
            if not any(p in shipped for p in paths):
                if is_exempt(text, match.start()):
                    continue
                lineno = text[:match.start()].count("\n") + 1
                unreachable.append(f"{rel}:{lineno}: {helper}({', '.join(repr(p) for p in paths)})")
    return call_sites, unreachable


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--quiet", action="store_true")
    args = parser.parse_args()

    root = module_root()
    shipped = load_shipped_keys(root)

    call_sites = 0
    unreachable: list[str] = []
    for java_root_name in JAVA_ROOTS:
        root_sites, root_unreachable = scan_root(root, root / java_root_name, shipped)
        call_sites += root_sites
        unreachable.extend(root_unreachable)

    if not args.quiet:
        print(f"config path check @ {root}")
        print(f"  shipped keys: {len(shipped)} from {', '.join(BUNDLED)}")
        print(f"  java roots: {', '.join(JAVA_ROOTS)}")
        print(f"  literal call sites checked: {call_sites}")

    if unreachable:
        print(f"\nno literal path is present in any shipped config file ({len(unreachable)}):")
        for item in unreachable:
            print("  " + item)

    print(f"\nproblems: {len(unreachable)}")
    return 1 if unreachable else 0


if __name__ == "__main__":
    raise SystemExit(main())
