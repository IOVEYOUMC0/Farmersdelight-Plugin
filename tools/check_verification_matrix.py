#!/usr/bin/env python3
"""Checks docs/verification.md, read only.

The matrix is only worth having if its claims can be followed: every row has to carry the seven columns, a
conclusion from a fixed set, and — for a row that claims a pass — a reference to something that exists. Test
references name a class, which has to be on disk under src/test/java in this repository; document references
name a path, which is looked up in this repository first and beside it second (see DOC_ROOTS). Rows that are
not verified yet are printed as the list of what is still open, which is the same list a release note would
have to mention.

This script lives in the plugin repository's own tools/ directory and finds that repository from its own path,
so it needs no argument and no particular working directory. Exit status is non-zero when a row is malformed
or a reference cannot be resolved, so it can be a build gate.

Usage: python tools/check_verification_matrix.py [--quiet]
"""

import os
import re
import sys

MATRIX = os.path.join("docs", "verification.md")
CONCLUSIONS = {"通过", "未验证", "不适用"}
COLUMNS = 7
# Test references resolve inside this repository only: a class named in the matrix has to be one of its tests.
TEST_ROOTS = (os.path.join("src", "test", "java"),)
# Document references resolve against these roots, in this order: the plugin repository first, then the
# directory that holds it. Some evidence documents (the scratchpad reports) are written one level above the
# repository, so a `doc:` path that is not inside it is looked up there before it is called missing.
DOC_ROOTS = ("repo", "workspace")


def repo_root():
    """The plugin repository that holds this script: <repo>/tools/check_verification_matrix.py."""
    return os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def doc_roots(root):
    """The absolute roots a `doc:` reference is resolved against, in the order DOC_ROOTS names them."""
    workspace = os.path.dirname(os.path.abspath(root))
    return tuple(workspace if name == "workspace" else root for name in DOC_ROOTS)


def doc_path(root, relative):
    """The first existing file a `doc:` reference names, or None when no root holds it."""
    for base in doc_roots(root):
        candidate = os.path.join(base, relative)
        if os.path.isfile(candidate):
            return candidate
    return None


def read_rows(root):
    path = os.path.join(root, MATRIX)
    if not os.path.isfile(path):
        raise SystemExit("missing matrix: %s" % MATRIX)
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


def check(root):
    problems = []
    unverified = []
    rows = read_rows(root)
    if not rows:
        problems.append("%s has no matrix rows" % MATRIX)
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
                problems.append("line %d: document %s does not exist under %s"
                                % (number, relative, " or ".join(doc_roots(root))))
        elif evidence.startswith("manual:"):
            problems.append("line %d: a manual note is not evidence for a pass; write it down as a document"
                            % number)
        else:
            problems.append("line %d: evidence %r has to start with test: or doc:" % (number, evidence))
    return problems, unverified


def enumerate_rows(rows):
    for number, cells in rows:
        yield number, cells


def main():
    quiet = "--quiet" in sys.argv
    root = repo_root()
    problems, unverified = check(root)
    if not quiet:
        print("== verification matrix: %d row(s)" % len(read_rows(root)))
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
