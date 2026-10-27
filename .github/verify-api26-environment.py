#!/usr/bin/env python3
"""Read-only check of the action-installed emulator/image before native tests."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys


def properties(path):
    entries = {}
    for line in path.read_text().splitlines():
        line = line.strip()
        if line and not line.startswith('#') and '=' in line:
            key, value = line.split('=', 1)
            if key in entries and entries[key] != value:
                raise ValueError('Conflicting duplicate property: '+key)
            entries[key] = value
    return entries


def verify(root, sdk, avd):
    originals = {}
    for name, path in [('emulator-source.properties', sdk/'emulator/source.properties'),
                       ('image-source.properties', sdk/'system-images/android-26/google_apis/x86_64/source.properties'),
                       ('avd-config.ini', avd/'test.avd/config.ini')]:
        if not path.is_file() or path.is_symlink():
            raise ValueError('Missing/nonordinary environment original: '+str(path))
        content = path.read_bytes()
        with (root/name).open('xb') as handle:
            handle.write(content)
        originals[name] = hashlib.sha256(content).hexdigest()
    emu = properties(root/'emulator-source.properties')
    img = properties(root/'image-source.properties')
    config = properties(root/'avd-config.ini')
    # Duplicate AVD keys are possible because upstream appends hw.cpu.ncore;
    # identical duplicates are accepted; conflicting values fail closed.
    if (emu.get('Pkg.Revision'), emu.get('Pkg.BuildId')) != ('37.2.12', '16428233'):
        raise ValueError('Wrong emulator build')
    if any(img.get(k) != v for k, v in {'Pkg.Revision':'16', 'AndroidVersion.ApiLevel':'26', 'SystemImage.Abi':'x86_64', 'SystemImage.TagId':'google_apis'}.items()):
        raise ValueError('Wrong API26 image revision/identity')
    if config.get('hw.cpu.ncore') != '2' or config.get('hw.device.name') != 'pixel_2':
        raise ValueError('Wrong AVD core/profile settings')
    binary = sdk/'emulator/emulator'
    version = subprocess.run([str(binary), '-version'], capture_output=True, timeout=15)
    content = version.stdout+version.stderr
    with (root/'emulator-version.txt').open('xb') as handle:
        handle.write(content)
    originals['emulator-version.txt'] = hashlib.sha256(content).hexdigest()
    if version.returncode or not re.search(rb'Android emulator version 37\.2\.12\.0 \(build_id 16428233\)', content):
        raise ValueError('Executed emulator version mismatch')
    value = dict(schema_version=1, valid=True, emulator_version='37.2.12.0', emulator_build='16428233',
        image_revision='16', binary_sha256=hashlib.sha256(binary.read_bytes()).hexdigest(), original_sha256=originals,
        baseline_options=['-no-window','-gpu','swiftshader_indirect','-no-snapshot','-noaudio','-no-boot-anim','-memory','3072'],
        logging_only_options=['-verbose','-show-kernel'], baseline_run='40593355374', source_sha='77314e80885f4649d58482991ac3e2f9898b7d8a',
        limitation='Same source rebuilt with disposable debug signing; not byte-identical original APKs. Added logging may affect timing.')
    with (root/'pinned-environment.json').open('x') as handle:
        json.dump(value, handle, indent=2)
        handle.write('\n')
    return 0


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--evidence', required=True)
    args = parser.parse_args()
    root = Path(args.evidence).absolute()
    if not root.is_dir() or any(p.is_symlink() for p in [root,*root.parents]):
        raise ValueError('Evidence root absent/symlinked')
    return verify(root, Path(os.environ['ANDROID_HOME']), Path(os.environ['ANDROID_AVD_HOME']))


if __name__ == '__main__':
    try:
        sys.exit(main())
    except (OSError, ValueError, KeyError, subprocess.SubprocessError) as error:
        print(type(error).__name__+': '+str(error), file=sys.stderr)
        sys.exit(3)
