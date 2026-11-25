"""Runs every repository contract check, the self-tests of those checks, and the verification matrix check.

Usage: python tools/run_all_checks.py [--quiet]

Each step runs in the directory it needs: the contract checks and the matrix check in this module (the matrix
check finds the repository from its own path, so the working directory only has to be somewhere sensible), and
the self-tests in tools (so they can import the support module). The exit status is non-zero when any step
failed, so the whole set can be a single build gate.
"""

import argparse
import os
import subprocess
import sys
from pathlib import Path

TOOLS = Path(__file__).resolve().parent
MODULE = TOOLS.parent
MATRIX = TOOLS / "check_verification_matrix.py"

# Checks that only assert something when their input exists. The records live outside this repository, so in a
# checkout without them the step used to report "problems: 0" and count as a pass, which made the gate look
# stronger than it is. Such a step is now reported as SKIPPED and excluded from the pass count.
OPTIONAL_INPUTS = {
    "check_verification_matrix.py": ("docs", "verification.md"),
    "check_verification_evidence.py": ("docs", "verification"),
}

CHECKS = (
    ("strip_ce_comments.py", ["--check"]),
    ("meal_icons.py", ["--check"]),
    ("check_lang_keys.py", ["--quiet"]),
    ("check_api_boundary.py", ["--quiet"]),
    ("check_api_signatures.py", ["--quiet"]),
    ("check_error_handling.py", ["--quiet"]),
    ("check_config_paths.py", ["--quiet"]),
    ("check_duplicate_yaml_keys.py", ["--quiet"]),
    ("check_block_state_occupancy.py", ["--quiet"]),
    ("check_pack_client_keys.py", ["--quiet"]),
    ("check_behavior_keys.py", ["--quiet"]),
    ("check_addon_config_keys.py", ["--quiet"]),
    ("check_comment_tags.py", ["--quiet"]),
    ("check_verification_evidence.py", ["--quiet"]),
)


def run(label: str, command: list, cwd: Path, quiet: bool) -> bool:
    result = subprocess.run([sys.executable, *command], cwd=str(cwd), capture_output=True, text=True, encoding="utf-8",
                            env={**os.environ, "PYTHONUTF8": "1"})
    ok = result.returncode == 0
    print(f"[{'PASS' if ok else 'FAIL'}] {label}" + ("" if ok else f"  (exit {result.returncode})"))
    output = (result.stdout or "") + (result.stderr or "")
    if not ok:
        for line in [row for row in output.splitlines() if row.strip()][-8:]:
            print(f"        {line}")
    elif not quiet:
        summary = [row for row in output.splitlines() if "problems:" in row]
        for line in summary[:2]:
            print(f"        {line.strip()}")
    return ok


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--quiet", action="store_true", help="only print each step's verdict")
    args = parser.parse_args()

    siblings = ["BarbequesDelight", "BrewinAndChewin", "CrabbersDelight", "EndsDelight", "FDAddonTemplate", "VillagersDelight"]
    missing = [name for name in siblings if not (MODULE.parent / name).is_dir()]
    if missing:
        print("[SCOPE] Missing sibling repositories are not checked: " + ", ".join(missing))

    steps = []
    skipped = []
    for script, extra in CHECKS:
        required = OPTIONAL_INPUTS.get(script)
        if required is not None and not (MODULE.joinpath(*required)).exists():
            skipped.append(f"check {script} (no {os.path.join(*required)})")
            continue
        steps.append((f"check {script} {' '.join(extra)}", [str(TOOLS / script), *extra], MODULE))
    steps.append(("self-test tools/test_checks.py", ["-m", "unittest", "test_checks", "test_test_results"], TOOLS))
    if (MODULE / "docs" / "verification.md").is_file():
        steps.append((f"matrix {MATRIX.name}", [str(MATRIX)], MODULE))
    else:
        skipped.append("matrix check_verification_matrix.py (no docs/verification.md; records live outside)")

    for label in skipped:
        print(f"[SKIP] {label}")

    failures = [label for label, command, cwd in steps if not run(label, command, cwd, args.quiet)]

    tail = "" if not failures else "  <-- " + ", ".join(failures)
    suffix = "" if not skipped else f", {len(skipped)} skipped"
    print(f"\n{len(steps) - len(failures)}/{len(steps)} steps passed{suffix}{tail}")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
