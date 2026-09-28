"""Strip whole-line comments from the CraftEngine pack configuration files.

The wiki repository states the rule (server-guide/*/block-behaviors.md: "The shipped CE files contain no comments,
so use this page as the field reference when you edit them") and carries
the field references instead. This removes the explanatory comments without touching values: only lines whose
first non-space character is '#' are dropped, and the script refuses to touch a comment that sits inside a YAML
block scalar (a value introduced by '|' or '>'), which would be data rather than a comment.

Repo-scoped: it walks the packs this repository ships (its own namespace and the standalone packs under packs/).
The workspace-level copy in the monorepo parent directory checks every module's pack in one pass; this one exists
so CI can enforce the rule on the files a change to this repository can actually affect.

    python tools/strip_ce_comments.py [--check]
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

PACK_ROOTS = [
    "src/main/resources/craftengine",
    "packs",
]


def config_files() -> list[Path]:
    files: list[Path] = []
    for rel in PACK_ROOTS:
        base = ROOT / rel
        if not base.is_dir():
            continue
        for path in sorted(base.rglob("*.yml")):
            if path.parent.name == "configuration":
                files.append(path)
    return files


def strip(path: Path) -> tuple[str, int, list[str]]:
    """Return (new text, removed count, block-scalar warnings) for one file."""
    text = path.read_text(encoding="utf-8")
    eol = "\r\n" if "\r\n" in text else "\n"
    lines = text.split(eol)
    out: list[str] = []
    removed = 0
    warnings: list[str] = []
    block_indent: int | None = None
    for line in lines:
        stripped = line.lstrip(" ")
        indent = len(line) - len(stripped)
        if block_indent is not None:
            if stripped and indent <= block_indent:
                block_indent = None
            elif stripped.startswith("#"):
                warnings.append(f"{path}: comment inside a block scalar: {line.strip()}")
        if block_indent is None and (stripped.endswith("|") or stripped.endswith(">")):
            block_indent = indent
        if stripped.startswith("#") and block_indent is None:
            removed += 1
            continue
        out.append(line)
    return eol.join(out), removed, warnings


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="report files that still contain comments")
    args = parser.parse_args()

    total = 0
    changed = 0
    warnings: list[str] = []
    for path in config_files():
        new, removed, file_warnings = strip(path)
        warnings.extend(file_warnings)
        if removed == 0:
            continue
        total += removed
        changed += 1
        rel = path.relative_to(ROOT).as_posix()
        if args.check:
            print(f"{rel}: {removed} comment line(s)")
        else:
            path.write_text(new, encoding="utf-8", newline="")
            print(f"{rel}: removed {removed} comment line(s)")
    for warning in warnings:
        print(f"warning: {warning}")
    print(f"{'would change' if args.check else 'changed'} {changed} file(s), {total} comment line(s)")
    return 1 if (args.check and total) else 0


if __name__ == "__main__":
    sys.exit(main())
