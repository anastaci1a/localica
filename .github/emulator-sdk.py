#!/usr/bin/env python3
"""Cache and install the checksum-verified stable Linux emulator archive."""
import argparse
import copy
from dataclasses import asdict, dataclass
import hashlib
from http.client import HTTPException
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import stat
import subprocess
import sys
import tempfile
import time
from urllib.request import Request, urlopen
import xml.etree.ElementTree as ET
import zipfile

REPOSITORY = 'https://dl.google.com/android/repository/'
CATALOG = REPOSITORY + 'repository2-1.xml'


class ArchiveError(ValueError):
    pass


@dataclass(frozen=True)
class Package:
    revision: str
    url: str
    sha1: str
    size: int
    metadata: str = ''

    @property
    def key(self):
        return f'android-emulator-archive-v1-linux-x64-r{self.revision}-{self.sha1}'


def child(element, name):
    return next((e for e in element if e.tag.rsplit('}', 1)[-1] == name), None)


def value(element, name, default=''):
    found = child(element, name)
    return found.text.strip() if found is not None and found.text else default


def local_metadata(revision, license_element=None):
    common = 'http://schemas.android.com/repository/android/common/02'
    generic = 'http://schemas.android.com/repository/android/generic/02'
    xsi = 'http://www.w3.org/2001/XMLSchema-instance'
    ET.register_namespace('common', common)
    ET.register_namespace('xsi', xsi)
    root = ET.Element('{' + common + '}repository', {'xmlns:generic': generic})
    if license_element is not None:
        root.append(copy.deepcopy(license_element))
    package = ET.SubElement(root, 'localPackage', {'path': 'emulator', 'obsolete': 'false'})
    ET.SubElement(package, 'type-details', {'{' + xsi + '}type': 'generic:genericDetailsType'})
    version = ET.SubElement(package, 'revision')
    for name, part in zip(('major', 'minor', 'micro'), revision.split('.')):
        ET.SubElement(version, name).text = part
    ET.SubElement(package, 'display-name').text = 'Android Emulator'
    if license_element is not None:
        ET.SubElement(package, 'uses-license', {'ref': license_element.get('id')})
    return ET.tostring(root, encoding='unicode')


def available_package(xml):
    candidates = []
    root = ET.fromstring(xml)
    for package in root.iter():
        if package.tag.rsplit('}', 1)[-1] != 'remotePackage' or package.get('path') != 'emulator':
            continue
        channel = child(package, 'channelRef')
        revision = child(package, 'revision')
        if channel is None or channel.get('ref') != 'channel-0' or revision is None:
            continue
        if int(value(revision, 'preview', '0')) != 0:
            continue
        version = tuple(int(value(revision, part, '0')) for part in ('major', 'minor', 'micro'))
        if version[0] <= 0 or any(part < 0 for part in version):
            raise ArchiveError('Invalid stable emulator revision')
        archives = child(package, 'archives')
        for archive in archives if archives is not None else []:
            if value(archive, 'host-os') != 'linux' or value(archive, 'host-bits', '64') != '64':
                continue
            if value(archive, 'host-arch', 'x86_64') != 'x86_64':
                continue
            complete = child(archive, 'complete')
            url = value(complete, 'url') if complete is not None else ''
            checksum = value(complete, 'checksum').lower() if complete is not None else ''
            size = value(complete, 'size') if complete is not None else ''
            if not re.fullmatch(r'emulator[-_]linux_x64-\d+\.zip', url):
                raise ArchiveError('Unexpected Linux emulator archive URL')
            if not re.fullmatch(r'[0-9a-f]{40}', checksum) or not size.isdecimal() or int(size) <= 0:
                raise ArchiveError('Missing emulator archive checksum or size')
            use = child(package, 'uses-license')
            license_element = None
            if use is not None:
                license_element = next((e for e in root if e.tag.rsplit('}', 1)[-1] == 'license'
                                        and e.get('id') == use.get('ref')), None)
                if license_element is None:
                    raise ArchiveError('The emulator SDK license reference has no repository definition')
            revision_text = '.'.join(map(str, version))
            metadata = local_metadata(revision_text, license_element)
            candidates.append((version, Package(revision_text, REPOSITORY + url, checksum, int(size), metadata)))
    if not candidates:
        raise ArchiveError('No stable Linux x86_64 emulator archive in the SDK repository')
    return max(candidates, key=lambda item: item[0])[1]


def validate_archive(path, package):
    digest = hashlib.sha1()
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(block)
    if path.stat().st_size != package.size or digest.hexdigest() != package.sha1:
        raise ArchiveError(f'Emulator archive mismatch: expected {package.size} bytes/{package.sha1}, '
                           f'observed {path.stat().st_size} bytes/{digest.hexdigest()}')
    with zipfile.ZipFile(path) as archive:
        for entry in archive.infolist():
            parts = PurePosixPath(entry.filename).parts
            if (not parts or parts[0] != 'emulator' or '\\' in entry.filename or
                    '..' in parts or stat.S_ISLNK(entry.external_attr >> 16)):
                raise ArchiveError('Unsafe path in emulator archive: ' + entry.filename)
        try:
            props = archive.read('emulator/source.properties').decode('utf-8')
            binary = archive.getinfo('emulator/emulator')
        except KeyError as failure:
            raise ArchiveError('Incomplete emulator package') from failure
        revision = re.search(r'^Pkg\.Revision\s*=\s*([\d.]+)\s*$', props, re.M)
        if revision is None or revision.group(1) != package.revision or binary.file_size == 0:
            raise ArchiveError('Emulator package revision differs from the resolved cache key')


def download(url, destination, limit, fresh):
    headers = {'Cache-Control': 'no-cache'} if fresh else {}
    with urlopen(Request(url, headers=headers), timeout=60) as response, destination.open('wb') as stream:
        total = 0
        while block := response.read(1024 * 1024):
            total += len(block)
            if total > limit:
                raise ArchiveError('Emulator download exceeds its declared archive size')
            stream.write(block)


def ensure_archive(package, path, fetch=download):
    path.parent.mkdir(parents=True, exist_ok=True)
    if path.exists():
        try:
            validate_archive(path, package)
            return {'cache_valid': True, 'download_attempts': 0}
        except (ArchiveError, OSError, zipfile.BadZipFile) as failure:
            print('Discarding invalid cached emulator archive: ' + str(failure), file=sys.stderr)
            path.unlink()
    partial = path.with_suffix('.partial')
    for attempt in (1, 2):
        try:
            fetch(package.url, partial, package.size, attempt == 2)
            validate_archive(partial, package)
            partial.replace(path)
            return {'cache_valid': False, 'download_attempts': attempt}
        except (ArchiveError, OSError, HTTPException, zipfile.BadZipFile) as failure:
            print(f'Emulator archive attempt {attempt}/2 failed: {failure}', file=sys.stderr)
            if attempt == 2:
                raise ArchiveError('No verified emulator archive after two download attempts') from failure
        finally:
            partial.unlink(missing_ok=True)


def installed_revision(sdk):
    manager = sdk / 'cmdline-tools/latest/bin/sdkmanager'
    listing = subprocess.check_output([str(manager), '--sdk_root=' + str(sdk), '--list_installed'],
                                      text=True, timeout=120)
    for line in listing.splitlines():
        columns = [column.strip() for column in line.split('|')]
        if len(columns) >= 2 and columns[0] == 'emulator':
            return columns[1]
    raise ArchiveError('sdkmanager did not recognize the installed emulator package')


def install_archive(package, path, sdk, check=installed_revision):
    validate_archive(path, package)
    sdk.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='.localica-emulator-', dir=sdk) as temporary:
        stage = Path(temporary)
        with zipfile.ZipFile(path) as archive:
            archive.extractall(stage)
            for entry in archive.infolist():
                extracted = stage / entry.filename
                executable = entry.external_attr >> 16 & 0o111
                extracted.chmod(0o755 if entry.is_dir() or executable else 0o644)
        # SDK archives omit the installed-package registration that sdkmanager writes.
        # Preserve the official license and exact revision so its later install is a no-op.
        (stage / 'emulator/package.xml').write_text(package.metadata or local_metadata(package.revision))
        target = sdk / 'emulator'
        if target.is_symlink():
            target.unlink()
        elif target.exists():
            shutil.rmtree(target)
        shutil.move(str(stage / 'emulator'), target)
    revision = check(sdk)
    if revision != package.revision:
        raise ArchiveError('sdkmanager revision differs from the verified emulator archive')
    return revision


def outputs(values):
    if os.environ.get('GITHUB_OUTPUT'):
        with open(os.environ['GITHUB_OUTPUT'], 'a') as stream:
            for key, value in values.items():
                stream.write(f'{key}={value}\n')
    print(json.dumps(values, indent=2))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('mode', choices=['resolve', 'prepare'])
    args = parser.parse_args()
    root = Path(os.environ['RUNNER_TEMP']) / 'localica-emulator-sdk'
    root.mkdir(parents=True, exist_ok=True)
    state = root / 'state.json'
    if args.mode == 'resolve':
        for attempt in (1, 2):
            try:
                request = Request(CATALOG, headers={'Cache-Control': 'no-cache'} if attempt == 2 else {})
                with urlopen(request, timeout=60) as response:
                    package = available_package(response.read())
                break
            except (OSError, HTTPException, ET.ParseError, ArchiveError):
                if attempt == 2:
                    raise
                time.sleep(1)
        path = root / 'archives' / f'emulator-{package.revision}-{package.sha1}.zip'
        state.write_text(json.dumps({'package': asdict(package), 'archive': str(path)}))
        outputs({'path': str(path), 'key': package.key, 'revision': package.revision, 'sha1': package.sha1})
    else:
        saved = json.loads(state.read_text())
        package = Package(**saved['package'])
        path = Path(saved['archive'])
        receipt = ensure_archive(package, path)
        receipt.update(revision=install_archive(package, path, Path(os.environ['ANDROID_HOME'])),
                       archive_sha1=package.sha1, archive_size=package.size, url=package.url)
        (root / 'receipt.json').write_text(json.dumps(receipt, indent=2) + '\n')
        outputs(receipt)
        if os.environ.get('GITHUB_STEP_SUMMARY'):
            with open(os.environ['GITHUB_STEP_SUMMARY'], 'a') as stream:
                stream.write(f'Verified Android Emulator {package.revision}; archive SHA1 `{package.sha1}`; '
                             f'cache valid: {receipt["cache_valid"]}; downloads: {receipt["download_attempts"]}.\n')


if __name__ == '__main__':
    main()
