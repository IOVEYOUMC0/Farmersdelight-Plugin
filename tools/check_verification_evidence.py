#!/usr/bin/env python3
"""Checks verification round manifests, read only.

A round manifest is the machine-readable half of a verification note: which binaries were exercised (their
sha256), on which environments (server build, Java, CraftEngine build), what each check reported (PASS/SKIP/
FAIL) and what the round does not cover (`scope` + `limitations`). This check enforces that shape so rounds stay
comparable, but it adds no facts of its own: a round without a manifest is simply not checked.

The records are operational, not source: they stay in the workspace's verification-records/ directory and the
repository only tracks this format and the check. A record inside docs/verification/ is looked at while the
repository is checked; records elsewhere are opted into with --dir.

Usage: python tools/check_verification_evidence.py [--quiet] [--dir DIR ...]
"""

import datetime
import json
import os
import re
import sys

EVIDENCE = os.path.join("docs", "verification")
MANIFEST = "manifest.json"
STATUSES = {"PASS", "SKIP", "FAIL"}
DATE = re.compile(r"^\d{4}-\d{2}-\d{2}$")
SHA256 = re.compile(r"^[0-9a-f]{64}$")


def repo_root():
    """The plugin repository that holds this script: <repo>/tools/check_verification_evidence.py."""
    return os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def manifests(directory):
    """Every round manifest under one directory tree, as (path relative to it, absolute path) pairs."""
    if not os.path.isdir(directory):
        return []
    found = []
    for folder, _, files in os.walk(directory):
        if MANIFEST in files:
            absolute = os.path.join(folder, MANIFEST)
            found.append((os.path.relpath(absolute, directory).replace(os.sep, "/"), absolute))
    return sorted(found)


def non_empty_text(value):
    return isinstance(value, str) and bool(value.strip())


def check_date(data, problems):
    date = data.get("date")
    if not non_empty_text(date):
        problems.append("`date` is missing")
    elif not DATE.match(date):
        problems.append("`date` has to be YYYY-MM-DD, got %r" % date)
    else:
        try:
            datetime.date.fromisoformat(date)
        except ValueError:
            problems.append("`date` is not a real date: %r" % date)


def check_limitations(data, problems):
    limitations = data.get("limitations")
    if not isinstance(limitations, list) or not limitations:
        problems.append("`limitations` has to list what the round does not cover")
        return
    for index, limitation in enumerate(limitations):
        if not non_empty_text(limitation):
            problems.append("`limitations[%d]` is empty" % index)


def check_artifacts(data, problems):
    artifacts = data.get("artifacts")
    if not isinstance(artifacts, dict) or not artifacts:
        problems.append("`artifacts` has to name at least one exercised binary")
        return
    for name, entry in artifacts.items():
        if not non_empty_text(name):
            problems.append("`artifacts` has an empty name")
            continue
        if not isinstance(entry, dict):
            problems.append("`artifacts.%s` has to be an object" % name)
            continue
        if not isinstance(entry.get("sha256"), str) or not SHA256.match(entry["sha256"]):
            problems.append("`artifacts.%s.sha256` has to be 64 lowercase hex characters" % name)
        size = entry.get("bytes")
        if not isinstance(size, int) or isinstance(size, bool) or size <= 0:
            problems.append("`artifacts.%s.bytes` has to be a positive integer" % name)


def check_runs(data, problems):
    runs = data.get("runs")
    if not isinstance(runs, list) or not runs:
        problems.append("`runs` has to name at least one environment")
        return
    for index, run in enumerate(runs):
        label = "`runs[%d]`" % index
        if not isinstance(run, dict):
            problems.append(label + " has to be an object")
            continue
        for key in ("environment", "java", "craft_engine"):
            if not non_empty_text(run.get(key)):
                problems.append("%s.%s is missing" % (label, key))
        core_build = run.get("core_build")
        if core_build is not None and not non_empty_text(core_build):
            problems.append(label + ".core_build is empty")
        digest = run.get("craft_engine_sha256")
        if digest is not None and not (isinstance(digest, str) and SHA256.match(digest)):
            problems.append(label + ".craft_engine_sha256 has to be 64 lowercase hex characters")
        check_checks(label, run.get("checks"), problems)


def check_checks(label, checks, problems):
    if not isinstance(checks, dict) or not checks:
        problems.append(label + ".checks has to name at least one check")
        return
    for name, result in checks.items():
        if not non_empty_text(name):
            problems.append(label + ".checks has an empty name")
            continue
        where = "%s.checks.%s" % (label, name)
        if not isinstance(result, dict):
            problems.append(where + " has to be an object")
            continue
        if result.get("status") not in STATUSES:
            problems.append(where + ".status has to be one of " + ", ".join(sorted(STATUSES)))
        if not non_empty_text(result.get("detail")):
            problems.append(where + ".detail is missing")


def check_manifest(path):
    """The shape problems of one manifest; empty when the file is a valid record."""
    try:
        with open(path, encoding="utf-8") as handle:
            data = json.load(handle)
    except (OSError, ValueError) as unreadable:
        return ["not readable as JSON: %s" % unreadable]
    if not isinstance(data, dict):
        return ["the manifest has to be a JSON object"]
    problems = []
    check_date(data, problems)
    if not non_empty_text(data.get("scope")):
        problems.append("`scope` is missing: a round has to say what it covers")
    check_limitations(data, problems)
    check_artifacts(data, problems)
    check_runs(data, problems)
    return problems


def parse_arguments(arguments):
    """(quiet, extra directories): --dir DIR and --dir=DIR add a record directory outside the repository."""
    quiet = False
    directories = []
    index = 0
    while index < len(arguments):
        argument = arguments[index]
        if argument == "--quiet":
            quiet = True
        elif argument == "--dir":
            index += 1
            if index >= len(arguments):
                raise SystemExit("--dir needs a directory")
            directories.append(os.path.abspath(arguments[index]))
        elif argument.startswith("--dir="):
            directories.append(os.path.abspath(argument[len("--dir="):]))
        index += 1
    return quiet, directories


def main():
    quiet, extra_directories = parse_arguments(sys.argv[1:])
    root = repo_root()
    targets = [(os.path.join(root, EVIDENCE), "repository")]
    targets.extend((directory, "external") for directory in extra_directories)
    found = []
    for directory, scope in targets:
        for relative, absolute in manifests(directory):
            found.append((relative if scope == "repository" else absolute, absolute))
    problems = []
    for label, absolute in found:
        for problem in check_manifest(absolute):
            problems.append("%s: %s" % (label, problem))
    if not quiet:
        print("== verification evidence: %d round manifest(s)" % len(found))
    for problem in problems:
        print("problem: %s" % problem)
    print("problems: %d" % len(problems))
    if problems:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
