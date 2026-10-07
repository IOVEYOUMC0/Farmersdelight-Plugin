"""Runs every repository contract check, the self-tests of those checks, and the workspace matrix check.

Usage: python tools/run_all_checks.py [--quiet]

Each step runs in the directory its tool expects: the contract checks in the plugin module, the self-tests in
tools (so they can import the support module), and the workspace matrix check at the workspace root. The exit
status is non-zero when any step failed, so the whole set can be a single build gate.
"""

import argparse
import subprocess
import sys
from pathlib import Path

TOOLS = Path(__file__).resolve().parent
MODULE = TOOLS.parent
WORKSPACE = MODULE.parent
MATRIX = WORKSPACE / "tools" / "check_verification_matrix.py"

CHECKS = (
    ("strip_ce_comments.py", ["--check"]),
    ("meal_icons.py", ["--check"]),
    ("check_lang_keys.py", ["--quiet"]),
    ("check_api_boundary.py", ["--quiet"]),
    ("check_config_paths.py", ["--quiet"]),
    ("check_duplicate_yaml_keys.py", ["--quiet"]),
    ("check_block_state_occupancy.py", ["--quiet"]),
    ("check_pack_client_keys.py", ["--quiet"]),
)


def run(label: str, command: list, cwd: Path, quiet: bool) -> bool:
    result = subprocess.run([sys.executable, *command], cwd=str(cwd), capture_output=True, text=True)
    ok = result.returncode == 0
    print(f"[{'PASS' if ok else 'FAIL'}] {label}" + ("" if ok else f"  (exit {result.returncode})"))
    output = result.stdout + result.stderr
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

    steps = [(f"check {script} {' '.join(extra)}", [str(TOOLS / script), *extra], MODULE)
             for script, extra in CHECKS]
    steps.append(("self-test tools/test_checks.py", ["-m", "unittest", "test_checks"], TOOLS))
    if MATRIX.is_file():
        steps.append((f"workspace {MATRIX.name}", [str(MATRIX)], WORKSPACE))
    else:
        print(f"[SKIP] workspace matrix check not found at {MATRIX}")

    failures = [label for label, command, cwd in steps if not run(label, command, cwd, args.quiet)]

    print(f"\n{len(steps) - len(failures)}/{len(steps)} steps passed"
          + ("" if not failures else "  <-- " + ", ".join(failures)))
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
