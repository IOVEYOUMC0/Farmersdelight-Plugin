"""Check shipped YAML files for duplicate mapping keys.

Why this matters: a duplicated block is not a YAML error. Every constructor-based reader — including
`yaml.safe_load`, which the other checks in this directory use — keeps the LAST value of a repeated key and
reports nothing, so the duplicate silently overrides the first block. The 1.4 migration shipped exactly that:
`farmersdelight:cocoa_beans_bag` carried two `states:` blocks, and the second one both hid the first and stole
`rice_bag`'s block. `yaml.safe_load` saw a clean document, and CraftEngine's own duplicate-tolerant loader
would have loaded only the second block.

So this check walks the *node* tree from `yaml.compose`, where a mapping is still a list of key/value pairs and
a repeated key is visible, instead of loading the document. It never writes to a file.

Scope: `<module>/src/main/resources/**/*.yml` and `*.yaml` — the CraftEngine packs, `lang/*.yml`,
`paper-plugin.yml`, `config.yml`, `common-tags.yml`, ... The standalone packs under the monorepo's `packs/`
are not in this repository; a workspace-level copy of this script scans them by listing `packs` in SCAN_ROOTS.

Non-strict YAML is skipped, not guessed at: a scanner/parser/composer error, a second document, or a file that
is not UTF-8 is reported as `skipped: <path>: <reason>` and does not by itself fail the check, so a custom macro
or a tab-indented file cannot produce a false "duplicate key". Those lines are printed in both modes — a skipped
file is reduced coverage and has to stay visible even under --quiet. A duplicate key does fail the check (exit 1).

Usage:
    python tools/check_duplicate_yaml_keys.py            # from FarmersDelight/
    python tools/check_duplicate_yaml_keys.py --quiet    # summary (+ any skipped files) only
Exit code 0 when no duplicate key was found, 1 otherwise.
"""
from __future__ import annotations

import argparse
import sys
from pathlib import Path

try:
    import yaml
except ImportError:
    sys.exit("pyyaml is required: python -m pip install pyyaml")

ROOT = Path(__file__).resolve().parent.parent

# Directories scanned relative to the repository root. Kept as a list so a workspace-level copy can add the
# monorepo's standalone `packs` directory without touching the walking code.
SCAN_ROOTS = ["src/main/resources"]
SUFFIXES = (".yml", ".yaml")
SKIP_PARTS = ("\\build\\", "/build/", "\\Reference\\", "/Reference/")


def yaml_files() -> list[Path]:
    files: list[Path] = []
    for rel in SCAN_ROOTS:
        base = ROOT / rel
        if not base.is_dir():
            continue
        for path in sorted(base.rglob("*")):
            text = str(path)
            if path.suffix.lower() not in SUFFIXES or not path.is_file():
                continue
            if any(part in text for part in SKIP_PARTS):
                continue
            files.append(path)
    return files


def compose_file(path: Path) -> tuple[yaml.Node | None, str | None]:
    """Compose one file into a node tree.

    Returns (node, None) for a single-document, strictly parseable file, or (None, reason) when the file has to
    be skipped. The document is composed and never constructed, so an unknown tag or a `!` macro cannot turn
    into an error or into a false positive here.
    """
    try:
        text = path.read_text(encoding="utf-8")
    except (OSError, UnicodeDecodeError) as error:
        return None, f"not readable as UTF-8 ({error.__class__.__name__})"
    try:
        return yaml.compose(text, Loader=yaml.SafeLoader), None
    except yaml.YAMLError as error:
        message = str(error).strip()
        detail = message.splitlines()[0] if message else error.__class__.__name__
        return None, f"not single-document strict YAML ({error.__class__.__name__}: {detail})"


def key_identity(key_node: yaml.Node) -> str | None:
    """The comparison identity of one mapping key, or None when it cannot be compared safely.

    Scalar keys compare on (tag, value), so `1` and `'1'` — two different YAML keys — are not merged into one
    report. A non-scalar key (an explicit `? [a, b]` key) is not compared at all. Both choices can only
    *suppress* a report, never invent one, which is the right bias for a check that gates CI.
    """
    if isinstance(key_node, yaml.ScalarNode):
        return f"{key_node.tag}\x00{key_node.value}"
    return None


def key_label(key_node: yaml.Node) -> str:
    return key_node.value if isinstance(key_node, yaml.ScalarNode) else "<complex key>"


def find_duplicates(node: yaml.Node | None, visited: set[int], found: list[tuple[str, int, int]],
                    counts: dict[str, int]) -> None:
    """Walk one node subtree, appending (key, duplicate line, first line) for every repeated key.

    `visited` holds node ids: `yaml.compose` hands back the same object for an alias and its anchor, and a
    recursive anchor (`&a [*a]`) would otherwise recurse forever. Reporting a shared subtree once is also the
    correct answer — the duplicate is written in the file once.
    """
    if node is None or id(node) in visited:
        return
    visited.add(id(node))

    if isinstance(node, yaml.MappingNode):
        counts["mappings"] += 1
        first_line: dict[str, int] = {}
        for key_node, value_node in node.value:
            counts["pairs"] += 1
            identity = key_identity(key_node)
            line = key_node.start_mark.line + 1
            if identity is not None:
                if identity in first_line:
                    found.append((key_label(key_node), line, first_line[identity]))
                else:
                    first_line[identity] = line
            find_duplicates(value_node, visited, found, counts)
    elif isinstance(node, yaml.SequenceNode):
        for item in node.value:
            find_duplicates(item, visited, found, counts)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--quiet", action="store_true", help="only print the summary and any skipped files")
    args = parser.parse_args()

    files = yaml_files()
    skipped: list[tuple[Path, str]] = []
    duplicates: list[tuple[Path, str, int, int]] = []
    counts = {"mappings": 0, "pairs": 0}

    for path in files:
        node, reason = compose_file(path)
        if reason is not None:
            skipped.append((path, reason))
            continue
        found: list[tuple[str, int, int]] = []
        find_duplicates(node, set(), found, counts)
        for key, line, first in found:
            duplicates.append((path, key, line, first))

    if not args.quiet:
        print(f"duplicate YAML key check @ {ROOT}")
        print(f"  scanned: {len(files)} file(s) under {', '.join(SCAN_ROOTS)} (skipped: {len(skipped)})")
        print(f"  mappings: {counts['mappings']}, key/value pairs: {counts['pairs']}")

    for path, reason in skipped:
        print(f"skipped: {path.relative_to(ROOT).as_posix()}: {reason}")

    if duplicates:
        print(f"\nduplicate keys ({len(duplicates)}):")
        for path, key, line, first in duplicates:
            print(f"  {path.relative_to(ROOT).as_posix()}:{line}: duplicate key '{key}'"
                  f" (first defined at line {first})")

    print(f"\nproblems: {len(duplicates)}")
    return 1 if duplicates else 0


if __name__ == "__main__":
    raise SystemExit(main())
