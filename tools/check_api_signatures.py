"""Guard the binary shape of the addon-facing api.

The addon boundary check (`check_api_boundary.py`) keeps internal types out of `api.**`. This check covers the
other half: it records the public signatures of every `api.**` type in `tools/api-signatures.txt` and fails when a
signature that an already published addon can link against disappears or changes.

Policy:
  * removed or changed signatures fail the check — that is a binary break for addons compiled against an earlier
    build;
  * added signatures are allowed (the api is append-only by policy) but are reported so the baseline gets
    refreshed in the same change with `--update`;
  * `--update` rewrites the baseline after a reviewed addition, it never bypasses a removal.

The signatures are derived from source, not bytecode, so the check can run in the same place as the other
contract checks (no Gradle/JDK invocation in the gate). Keep the parser conservative: a construct it cannot
understand is ignored rather than reported, so this check never invents a failure.
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

BASELINE = "api-signatures.txt"
API_PACKAGE = "com.huidu.farmersdelight.api"
TYPE_DECL_RE = re.compile(r"(?:^|\s)(class|interface|record|enum|@interface)\s+([A-Za-z_$][\w$]*)")
ANNOTATION_RE = re.compile(r"@[A-Za-z_$][\w$.]*(\s*\([^()]*\))?")
WHITESPACE_RE = re.compile(r"\s+")


def repo_root() -> Path:
    here = Path(__file__).resolve()
    for parent in here.parents:
        if (parent / "src" / "main" / "java").is_dir():
            return parent
    sys.exit("could not locate the FarmersDelight module root from " + str(here))


def api_root(root: Path) -> Path:
    return root / "src" / "main" / "java" / API_PACKAGE.replace(".", "/")


def strip_comments_and_literals(text: str) -> str:
    """Remove comments and literal bodies so braces/semicolons inside them cannot confuse the scanner."""
    text = re.sub(r"/\*.*?\*/", " ", text, flags=re.S)
    text = re.sub(r"//[^\n]*", " ", text)
    text = re.sub(r'"(?:\\.|[^"\\])*"', '""', text)
    text = re.sub(r"'(?:\\.|[^'\\])*'", "''", text)
    return text


def normalize_signature(text: str) -> str:
    text = ANNOTATION_RE.sub(" ", text)
    text = WHITESPACE_RE.sub(" ", text).strip()
    text = text.replace("( ", "(").replace(" )", ")").replace(" ,", ",")
    text = text.replace(" <", "<").replace("> ", ">").replace(" >", ">")
    text = text.replace("[ ", "[").replace(" ]", "]")
    return text


def without_initializer(declaration: str) -> str:
    """Drop a field initializer: `= value` is not part of the binary signature."""
    equals = declaration.find("=")
    if equals < 0:
        return declaration
    parenthesis = declaration.find("(")
    if parenthesis >= 0 and parenthesis < equals:
        # Method/constructor signature: the parameter list comes before any `=`.
        return declaration
    return declaration[:equals].rstrip()


def package_of(root: Path, path: Path) -> str:
    rel = path.relative_to(root / "src" / "main" / "java")
    return ".".join(rel.with_suffix("").parts[:-1])


def declarations(text: str):
    """Yield ("type"|"member"|"end", declaration) items, skipping method and initializer bodies.

    Type bodies are scanned (their members matter); method bodies, static initializers and anonymous classes
    are skipped. Every `}` that reaches the main loop closes a scanned type body, so an "end" marker is emitted
    for the caller to unwind its nesting stack. Members are yielded regardless of their access modifier; the
    caller decides what is public (interface members are public implicitly).
    """
    buffer = ""
    i = 0
    n = len(text)
    while i < n:
        c = text[i]
        if c == "{":
            chunk = normalize_signature(buffer)
            match = TYPE_DECL_RE.search(chunk)
            if match:
                yield ("type", chunk)
                buffer = ""
                i += 1
                continue
            if "(" in chunk and "=" not in chunk:
                yield ("member", without_initializer(chunk))
            buffer = ""
            depth = 1
            i += 1
            while i < n and depth > 0:
                if text[i] == "{":
                    depth += 1
                elif text[i] == "}":
                    depth -= 1
                i += 1
            continue
        if c == "}":
            yield ("end", "")
            buffer = ""
            i += 1
            continue
        if c == ";":
            chunk = normalize_signature(buffer)
            if chunk:
                yield ("member", without_initializer(chunk))
            buffer = ""
            i += 1
            continue
        buffer += c
        i += 1
    tail = normalize_signature(buffer)
    if tail:
        yield ("member", without_initializer(tail))


def signature_lines(package: str, text: str) -> list[str]:
    lines: list[str] = []
    nested: list[str] = []
    nested_kinds: list[str] = []
    public_levels: list[bool] = []
    for kind, declaration in declarations(text):
        if kind == "type":
            match = TYPE_DECL_RE.search(declaration)
            name = match.group(2) if match else declaration
            keyword = match.group(1) if match else "class"
            in_interface = bool(nested_kinds) and nested_kinds[-1] in ("interface", "@interface")
            is_public = declaration.startswith("public") or (
                in_interface and not declaration.startswith(("private", "protected")))
            nested.append(name)
            nested_kinds.append(keyword)
            public_levels.append(is_public and (all(public_levels) if public_levels else True))
            if public_levels[-1]:
                owner = package + "." + ".".join(nested)
                lines.append("type|" + owner + "|" + declaration)
        elif kind == "end":
            if nested:
                nested.pop()
            if nested_kinds:
                nested_kinds.pop()
            if public_levels:
                public_levels.pop()
        else:
            if not public_levels or not public_levels[-1]:
                continue
            in_interface = nested_kinds[-1] in ("interface", "@interface")
            if declaration.startswith("public"):
                signature = declaration
            elif in_interface and not declaration.startswith(("private", "protected")):
                signature = "public " + declaration
            else:
                continue
            owner = package + "." + ".".join(nested) if nested else package
            lines.append("member|" + owner + "|" + signature)
    return lines


def collect(root: Path) -> list[str]:
    package_dir = api_root(root)
    if not package_dir.is_dir():
        return []
    lines: list[str] = []
    for path in sorted(package_dir.rglob("*.java")):
        text = strip_comments_and_literals(path.read_text(encoding="utf-8", errors="ignore"))
        lines.extend(signature_lines(package_of(root, path), text))
    return sorted(set(line for line in lines if "|" in line))


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--quiet", action="store_true", help="print problems only")
    parser.add_argument("--update", action="store_true", help="rewrite the baseline with the current signatures")
    args = parser.parse_args()

    root = repo_root()
    baseline_path = root / "tools" / BASELINE
    current = collect(root)
    if args.update:
        baseline_path.write_text("\n".join(current) + "\n", encoding="utf-8", newline="\n")
        print(f"api signatures: wrote {len(current)} signatures to {baseline_path}")
        return 0
    if not baseline_path.is_file():
        print(f"missing baseline {baseline_path}; run: python tools/check_api_signatures.py --update")
        return 1

    baseline = [line for line in baseline_path.read_text(encoding="utf-8").splitlines() if line.strip()]
    current_set = set(current)
    baseline_set = set(baseline)
    removed = [line for line in baseline if line not in current_set]
    added = [line for line in current if line not in baseline_set]

    if not args.quiet:
        print(f"api signatures @ {root}")
        print(f"  baseline: {len(baseline)} signatures, current: {len(current)}")

    if added:
        print(f"\napi signatures added ({len(added)}); review, then refresh the baseline with --update:")
        for line in added[:40]:
            print("  + " + line)

    if removed:
        print(f"\napi signatures removed or changed ({len(removed)}); this breaks addons compiled against them:")
        for line in removed[:40]:
            print("  - " + line)

    print(f"\nproblems: {len(removed)}")
    return 1 if removed else 0


if __name__ == "__main__":
    raise SystemExit(main())
