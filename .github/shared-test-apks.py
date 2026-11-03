#!/usr/bin/env python3
"""Bind native app/test APKs and AGP metadata to one producer build."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil

PATHS = (
    'app/build/outputs/apk/debug/app-debug.apk',
    'app/build/outputs/apk/debug/output-metadata.json',
    'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk',
    'app/build/outputs/apk/androidTest/debug/output-metadata.json',
)


def sha256(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def identity():
    sha, run = os.environ['GITHUB_SHA'], os.environ['GITHUB_RUN_ID']
    if not re.fullmatch('[0-9a-f]{40}', sha) or not re.fullmatch('[1-9][0-9]*', run):
        raise ValueError('Invalid producer identity')
    # A retried shard may consume the original successful build in this run.
    return {'sha': sha, 'run_id': run}


def freeze(output):
    output.mkdir(exist_ok=False)
    files = {}
    for name in PATHS:
        source, target = Path(name), output/name
        if not source.is_file() or source.is_symlink():
            raise ValueError('Missing or linked input: ' + name)
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source, target)
        files[name] = sha256(target)
    receipt = dict(identity(), producer_attempt=os.environ['GITHUB_RUN_ATTEMPT'], files=files)
    manifest = output/'manifest.json'
    manifest.write_text(json.dumps(receipt, sort_keys=True, indent=2)+'\n')
    print(sha256(manifest))


def verify(bundle, digest):
    manifest = bundle/'manifest.json'
    if not re.fullmatch('[0-9a-f]{64}', digest) or sha256(manifest) != digest:
        raise ValueError('Producer manifest digest mismatch')
    receipt = json.loads(manifest.read_text())
    if any(receipt.get(key) != value for key, value in identity().items()):
        raise ValueError('Producer source/run mismatch')
    if set(receipt['files']) != set(PATHS):
        raise ValueError('Unexpected native input inventory')
    for name in PATHS:
        source = bundle/name
        if not source.is_file() or source.is_symlink() or sha256(source) != receipt['files'][name]:
            raise ValueError('Native input digest mismatch: ' + name)
    # Install only after all inputs have verified, preserving AGP output metadata.
    for name in PATHS:
        target = Path(name)
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(bundle/name, target)
    print(json.dumps(receipt, sort_keys=True))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest='command', required=True)
    commands.add_parser('freeze').add_argument('--output', type=Path, required=True)
    check = commands.add_parser('verify')
    check.add_argument('--bundle', type=Path, required=True)
    check.add_argument('--digest', required=True)
    args = parser.parse_args()
    if args.command == 'freeze':
        freeze(args.output)
    else:
        verify(args.bundle, args.digest)
