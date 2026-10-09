"""Reject missing, malformed, empty, failing or skipped Gradle JUnit results."""
import argparse
from pathlib import Path
import xml.etree.ElementTree as ET


def check(module):
    files = sorted(Path(module).glob("**/build/test-results/*/TEST-*.xml"))
    if not files:
        return "No JUnit XML results found"
    tests = 0
    for file in files:
        try:
            suite = ET.parse(file).getroot()
            counts = {key: int(suite.get(key, "0")) for key in ("tests", "failures", "errors", "skipped")}
        except (ET.ParseError, ValueError, OSError) as error:
            return f"Invalid JUnit result {file}: {error}"
        tests += counts["tests"]
        if any(counts[key] for key in ("failures", "errors", "skipped")):
            return f"JUnit gate rejected {file}: {counts}"
    return None if tests > 0 else "JUnit results contain zero tests"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("module", nargs="?", default=".")
    args = parser.parse_args()
    problem = check(args.module)
    print(problem or "JUnit gate passed: zero failures, errors or skipped tests")
    return 1 if problem else 0


if __name__ == "__main__":
    raise SystemExit(main())
