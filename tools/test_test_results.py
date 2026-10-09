import tempfile
import unittest
from pathlib import Path
from check_test_results import check


class TestResultGateTest(unittest.TestCase):
    def test_results_are_required(self):
        with tempfile.TemporaryDirectory() as directory:
            self.assertIsNotNone(check(directory))

    def test_all_nonpassing_outcomes_and_malformed_xml_fail(self):
        with tempfile.TemporaryDirectory() as directory:
            result = Path(directory) / "build/test-results/test/TEST-suite.xml"
            result.parent.mkdir(parents=True)
            for field in ("failures", "errors", "skipped"):
                result.write_text(f'<testsuite tests="2" {field}="1"/>', encoding="utf-8")
                self.assertIsNotNone(check(directory))
            result.write_text('<testsuite tests="0"/>', encoding="utf-8")
            self.assertIsNotNone(check(directory))
            result.write_text('<broken', encoding="utf-8")
            self.assertIsNotNone(check(directory))
            result.write_text('<testsuite tests="2" failures="0" errors="0" skipped="0"/>', encoding="utf-8")
            self.assertIsNone(check(directory))
