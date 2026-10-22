"""Enforce the failure-handling convention: an empty catch must say why it is empty.

FarmersDelight deliberately swallows a lot of optional-integration failures (reflection against an absent
plugin, a scheduler that refuses work during shutdown, a display entity whose world is gone). Those are fine —
but only when the reason is written down. A bare `catch (RuntimeException e) {}` hides a bug, and the next
change that starts throwing in that block will fail invisibly.

Rule: the body of every `catch` block, with comments stripped, must not be empty *unless* the block contains a
comment. A comment is the opt-out: it is read by the next person debugging the path, which is the point.

The pre-existing empty catches are frozen in `tools/error-handling-baseline.txt` so this check can land without a
34-file comment sweep: the baseline is a per-(file, catch signature) count, and only a **new** empty catch fails
the gate. Removing one lets `--update` tighten the baseline; adding one fails until either a comment is written
or the baseline is refreshed with a reviewed reason.

Usage:
    python tools/check_error_handling.py            # new empty catches only
    python tools/check_error_handling.py --update   # refresh the frozen baseline
"""

from __future__ import annotations

import argparse
import re
import sys
from collections import Counter
from pathlib import Path

BASELINE = "error-handling-baseline.txt"
CATCH_RE = re.compile(r"catch\s*\(([^)]*)\)\s*\{")
SKIP_PARTS = ("\\build\\", "/build/", "\\.gradle\\", "/.gradle/")


def repo_root() -> Path:
    here = Path(__file__).resolve()
    for parent in here.parents:
        if (parent / "src" / "main" / "java").is_dir():
            return parent
    sys.exit("could not locate the FarmersDelight module root from " + str(here))


def java_files(root: Path):
    for path in sorted((root / "src").rglob("*.java")):
        text = str(path)
        if any(part in text for part in SKIP_PARTS):
            continue
        yield path


def block_body(text: str, brace: int) -> str:
    depth = 0
    i = brace
    while i < len(text):
        char = text[i]
        if char == "{":
            depth += 1
        elif char == "}":
            depth -= 1
            if depth == 0:
                return text[brace + 1:i]
        i += 1
    return text[brace + 1:]


def strip_comments(body: str) -> tuple[str, bool]:
    has_comment = bool(re.search(r"//|/\*", body))
    clean = re.sub(r"/\*.*?\*/", " ", body, flags=re.S)
    clean = re.sub(r"//[^\n]*", " ", clean)
    return clean, has_comment


def violations(root: Path) -> list[tuple[str, str]]:
    """(relative path, normalized catch header) for every empty catch without a comment."""
    found: list[tuple[str, str]] = []
    for path in java_files(root):
        text = path.read_text(encoding="utf-8", errors="ignore")
        relative = str(path.relative_to(root)).replace("\\", "/")
        for match in CATCH_RE.finditer(text):
            body = block_body(text, match.end() - 1)
            clean, has_comment = strip_comments(body)
            if not clean.strip() and not has_comment:
                header = "catch (" + " ".join(match.group(1).split()) + ")"
                found.append((relative, header))
    return found


def load_baseline(path: Path) -> Counter:
    if not path.is_file():
        return Counter()
    counts: Counter = Counter()
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.rstrip("\n")
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        parts = line.split("\t")
        if len(parts) != 3:
            continue
        try:
            counts[(parts[0], parts[1])] = int(parts[2])
        except ValueError:
            continue
    return counts


def save_baseline(path: Path, counts: Counter) -> None:
    lines = ["# Frozen empty catches: file\tcatch header\tcount. Comment a new empty catch or refresh with --update."]
    for (file, header), count in sorted(counts.items()):
        lines.append(f"{file}\t{header}\t{count}")
    path.write_text("\n".join(lines) + "\n", encoding="utf-8", newline="\n")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--quiet", action="store_true", help="print problems only")
    parser.add_argument("--update", action="store_true", help="rewrite the frozen baseline")
    args = parser.parse_args()

    root = repo_root()
    baseline_path = root / "tools" / BASELINE
    current = Counter(violations(root))

    if args.update:
        save_baseline(baseline_path, current)
        print(f"error handling: froze {sum(current.values())} empty catches in {baseline_path}")
        return 0
    if not baseline_path.is_file():
        print(f"missing baseline {baseline_path}; run: python tools/check_error_handling.py --update")
        return 1

    baseline = load_baseline(baseline_path)
    new_problems: list[str] = []
    for (file, header), count in current.items():
        allowed = baseline.get((file, header), 0)
        if count > allowed:
            new_problems.append(f"{file}: new empty catch without a comment: {header} (+{count - allowed})")
    tightened = [key for key, allowed in baseline.items() if current.get(key, 0) < allowed]

    if not args.quiet:
        print(f"failure handling check @ {root}")
        print("  rule: an empty catch block needs a comment saying why it is empty")
        print(f"  baseline: {sum(baseline.values())} frozen, current: {sum(current.values())}")

    if new_problems:
        print(f"\nnew empty catches without a reason ({len(new_problems)}):")
        for problem in new_problems[:40]:
            print("  " + problem)
    if tightened and not args.quiet:
        print(f"\n{len(tightened)} baseline entries are now stricter than needed; refresh with --update after review")
    print(f"\nproblems: {len(new_problems)}")
    return 1 if new_problems else 0


if __name__ == "__main__":
    raise SystemExit(main())
