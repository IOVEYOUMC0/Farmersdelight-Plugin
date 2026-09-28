"""Enforce the addon/plugin API boundary mechanically.

The rule (see the project readme): an addon may only depend on FarmersDelight's `api.**` facade at
compile time. Everything else is internal and may be refactored or moved without notice.

Two checks, both mechanical:
  1. no file outside FarmersDelight/ may import a `com.huidu.farmersdelight.*` package that is not
     `api` (this is the rule the whole addon split rests on);
  2. no `api/**` class may *declare* a public signature that mentions an internal FD type — that is
     what would leak an implementation type through the facade to a caller.

Check 2 is source-level: a public method/field/parameter whose type is written as an FD-internal
class name (resolved by the file's own imports). It deliberately does not try to be a full type
resolver; it flags what is written down, which is what a reviewer would catch.

Usage:
    python tools/check_api_boundary.py            # from FarmersDelight/, uses ../<addon> dirs
    python tools/check_api_boundary.py --quiet    # problems only
Exit code is 0 when clean, 1 when a violation is found.
"""
from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

# Modules that consume the facade.
ADDON_DIRS = [
    "BarbequesDelight",
    "BrewinAndChewin",
    "CrabbersDelight",
    "EndsDelight",
    "FDAddonTemplate",
    "VillagersDelight",
]

FD_PACKAGE = "com.huidu.farmersdelight"
IMPORT_RE = re.compile(r"^\s*import\s+(static\s+)?(" + re.escape(FD_PACKAGE) + r"\.[\w.]+);", re.M)
SKIP_PARTS = ("\\build\\", "/build/", "\\Reference\\", "/Reference/", "\\.git\\", "/.git/",
              "\\.codex-backups\\", "/.codex-backups/")


def repo_root() -> Path:
    """FarmersDelight/ is the repo; its parent holds the sibling addon checkouts."""
    here = Path(__file__).resolve()
    for parent in here.parents:
        if (parent / "src" / "main" / "java").is_dir():
            return parent
    sys.exit("could not locate the FarmersDelight module root from " + str(here))


def is_api(imported: str) -> bool:
    return imported == FD_PACKAGE + ".api" or imported.startswith(FD_PACKAGE + ".api.")


def java_files(root: Path):
    for path in root.rglob("*.java"):
        text = str(path)
        if any(part in text for part in SKIP_PARTS):
            continue
        yield path


def check_addon_imports(root: Path) -> list[str]:
    problems: list[str] = []
    base = root.parent
    for addon in ADDON_DIRS:
        module = base / addon
        if not module.is_dir():
            continue
        for path in java_files(module):
            rel = path.relative_to(base)
            for match in IMPORT_RE.finditer(path.read_text(encoding="utf-8", errors="ignore")):
                imported = match.group(2)
                if not is_api(imported):
                    problems.append(f"{rel}: imports internal {imported}")
    return problems


def check_api_signature_leak(root: Path) -> list[str]:
    """A public api member must not name an FD-internal type in its written signature.

    Members marked `@ApiStatus.Internal` are exempt: that annotation is exactly how this codebase says
    "public only because a sibling package needs it, never for addons", and CraftEngine marks its own
    internals the same way. Flagging those would push correct code into a worse shape just to satisfy a
    linter.
    """
    api_root = root / "src" / "main" / "java" / FD_PACKAGE.replace(".", "/") / "api"
    if not api_root.is_dir():
        return []
    problems: list[str] = []
    # A public declaration line, e.g. "    public static Foo bar(Baz b) {"
    public_decl = re.compile(r"^\s{4}public\s+(?:static\s+|final\s+|abstract\s+|synchronized\s+)*"
                             r"(?:class|interface|enum|record)?\s*[\w.<>\[\],?\s]*\(")
    public_field = re.compile(r"^\s{4}public\s+(?:static\s+|final\s+)*([\w.]+)\s+\w+\s*(=|;)")
    annotated_internal = re.compile(r"@ApiStatus\.Internal")

    def is_marked_internal(lines: list[str], lineno: int) -> bool:
        """Walk up from a declaration past its javadoc and every annotation, looking for @ApiStatus.Internal.

        A member can carry several annotations (`@ApiStatus.Internal` then `@Nullable`), and a javadoc block
        usually sits between them and the declaration, so a fixed-size window above the declaration misses it.
        Walking stops at the first line that is neither a comment nor an annotation, which keeps the search
        inside this member's own header.
        """
        i = lineno - 2  # 0-based index of the line above the declaration
        while i >= 0:
            stripped = lines[i].strip()
            if not stripped:
                i -= 1
                continue
            if stripped.startswith(("*", "/*", "*/", "//")):
                i -= 1
                continue
            if stripped.startswith("@"):
                if annotated_internal.search(stripped):
                    return True
                i -= 1
                continue
            return False
        return False

    for path in java_files(api_root):
        text = path.read_text(encoding="utf-8", errors="ignore")
        # map simple name -> whether it resolves to an FD-internal package
        internal: set[str] = set()
        for match in IMPORT_RE.finditer(text):
            imported = match.group(2)
            if not imported.startswith(FD_PACKAGE + ".api"):
                simple = imported.rsplit(".", 1)[-1]
                internal.add(simple)
        if not internal:
            continue
        rel = path.relative_to(root)
        lines = text.splitlines()
        for lineno, line in enumerate(lines, start=1):
            if line.lstrip().startswith(("//", "*", "/*")):
                continue
            if is_marked_internal(lines, lineno):
                continue
            for simple in internal:
                if re.search(r"\b" + re.escape(simple) + r"\b", line):
                    if public_decl.match(line) or public_field.match(line):
                        problems.append(
                            f"{rel}:{lineno}: public api signature names internal type "
                            f"{simple} -> {line.strip()}")
    return problems


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--quiet", action="store_true", help="print problems only")
    args = parser.parse_args()

    root = repo_root()
    addon_problems = check_addon_imports(root)
    leak_problems = check_api_signature_leak(root)

    if not args.quiet:
        print(f"api boundary check @ {root}")
        print(f"  addon modules scanned: {', '.join(ADDON_DIRS)}")
        print(f"  rule: addons may import only {FD_PACKAGE}.api.*")

    if addon_problems:
        print(f"\naddon imports an internal FarmersDelight package ({len(addon_problems)}):")
        for problem in addon_problems[:40]:
            print("  " + problem)

    if leak_problems:
        print(f"\napi/** exposes an internal type in a public signature ({len(leak_problems)}):")
        for problem in leak_problems[:40]:
            print("  " + problem)

    total = len(addon_problems) + len(leak_problems)
    print(f"\nproblems: {total}")
    return 1 if total else 0


if __name__ == "__main__":
    raise SystemExit(main())
