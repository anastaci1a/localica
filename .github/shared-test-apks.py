#!/usr/bin/env python3
"""Bind native app/test APKs and AGP metadata to one producer build."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import zipfile

PATHS = (
    'app/build/outputs/apk/debug/app-debug.apk',
    'app/build/outputs/apk/debug/output-metadata.json',
    'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk',
    'app/build/outputs/apk/androidTest/debug/output-metadata.json',
    'app/build/generated/standalone-test-runtime/kotlin-stdlib-standalone.jar',
    'app/build/generated/standalone-test-runtime/binding.txt',
    'app/build/generated/shared-test-apks/test-classes.zip',
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
    # AGP uses compiled class presence to decide whether instrumentation has tests.
    # Preserve those producer bytes rather than allowing an empty shard to pass.
    classes = Path(PATHS[-1])
    classes.parent.mkdir(parents=True, exist_ok=True)
    names = set()
    with zipfile.ZipFile(classes, 'w', zipfile.ZIP_DEFLATED) as archive:
        for folder in (Path('app/build/tmp/kotlin-classes/debugAndroidTest'),
                       Path('app/build/intermediates/javac/debugAndroidTest/compileDebugAndroidTestJavaWithJavac/classes')):
            for file in sorted(folder.rglob('*.class')):
                name = file.as_posix()
                if name in names:
                    raise ValueError('Duplicate compiled class: ' + name)
                names.add(name)
                archive.write(file, name)
    if not names:
        raise ValueError('Missing producer test classes')
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
    checksums = {name: receipt['files'][name] for name in PATHS}
    with zipfile.ZipFile(Path(PATHS[-1])) as archive:
        members = archive.infolist()
        names = [item.filename for item in members]
        if not names or len(names) != len(set(names)) or sum(item.file_size for item in members) > 256*1024*1024:
            raise ValueError('Invalid compiled class archive')
        prefixes = ('app/build/tmp/kotlin-classes/debugAndroidTest/',
                    'app/build/intermediates/javac/debugAndroidTest/compileDebugAndroidTestJavaWithJavac/classes/')
        for item in members:
            name = item.filename
            if not name.startswith(prefixes) or not name.endswith('.class') or '..' in Path(name).parts:
                raise ValueError('Unexpected compiled class path')
            data = archive.read(item)
            target = Path(name)
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(data)
            checksums[name] = hashlib.sha256(data).hexdigest()
    Path('native-test-apks.sha256').write_text(''.join(value+'  '+name+'\n' for name,value in sorted(checksums.items())))
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
