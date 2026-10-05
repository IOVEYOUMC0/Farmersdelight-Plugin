"""Check that no two custom blocks pin the same vanilla block state.

Why this matters: a CraftEngine block picks the vanilla block state it occupies. Two *different* custom blocks
that pin the same state cannot coexist — the game sees one vanilla block, so the second one placed is
indistinguishable from the first (its appearance, its break/place behaviour and its block entity all resolve
through the same state). That is the defect the owner found by eye: three baskets sharing one vanilla state.
None of the other checks look at this.

What counts as a pin: an explicit `state:` written under a block's `appearances`/`state` section. `auto_state`
is ignored on purpose — there CE reserves a free state per block, so two blocks using `auto_state` never
collide by construction.

The judgement rule (this is the anti-false-positive rule):
  * one block reusing the same state across its own faces is NORMAL (an 8-faced `iron_trapdoor` block pins
    the same trapdoor state eight times) — those are reported in a separate "single owner" list, never as a
    problem;
  * only the same state pinned by **two or more different owners** is a problem. An owner is a key under
    `blocks:` / `block:` (a concrete custom block) or under `templates:` (a shared definition, labelled
    `templates:`), because a state pinned by a template is pinned by every block that uses that template.

`block-state-mappings` is reported separately as a hint: it folds vanilla states onto each other (for example
`composter[level=7] -> composter[level=6]`), which can push two states that blocks pinned apart onto one slot.
It is a hint, not a problem, and never changes the exit code.

Usage:
    python tools/check_block_state_occupancy.py            # from FarmersDelight/
    python tools/check_block_state_occupancy.py --quiet    # summary only
Exit code 0 when no state is shared by two owners, 1 otherwise.
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
CONFIG_ROOT = "src/main/resources/craftengine"

# Sections whose direct children are block (or template) definitions. A data-driven whitelist: the only
# sections in this repository that carry explicit `state:` pins are these three; `placed-features` also
# contains `state:` keys, but those are worldgen block-state providers, not pinned appearances.
BLOCK_SECTIONS = ("blocks", "block", "templates")
REMAP_SECTION = "block-state-mappings"
SKIP_PARTS = ("\\build\\", "/build/", "\\Reference\\", "/Reference/")


def config_files() -> list[Path]:
    base = ROOT / CONFIG_ROOT
    if not base.is_dir():
        return []
    return sorted(path for path in base.rglob("*.yml")
                  if path.is_file() and not any(part in str(path) for part in SKIP_PARTS))


class Findings:
    """One run's results: per-owner pins plus the separate remap hints."""

    def __init__(self) -> None:
        self.pins: list[tuple[str, str, str, int]] = []   # (rel path, owner, state, line)
        self.remaps: list[tuple[str, str, str, int]] = []  # (rel path, source state, target, line)
        self.template_refs: dict[str, set[str]] = {}       # owner -> template ids it uses
        self.owners = 0


def scalar(node: yaml.Node) -> str | None:
    return node.value if isinstance(node, yaml.ScalarNode) else None


def strings(node: yaml.Node) -> list[str]:
    """Every scalar string in a node: one value or a list of values."""
    if isinstance(node, yaml.ScalarNode):
        return [node.value]
    if isinstance(node, yaml.SequenceNode):
        return [item.value for item in node.value if isinstance(item, yaml.ScalarNode)]
    return []


def collect_pins(node: yaml.Node, rel: str, owner: str, findings: Findings) -> None:
    """Collect every explicit `state: <scalar>` under one block definition, plus its `template:` references.

    `state:` sometimes holds the mapping form `state: {state: <scalar>, entity_renderer: ...}`, so the walk
    descends into non-scalar values instead of stopping at them. `auto_state` is never matched: the key must
    be exactly `state`. The `template:` references are collected so a state pinned by a shared template can
    name the blocks that inherit it.
    """
    if isinstance(node, yaml.MappingNode):
        for key_node, value_node in node.value:
            key = scalar(key_node)
            if key == "state":
                pinned = scalar(value_node)
                if pinned:
                    findings.pins.append((rel, owner, pinned, value_node.start_mark.line + 1))
            elif key == "template":
                for reference in strings(value_node):
                    findings.template_refs.setdefault(owner, set()).add(reference)
            collect_pins(value_node, rel, owner, findings)
    elif isinstance(node, yaml.SequenceNode):
        for item in node.value:
            collect_pins(item, rel, owner, findings)


def scan_file(path: Path, findings: Findings) -> tuple[int, str | None]:
    """Return (owners seen, skip reason). A file that is not single-document YAML is skipped and named."""
    try:
        text = path.read_text(encoding="utf-8")
    except (OSError, UnicodeDecodeError) as error:
        return 0, f"not readable as UTF-8 ({error.__class__.__name__})"
    try:
        root = yaml.compose(text, Loader=yaml.SafeLoader)
    except yaml.YAMLError as error:
        message = str(error).strip()
        detail = message.splitlines()[0] if message else error.__class__.__name__
        return 0, f"not single-document strict YAML ({error.__class__.__name__}: {detail})"
    if not isinstance(root, yaml.MappingNode):
        return 0, None

    rel = path.relative_to(ROOT).as_posix()
    owners = 0
    for section_node, section_value in root.value:
        section = scalar(section_node)
        if section == REMAP_SECTION and isinstance(section_value, yaml.MappingNode):
            for from_node, to_node in section_value.value:
                source, target = scalar(from_node), scalar(to_node)
                if source and target:
                    findings.remaps.append((rel, source, target, from_node.start_mark.line + 1))
            continue
        if section not in BLOCK_SECTIONS or not isinstance(section_value, yaml.MappingNode):
            continue
        for id_node, definition in section_value.value:
            block_id = scalar(id_node)
            if not block_id:
                continue
            before = len(findings.pins)
            collect_pins(definition, rel, f"{section}:{block_id}", findings)
            if len(findings.pins) > before:
                owners += 1
    return owners, None


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--quiet", action="store_true", help="only print the summary line")
    args = parser.parse_args()

    findings = Findings()
    files = config_files()
    skipped: list[tuple[str, str]] = []
    for path in files:
        owners, reason = scan_file(path, findings)
        findings.owners += owners
        if reason is not None:
            skipped.append((path.relative_to(ROOT).as_posix(), reason))

    by_state: dict[str, list[tuple[str, str, int]]] = {}
    for rel, owner, state, line in findings.pins:
        by_state.setdefault(state, []).append((owner, rel, line))

    conflicts = []
    single_owner = []
    for state, pins in sorted(by_state.items()):
        owners = sorted({owner for owner, _, _ in pins})
        if len(owners) > 1:
            _, first_rel, first_line = min(pins, key=lambda pin: (pin[1], pin[2]))
            conflicts.append((first_rel, first_line, state, owners, len(pins)))
        elif len(pins) > 1:
            _, first_rel, first_line = pins[0]
            single_owner.append((first_rel, first_line, state, owners[0], len(pins)))

    template_ids = {owner.split(":", 1)[1] for _, owner, _, _ in findings.pins
                    if owner.startswith("templates:")}

    def label(owner: str) -> str:
        if not owner.startswith("templates:"):
            return owner
        template_id = owner.split(":", 1)[1]
        users = sorted(user.split(":", 1)[1] for user, refs in findings.template_refs.items()
                       if template_id in refs and not user.startswith("templates:"))
        if not users:
            return owner
        return f"{owner} (used by {len(users)} block(s): {', '.join(users)})"

    if not args.quiet:
        print(f"block state occupancy check @ {ROOT}")
        print(f"  scanned: {len(files)} file(s), owners with explicit pins: {findings.owners},"
              f" pins: {len(findings.pins)}, shared templates pinned: {len(template_ids)}")

    for rel, reason in skipped:
        print(f"skipped: {rel}: {reason}")

    if conflicts and not args.quiet:
        print(f"\nstates pinned by more than one owner ({len(conflicts)}):")
        for rel, line, state, owners, pins in conflicts:
            print(f"  {rel}:{line}: {state} pinned by {', '.join(label(owner) for owner in owners)}"
                  f" ({pins} pin(s))")

    if single_owner and not args.quiet:
        print(f"\nsingle owner reusing one state across its own faces (normal, {len(single_owner)}):")
        for rel, line, state, owner, pins in single_owner:
            print(f"  {rel}:{line}: {state} x {pins} by {owner}")

    if findings.remaps and not args.quiet:
        print(f"\nhint: block-state-mappings folds states onto each other ({len(findings.remaps)} entry/ies);"
              " two states pinned by different owners can end up sharing one vanilla slot:")
        for rel, source, target, line in findings.remaps:
            print(f"  {rel}:{line}: {source} -> {target}")

    print(f"\nproblems: {len(conflicts)}")
    return 1 if conflicts else 0


if __name__ == "__main__":
    raise SystemExit(main())
