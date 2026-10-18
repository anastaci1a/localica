#!/usr/bin/env python3
"""Compare committed inputs; equivalence is not evidence that checks passed."""

import argparse
import hashlib
import json
import subprocess
import sys
from pathlib import Path


NOTES = b".github/RELEASE_NOTES.md"
REQUIRED = (b"settings.gradle.kts", b"app/build.gradle.kts", b".github/workflows/android.yml")


def git(repo, *args):
    return subprocess.run(
        ["git", "--no-replace-objects", "-C", str(repo), *args], check=True, stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    ).stdout


def committed_inputs(repo, revision):
    commit = git(repo, "rev-parse", "--verify", "--end-of-options", revision + "^{commit}").strip().decode("ascii")
    tree = git(repo, "rev-parse", commit + "^{tree}").strip().decode("ascii")
    entries = {}
    retained = []
    for record in git(repo, "ls-tree", "-r", "-z", "--full-tree", tree).split(b"\0"):
        if not record:
            continue
        header, path = record.split(b"\t", 1)
        entries[path] = header
        if path != NOTES:
            retained.append(record + b"\0")
    # The only exclusion is a regular, non-executable notes file. Do not silently
    # exclude a symlink, executable or a directory masquerading as that file.
    for path in (*REQUIRED, NOTES):
        if entries.get(path, b"").split(b" ")[:2] != [b"100644", b"blob"]:
            raise ValueError("Missing regular input: " + path.decode("ascii"))
    digest = hashlib.sha256(b"localica-verification-inputs-v1\0" + b"".join(retained)).hexdigest()
    return {"commit": commit, "tree": tree, "inputs_sha256": digest}, entries


def compare(repo, producer, release):
    original, before = committed_inputs(repo, producer)
    candidate, after = committed_inputs(repo, release)
    changed = sorted(path for path in before.keys() | after.keys()
                     if path != NOTES and before.get(path) != after.get(path))
    return {
        "schema": 1,
        "equivalent": not changed,
        "producer": original,
        "release": candidate,
        "excluded_paths": [NOTES.decode("ascii")],
        "changed_inputs": [path.decode("utf-8", "surrogateescape") for path in changed],
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("producer", help="Commit associated with the original verification")
    parser.add_argument("release", help="Proposed release commit")
    parser.add_argument("--repo", type=Path, default=Path.cwd())
    args = parser.parse_args()
    try:
        result = compare(args.repo, args.producer, args.release)
    except (subprocess.CalledProcessError, ValueError) as error:
        print("Cannot establish committed input equivalence: " + str(error), file=sys.stderr)
        return 2
    print(json.dumps(result, sort_keys=True))
    return 0 if result["equivalent"] else 1


if __name__ == "__main__":
    sys.exit(main())
