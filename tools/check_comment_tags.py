"""Reject HTML and Javadoc inline tags in comments.

The project rule is that a comment describes current behaviour, parameters, constraints and the technical
reason for an implementation. HTML tags and Javadoc inline tags are not part of that: they render as markup in
an IDE tooltip, they break when a name moves, and `{@link #member}` reads as a bare symbol once the braces are
gone. See the repository read-me for the full wording.

The check only inspects comment lines (a block-comment body line, a `/**` opener, or a `//` line), so a Java
string or a snippet inside code is never reported.

Usage:
  python tools/check_comment_tags.py --quiet   # exit 1 when a tag is found
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

SOURCE_ROOT = ("src", "main", "java")
TEST_ROOT = ("src", "test", "java")

# `<p>`, `</p>`, `<b>`, `<i>`, `<code>`, `<br>`, `<ul>`, `<li>` and the Javadoc inline tags.
TAG_RE = re.compile(
    r"</?(?:p|b|i|code|br|ul|ol|li|pre|em|strong|table|tr|td|th)\s*/?>"
    r"|\{@(?:link|linkplain|code|literal|value|inheritDoc|param|return|throws|see|since|deprecated)\b"
)


def repo_root() -> Path:
    here = Path(__file__).resolve()
    for parent in here.parents:
        if (parent / "src" / "main" / "java").is_dir():
            return parent
    sys.exit("could not locate the FarmersDelight module root from " + str(here))


def is_comment_line(line: str) -> bool:
    stripped = line.lstrip()
    return stripped.startswith("*") or stripped.startswith("//") or stripped.startswith("/*")


def scan(path: Path, root: Path) -> list[tuple[Path, int, str]]:
    found: list[tuple[Path, int, str]] = []
    text = path.read_text(encoding="utf-8", errors="replace")
    for number, line in enumerate(text.splitlines(), start=1):
        if not is_comment_line(line):
            continue
        match = TAG_RE.search(line)
        if match:
            found.append((path.relative_to(root), number, match.group(0)))
    return found


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--quiet", action="store_true", help="print problems only")
    args = parser.parse_args()

    root = repo_root()
    paths: list[Path] = []
    for parts in (SOURCE_ROOT, TEST_ROOT):
        base = root.joinpath(*parts)
        if base.is_dir():
            paths.extend(sorted(base.rglob("*.java")))

    problems: list[tuple[Path, int, str]] = []
    for path in paths:
        problems.extend(scan(path, root))

    if problems:
        print(f"comment tags found ({len(problems)}): HTML and Javadoc inline tags are not used in comments")
        for path, number, tag in problems[:40]:
            print(f"  {path}:{number}: {tag}")
        if len(problems) > 40:
            print(f"  ... and {len(problems) - 40} more")
        return 1
    if not args.quiet:
        print(f"comment tags: clean ({len(paths)} java files)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
