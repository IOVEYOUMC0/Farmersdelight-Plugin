"""Check that every Java file holds the type its path promises.

Why this matters: a whole-file write that puts the wrong content into a file is silent to every other check.
Twice in this repository a file ended up holding another file's source — `CarrierRestorer` written into
`visual/ProxyItemDisplayManager.java`, and `FarmersDelightPlugin` into `visual/RealDisplayCuller.java` — and both
times only a compile (or a single-file javac) caught it. This check reads only the first few lines a compiler
would: the `package` declaration and the top-level type declarations.

Rules, per file, against every `<module>/src/*/java` source set that exists:
  1. the directory must be the package directory: `<root>/a/b/C.java` must declare `package a.b;`;
  2. the file name must belong to a top-level type in it: a `public` top-level type must be named exactly like
     the file, and a file whose types are all package-private must still name its (first) type after the file.
     A file with several top-level types only has to have one of them match, which is what the Java language
     requires of the public one anyway;
  3. the same `public` fully-qualified type name must not be declared by two files.

A type declared inside another type (nested, inner, anonymous, local) is not a top-level type and is ignored.
Comments, strings and text blocks are blanked before scanning, so the word `class` inside a comment or a
template string cannot be mistaken for a declaration. Files without any top-level type (package-info.java) are
skipped.

Usage:
    python tools/check_java_file_identity.py            # from FarmersDelight/
    python tools/check_java_file_identity.py --quiet    # summary only
Exit code 0 when every file matches its path, 1 otherwise. Never writes to a file.
"""
from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

MODIFIERS = ("public", "protected", "private", "static", "final", "abstract", "sealed", "non-sealed", "strictfp")
TYPE_RE = re.compile(
    r"(?<![\w.@])(?P<mods>(?:(?:" + "|".join(MODIFIERS) + r")\s+)*)"
    r"(?P<kind>@interface|class|interface|enum|record)\s+(?P<name>\w+)")
PACKAGE_RE = re.compile(r"(?m)^[ \t]*package\s+([\w.]+)\s*;")


def source_roots() -> list[Path]:
    """Every `<module>/src/<sourceSet>/java` directory that exists."""
    src = ROOT / "src"
    if not src.is_dir():
        return []
    return sorted(path for path in src.glob("*/java") if path.is_dir())


def blank_comments_and_literals(text: str) -> str:
    """Replace comments, string/char literals and text blocks with spaces, keeping every newline.

    Line numbers stay identical, and the scanner cannot see a `class` keyword that is really comment or string
    content. Text blocks (triple quotes) are handled because the test sources embed JSON/YAML in them.
    """
    out = list(text)
    i = 0
    n = len(text)
    while i < n:
        char = text[i]
        if char == "/" and i + 1 < n and text[i + 1] == "/":
            while i < n and text[i] != "\n":
                out[i] = " "
                i += 1
        elif char == "/" and i + 1 < n and text[i + 1] == "*":
            while i < n and not (text[i] == "*" and i + 1 < n and text[i + 1] == "/"):
                if text[i] != "\n":
                    out[i] = " "
                i += 1
            for j in range(i, min(i + 2, n)):
                out[j] = " "
            i += 2
        elif text.startswith('"""', i):
            for j in range(i, min(i + 3, n)):
                out[j] = " "
            i += 3
            while i < n:
                if text.startswith('"""', i):
                    for j in range(i, min(i + 3, n)):
                        out[j] = " "
                    i += 3
                    break
                if text[i] != "\n":
                    out[i] = " "
                i += 1
        elif char in "\"'":
            quote = char
            out[i] = " "
            i += 1
            while i < n and text[i] != quote:
                if text[i] == "\\":
                    out[i] = " "
                    i += 1
                    if i < n:
                        out[i] = " "
                        i += 1
                    continue
                if text[i] != "\n":
                    out[i] = " "
                i += 1
            if i < n:
                out[i] = " "
                i += 1
        else:
            i += 1
    return "".join(out)


def top_level_types(clean: str) -> list[tuple[int, str, bool]]:
    """(line, name, is_public) for every top-level type declaration, in source order."""
    braces = [(match.start(), 1 if match.group() == "{" else -1) for match in re.finditer(r"[{}]", clean)]
    found: list[tuple[int, str, bool]] = []
    depth = 0
    brace_index = 0
    for match in TYPE_RE.finditer(clean):
        while brace_index < len(braces) and braces[brace_index][0] < match.start():
            depth += braces[brace_index][1]
            brace_index += 1
        if depth == 0:
            line = clean.count("\n", 0, match.start()) + 1
            found.append((line, match.group("name"), "public" in match.group("mods").split()))
    return found


def check_file(path: Path, root: Path, public_types: dict[str, str]) -> tuple[list[str], int]:
    """Identity problems of one file (formatted `<rel>:<line>: <message>`) and its top-level type count."""
    rel = path.relative_to(ROOT).as_posix()
    clean = blank_comments_and_literals(path.read_text(encoding="utf-8", errors="replace"))
    types = top_level_types(clean)
    if not types:
        return [], 0

    problems: list[str] = []
    package_match = PACKAGE_RE.search(clean)
    package = package_match.group(1) if package_match else ""
    expected_dir = Path(*package.split(".")) if package else Path(".")
    actual_dir = path.parent.relative_to(root)
    line, first_name, _ = types[0]
    stem = path.stem

    if actual_dir != expected_dir:
        problems.append(f"{rel}:{line}: package '{package or '<default>'}' belongs in '{expected_dir.as_posix()}/'"
                        f" but this file is in '{actual_dir.as_posix() or '.'}/'")

    for type_line, name, is_public in types:
        if is_public and name != stem:
            problems.append(f"{rel}:{type_line}: public type '{name}' is declared in '{stem}.java'")
            break
    else:
        if not any(name == stem for _, name, _ in types):
            problems.append(f"{rel}:{line}: no top-level type matches the file name '{stem}.java'"
                            f" (declares {', '.join(name for _, name, _ in types)})")

    for type_line, name, is_public in types:
        if not is_public:
            continue
        qualified = f"{package}.{name}" if package else name
        owner = public_types.setdefault(qualified, rel)
        if owner != rel:
            problems.append(f"{rel}:{type_line}: public type '{qualified}' is already declared in {owner}")
    return problems, len(types)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--quiet", action="store_true", help="only print the summary line")
    args = parser.parse_args()

    roots = source_roots()
    public_types: dict[str, str] = {}
    problems: list[str] = []
    files = 0
    types_seen = 0
    for root in roots:
        for path in sorted(root.rglob("*.java")):
            files += 1
            file_problems, file_types = check_file(path, root, public_types)
            problems.extend(file_problems)
            types_seen += file_types

    if not args.quiet:
        print(f"java file identity check @ {ROOT}")
        print(f"  scanned: {files} file(s) in {', '.join(root.relative_to(ROOT).as_posix() for root in roots)},"
              f" top-level types: {types_seen}, public: {len(public_types)}")
        for problem in problems:
            print(f"  {problem}")

    print(f"\nproblems: {len(problems)}")
    return 1 if problems else 0


if __name__ == "__main__":
    raise SystemExit(main())
