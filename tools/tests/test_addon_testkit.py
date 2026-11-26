"""Self-tests for tools/addon_testkit.py.

Run from the repository root:
    python -m unittest tools.tests.test_addon_testkit -v
"""
from __future__ import annotations

import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "tools" / "addon_testkit.py"

VALID_JAVA = """package com.example.addon;
import com.huidu.farmersdelight.api.FarmersDelightApi;
public final class Addon {
    public int version() { return FarmersDelightApi.get().apiVersion(); }
}
"""
DEPRECATED_JAVA = """package com.example.addon;
import com.huidu.farmersdelight.api.util.CeItemInterop;
public final class Addon { Object interop() { return CeItemInterop; } }
"""
INVALID_JAVA = """package com.example.addon;
import com.huidu.farmersdelight.FarmersDelightPlugin;
public final class Addon {
    FarmersDelightPlugin plugin;
}
"""
MANIFEST = """name: TestAddon
version: '1.0.0'
api-version: '1.21.5'
dependencies:
  server:
    FarmersDelight:
      load: BEFORE
      required: true
      join-classpath: true
"""


class AddonTestkitSelfTest(unittest.TestCase):
    def run_kit(self, java: str, manifest: str | None = MANIFEST) -> subprocess.CompletedProcess[str]:
        with tempfile.TemporaryDirectory(prefix="fd-addon-test-") as directory:
            root = Path(directory)
            source = root / "src" / "main" / "java" / "com" / "example" / "addon" / "Addon.java"
            source.parent.mkdir(parents=True)
            source.write_text(java, encoding="utf-8")
            if manifest is not None:
                resources = root / "src" / "main" / "resources"
                resources.mkdir(parents=True)
                (resources / "paper-plugin.yml").write_text(manifest, encoding="utf-8")
            return subprocess.run([sys.executable, str(SCRIPT), str(root), "--skip-build"],
                                  text=True, capture_output=True, timeout=60)

    def test_valid_addon_passes(self) -> None:
        result = self.run_kit(VALID_JAVA)
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)

    def test_internal_import_fails(self) -> None:
        result = self.run_kit(INVALID_JAVA)
        self.assertEqual(1, result.returncode)
        self.assertIn("api-boundary", result.stdout)

    def test_deprecated_api_fails(self) -> None:
        result = self.run_kit(DEPRECATED_JAVA)
        self.assertEqual(1, result.returncode)
        self.assertIn("deprecated-api", result.stdout)

    def test_missing_dependency_fails(self) -> None:
        result = self.run_kit(VALID_JAVA, "name: TestAddon\n")
        self.assertEqual(1, result.returncode)
        self.assertIn("paper-dependency", result.stdout)

    def test_missing_feature_gate_fails(self) -> None:
        result = self.run_kit("package com.example.addon;\npublic class Addon {}\n")
        self.assertEqual(1, result.returncode)
        self.assertIn("feature-gate", result.stdout)


if __name__ == "__main__":
    unittest.main()
