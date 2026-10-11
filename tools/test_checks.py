"""Self-tests for the repository contract checks.

Each class proves one check is still able to fail: a throwaway module tree gets a violation, the check has to
exit non-zero and print the offending place, and the same tree with the violation repaired has to exit zero. A
check whose rule is removed or weakened stops failing the first half, so the test goes red with it.
"""

import shutil
import subprocess
import sys
import unittest
from pathlib import Path

TOOLS = Path(__file__).resolve().parent
if str(TOOLS) not in sys.path:
    sys.path.insert(0, str(TOOLS))

from selftest_support import CheckSelfTest, build_tree, run_check, write  # noqa: E402


class StripCeCommentsSelfTest(CheckSelfTest):

    script = "strip_ce_comments.py"
    args = ("--check",)
    path = "src/main/resources/craftengine/farmersdelight/configuration/items.yml"

    def test_a_comment_in_a_shipped_config_fails(self):
        self.assert_red_then_green(
            {},
            self.path,
            "items:\n  example:\n    # the operator can rename this\n    material: minecraft:stick\n",
            "items:\n  example:\n    material: minecraft:stick\n",
            "items.yml")


class DuplicateYamlKeysSelfTest(CheckSelfTest):

    script = "check_duplicate_yaml_keys.py"
    args = ("--quiet",)
    path = "src/main/resources/config.yml"

    def test_a_repeated_key_in_one_mapping_fails(self):
        self.assert_red_then_green(
            {},
            self.path,
            "settings:\n  first: 1\n  first: 2\n",
            "settings:\n  first: 1\n  second: 2\n",
            "config.yml")


class LangKeysSelfTest(CheckSelfTest):

    script = "check_lang_keys.py"
    args = ("--quiet",)
    path = "src/main/resources/lang/zh_cn.yml"
    en = "command:\n  greeting: \"Hello\"\n"

    def test_a_key_defined_in_only_one_locale_fails(self):
        self.assert_red_then_green(
            {"src/main/resources/lang/en_us.yml": self.en},
            self.path,
            "command:\n  other: \"Other\"\n",
            'command:\n  greeting: "Hello"\n',
            "greeting")


class ApiBoundarySelfTest(CheckSelfTest):

    script = "check_api_boundary.py"
    args = ("--quiet",)
    path = "src/main/java/com/huidu/farmersdelight/api/Leaky.java"

    def test_a_public_api_member_naming_an_internal_type_fails(self):
        self.assert_red_then_green(
            {},
            self.path,
            "package com.huidu.farmersdelight.api;\n\n"
            "import com.huidu.farmersdelight.util.BlockPosKey;\n\n"
            "public final class Leaky {\n"
            "    public BlockPosKey position() {\n"
            "        return null;\n"
            "    }\n"
            "}\n",
            "package com.huidu.farmersdelight.api;\n\n"
            "public final class Leaky {\n"
            "    public int position() {\n"
            "        return 0;\n"
            "    }\n"
            "}\n",
            "BlockPosKey")


class ConfigPathsSelfTest(CheckSelfTest):

    script = "check_config_paths.py"
    args = ("--quiet",)
    path = "src/main/java/com/huidu/farmersdelight/Reads.java"

    def test_a_literal_config_path_with_no_shipped_key_fails(self):
        self.assert_red_then_green(
            {"src/main/resources/config.yml": "settings:\n  known: true\n"},
            self.path,
            "package com.huidu.farmersdelight;\n\n"
            "public final class Reads {\n"
            "    static boolean read() {\n"
            "        return getConfigBoolean(true, \"settings.missing\");\n"
            "    }\n"
            "}\n",
            "package com.huidu.farmersdelight;\n\n"
            "public final class Reads {\n"
            "    static boolean read() {\n"
            "        return getConfigBoolean(true, \"settings.known\");\n"
            "    }\n"
            "}\n",
            "settings.missing")


class PackClientKeysSelfTest(CheckSelfTest):

    script = "check_pack_client_keys.py"
    args = ("--quiet",)
    path = "src/main/resources/config.yml"
    pack = ("src/main/resources/craftengine/farmersdelight/resourcepack/assets/farmersdelight/"
            "lang/en_us.json")
    locale = '{"gui.example": "Example"}'

    def test_a_client_bound_key_missing_from_the_pack_fails(self):
        self.assert_red_then_green(
            {self.pack: self.locale},
            self.path,
            'title: "<lang:gui.missing>"\n',
            'title: "<lang:gui.example>"\n',
            "gui.missing")

    def test_a_vanilla_block_name_is_not_demanded_from_the_pack(self):
        """block.minecraft.* is written as one of the vanilla namespaces and resolves from the client's jar.

        The mutation at the end patches the prefix list out of a copy of the check: the same tree then has to
        fail, which is what keeps the rule from being deleted without a yellow light.
        """
        tree = build_tree({self.pack: self.locale})
        try:
            write(tree, self.path, 'title: "<lang:block.minecraft.crimson_fence>"\n')
            passed = run_check(tree, self.script, *self.args)
            self.assertEqual(0, passed.returncode,
                             "a vanilla block name must not be demanded from the pack language files:\n"
                             + passed.stdout + passed.stderr)

            patched = (TOOLS / self.script).read_text(encoding="utf-8").replace(
                '"block.minecraft.", "item.minecraft.", "entity.minecraft.",', "")
            self.assertNotEqual(patched, (TOOLS / self.script).read_text(encoding="utf-8"),
                                "the mutation has to change the prefix list, or this test proves nothing")
            mutated = tree / "tools" / self.script
            mutated.write_text(patched, encoding="utf-8")
            failed = subprocess.run([sys.executable, str(mutated), *self.args], cwd=str(tree),
                                    capture_output=True, text=True)
            self.assertNotEqual(0, failed.returncode,
                                "without the vanilla prefixes the same tree must fail:\n"
                                + failed.stdout + failed.stderr)
            self.assertIn("block.minecraft.crimson_fence", failed.stdout + failed.stderr)
        finally:
            shutil.rmtree(tree, ignore_errors=True)


class BlockStateOccupancySelfTest(CheckSelfTest):

    script = "check_block_state_occupancy.py"
    args = ()
    path = "src/main/resources/craftengine/farmersdelight/configuration/blocks.yml"

    def test_two_blocks_pinning_one_vanilla_state_fail(self):
        self.assert_red_then_green(
            {},
            self.path,
            "blocks:\n"
            "  first:\n"
            "    state: minecraft:bamboo_button[face=floor]\n"
            "  second:\n"
            "    state: minecraft:bamboo_button[face=floor]\n",
            "blocks:\n"
            "  first:\n"
            "    state: minecraft:bamboo_button[face=floor]\n"
            "  second:\n"
            "    state: minecraft:bamboo_button[face=ceiling]\n",
            "bamboo_button")


class MealIconsSelfTest(unittest.TestCase):
    """The one check whose rule is a pure function, so its self-test drives the function directly."""

    def setUp(self):
        import meal_icons
        self.meal_icons = meal_icons

    def read(self, text):
        import tempfile
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "recipes.yml"
            path.write_text(text, encoding="utf-8")
            return self.meal_icons.result_ids(path)

    def test_the_three_recipe_roots_and_every_result_shape(self):
        self.assertEqual(["example:soup", "example:stew", "example:pie"], self.read("""
cooking_pot_recipes:
  mapping:
    result: {item: "example:soup", count: 3}
  list:
    result: ["example:stew"]
  bare:
    result: "example:pie"
"""))

    def test_the_pack_root_and_the_addon_root_are_read_too(self):
        self.assertEqual(["example:soup", "minecraft:baked_potato"], self.read("""
cooking_recipes:
  pack:
    result: {item: "example:soup"}
custom_cooking_pot_recipes:
  addon:
    result: {item: "minecraft:baked_potato"}
"""))

    def test_missing_results_and_unclaimed_sections_are_ignored(self):
        self.assertEqual([], self.read("""
unclaimed_recipes:
  meal:
    result: {item: "example:soup"}
cooking_pot_recipes:
  missing: {}
"""))


if __name__ == "__main__":
    unittest.main()
