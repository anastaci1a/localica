#!/usr/bin/env python3
"""Archive finalized workflow SHA; apply only the reviewed private compatibility overlay."""
import difflib
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tarfile
from instrumentation_runner import from_source

root = Path(os.environ['GITHUB_WORKSPACE']).resolve()
sha = os.environ['GITHUB_SHA']
assert subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=root, text=True).strip() == sha
out = Path(os.environ['RUNNER_TEMP']) / 'shuffle-compatibility'
out.mkdir(exist_ok=False)
reports = out / 'reports'
reports.mkdir()
archive = out / 'source.tar'
subprocess.run(['git', 'archive', '--format=tar', '-o', str(archive), sha], cwd=root, check=True)
source = out / 'source'
source.mkdir()
with tarfile.open(archive) as tar:
    tar.extractall(source, filter='data')
archive.unlink()
base = 'app/src/main/java/io/github/anastaci1a/localica/'
journey = base + 'queue/QueueJourney.kt'
records = base + 'queue/QueueJourneyRecords.kt'
test = 'app/src/androidTest/java/io/github/anastaci1a/localica/LegacyJourneyCompatibilityTest.kt'
build = 'app/build.gradle.kts'
protected = [base + name for name in ['queue/QueueDatabase.kt', 'queue/QueueController.kt',
    'editing/MetadataRefreshJournal.kt', 'LocalicaApplication.kt']]
protected += ['app/schemas/io.github.anastaci1a.localica.queue.QueueDatabase/7.json']

def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()

def files(tree):
    return {str(p.relative_to(tree)): digest(p) for p in tree.rglob('*') if p.is_file()}

baseline = files(source)
provenance = {'source_sha': sha, 'legacy_source_sha': '755f2ea0f4f2ff8983a32d484ffcd113712c4cac',
              'instrumentation_runner': from_source((source / build).read_text()),
              'fixtures': files(source / '.github/compatibility'), 'variants': {}}
for index, phase in enumerate(('seed', 'bridge', 'verify')):
    tree = out / phase
    # Copy the immutable archive, never a working checkout or another built variant.
    import shutil
    shutil.copytree(source, tree)
    code = 1900000001 + index
    assert 0 < code < 2100000000
    content = (tree / build).read_text()
    content, count = re.subn(r'(?m)^        versionCode = [0-9]+$', f'        versionCode = {code}', content)
    assert count == 1
    (tree / build).write_text(content)
    allowed = {build}
    if phase == 'bridge':
        (tree / journey).write_bytes((source / '.github/compatibility/QueueJourney.kt').read_bytes())
        (tree / test).write_bytes((source / '.github/compatibility/LegacyJourneyCompatibilityTest.kt').read_bytes())
        content = (tree / records).read_text()
        old = 'modes.reshuffle, orderedTraversalId, orderedAnchorToken)'
        assert content.count(old) == 1
        content = content.replace(old, 'modes.reshuffle, null, null)')
        old = 'JourneyModes(shuffle, loop, reshuffle), orderedTraversalId, orderedAnchorToken)'
        assert content.count(old) == 1
        content = content.replace(old, 'JourneyModes(shuffle, loop, reshuffle))')
        (tree / records).write_text(content)
        allowed |= {journey, records, test}
    current = files(tree)
    changed = {p for p in baseline.keys() | current.keys() if baseline.get(p) != current.get(p)}
    assert changed == allowed, (phase, changed)
    for path in protected:
        assert current[path] == baseline[path], (phase, path)
    diff = ''.join(''.join(difflib.unified_diff(
        (source / path).read_text().splitlines(True) if path in baseline else [],
        (tree / path).read_text().splitlines(True), fromfile='source/' + path, tofile=phase + '/' + path))
        for path in sorted(changed))
    (reports / f'{phase}-source.diff').write_text(diff)
    provenance['variants'][phase] = {'version_code': code, 'changed': sorted(changed),
        'protected_sha256': {p: current[p] for p in protected},
        'changed_sha256': {p: current[p] for p in changed}}
assert files(source) == baseline
(reports / 'provenance.json').write_text(json.dumps(provenance, indent=2) + '\n')
