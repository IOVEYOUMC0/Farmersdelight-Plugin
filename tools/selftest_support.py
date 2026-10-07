"""Shared helpers for the contract-check self-tests.

A check locates its module root from its own file, so a self-test builds a throwaway module tree, copies the
check under test into that tree's tools/ directory, and runs it there as a subprocess: the exit status and the
printed problem lines are then exactly what a build gate sees, and nothing in the real repository is touched.
"""

import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

TOOLS = Path(__file__).resolve().parent


def write(tree: Path, relative: str, text: str) -> Path:
    path = tree / relative
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8")
    return path


def build_tree(files: dict) -> Path:
    folder = tempfile.mkdtemp(prefix="fd-check-selftest-")
    tree = Path(folder)
    for relative, text in files.items():
        write(tree, relative, text)
    return tree


def run_check(tree: Path, script: str, *args) -> subprocess.CompletedProcess:
    tools = tree / "tools"
    tools.mkdir(parents=True, exist_ok=True)
    shutil.copy(TOOLS / script, tools / script)
    return subprocess.run([sys.executable, str(tools / script), *args], cwd=str(tree),
                          capture_output=True, text=True)


class CheckSelfTest(unittest.TestCase):
    """One contract check: a violating tree has to fail with its problem line, a clean one has to pass."""

    script = ""
    args = ("--check",)

    def assert_red_then_green(self, files: dict, path: str, red: str, green: str, expected: str):
        tree = build_tree(files)
        try:
            write(tree, path, red)
            failed = run_check(tree, self.script, *self.args)
            self.assertNotEqual(0, failed.returncode,
                                f"{self.script} accepted a violating tree:\n{failed.stdout}{failed.stderr}")
            self.assertIn(expected, failed.stdout + failed.stderr,
                          f"{self.script} failed without naming the problem:\n{failed.stdout}{failed.stderr}")

            write(tree, path, green)
            passed = run_check(tree, self.script, *self.args)
            self.assertEqual(0, passed.returncode,
                             f"{self.script} rejected the repaired tree:\n{passed.stdout}{passed.stderr}")
        finally:
            shutil.rmtree(tree, ignore_errors=True)
