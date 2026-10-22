#!/usr/bin/env python3
"""Checks a verification matrix, read only.

The matrix is only worth having if its claims can be followed: every row has to carry the seven columns, a
conclusion from a fixed set, and — for a row that claims a pass — a reference to something that exists. Test
references name a class, which has to be on disk under src/test/java in this repository; document references
name a path inside this repository, so the evidence is versioned with the code it describes. Rows that are
not verified yet are printed as the list of what is still open, which is the same list a release note would
have to mention.

The matrix itself is a verification record, and records live outside this repository. The check therefore reads
docs/verification.md when it exists and reports nothing to check otherwise; a matrix kept elsewhere is named
with --dir, whose `test:` and `doc:` references still resolve against this repository.

Usage: python tools/check_verification_matrix.py [--quiet] [--dir DIR]
"""

import os
import re
import sys

MATRIX = os.path.join("docs", "verification.md")
CONCLUSIONS = {"通过", "未验证", "不适用"}
COLUMNS = 7
# Test references resolve inside this repository only: a class named in the matrix has to be one of its tests.
TEST_ROOTS = (os.path.join("src", "test", "java"),)



def repo_root():
    """The plugin repository that holds this script: <repo>/tools/check_verification_matrix.py."""
    return os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def doc_path(root, relative):
    """The file a `doc:` reference names, or None when it is not a file inside this repository."""
    base = os.path.normpath(root)
    candidate = os.path.normpath(os.path.join(base, relative))
    if os.path.commonpath([base, candidate]) != base:
        return None
    return candidate if os.path.isfile(candidate) else None


def read_rows(path):
    rows = []
    for number, line in enumerate(open(path, encoding="utf-8"), start=1):
        stripped = line.strip()
        if not stripped.startswith("|"):
            continue
        cells = [cell.strip() for cell in stripped.strip("|").split("|")]
        if len(cells) != COLUMNS:
            continue
        if cells[0] in ("平台",) or set(cells[0]) <= {"-", " "}:
            continue
        rows.append((number, cells))
    return rows


def test_path(root, class_name):
    relative = os.path.join(*class_name.split(".")) + ".java"
    for test_root in TEST_ROOTS:
        candidate = os.path.join(test_root, relative)
        if os.path.isfile(os.path.join(root, candidate)):
            return candidate
    return None


def check(root, rows):
    problems = []
    unverified = []
    if not rows:
        problems.append("the matrix has no rows")
    for number, cells in enumerate_rows(rows):
        platform, version, ce, item, conclusion, evidence, date = cells
        if not (platform and version and ce and item and date):
            problems.append("line %d: a row is missing a platform, version, CE version, item or date" % number)
        if conclusion not in CONCLUSIONS:
            problems.append("line %d: conclusion %r is not one of %s"
                            % (number, conclusion, "/".join(sorted(CONCLUSIONS))))
            continue
        if conclusion == "未验证":
            unverified.append((platform, version, ce, item))
            continue
        if conclusion == "不适用":
            continue
        if not evidence or evidence == "-":
            problems.append("line %d: a passing row has to name its evidence" % number)
        elif evidence.startswith("test:"):
            class_name = evidence[len("test:"):]
            if test_path(root, class_name) is None:
                problems.append("line %d: test %s does not exist under %s"
                                % (number, class_name, " or ".join(TEST_ROOTS)))
        elif evidence.startswith("doc:"):
            relative = evidence[len("doc:"):]
            if doc_path(root, relative) is None:
                problems.append("line %d: document %s does not exist in this repository"
                                % (number, relative))
        elif evidence.startswith("manual:"):
            problems.append("line %d: a manual note is not evidence for a pass; write it down as a document"
                            % number)
        else:
            problems.append("line %d: evidence %r has to start with test: or doc:" % (number, evidence))
    return problems, unverified


def enumerate_rows(rows):
    for number, cells in rows:
        yield number, cells


def parse_arguments(arguments):
    """(quiet, directory): --dir DIR and --dir=DIR name a matrix kept outside this repository."""
    quiet = False
    directory = None
    index = 0
    while index < len(arguments):
        argument = arguments[index]
        if argument == "--quiet":
            quiet = True
        elif argument == "--dir":
            index += 1
            if index >= len(arguments):
                raise SystemExit("--dir needs a directory")
            directory = arguments[index]
        elif argument.startswith("--dir="):
            directory = argument[len("--dir="):]
        index += 1
    return quiet, directory


def main():
    quiet, directory = parse_arguments(sys.argv[1:])
    root = repo_root()
    matrix_path = os.path.join(directory, "verification.md") if directory else os.path.join(root, MATRIX)
    if not os.path.isfile(matrix_path):
        if not quiet:
            print("== verification matrix: none (records live outside this repository; pass --dir)")
        print("problems: 0")
        return
    rows = read_rows(matrix_path)
    problems, unverified = check(root, rows)
    if not quiet:
        print("== verification matrix: %d row(s)" % len(rows))
        if unverified:
            print("== unverified (%d):" % len(unverified))
            for platform, version, ce, item in unverified:
                print("   - %s %s / CE %s: %s" % (platform, version, ce, item))
        else:
            print("== unverified: none")
    for problem in problems:
        print("problem: %s" % problem)
    print("problems: %d" % len(problems))
    if problems:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
