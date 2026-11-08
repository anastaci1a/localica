#!/usr/bin/env python3
"""Independent prototype repetitions; optional exact methods follow one mandatory gate."""
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import signal
import subprocess

REQUIRED = {'io.github.anastaci1a.localica.' + cls for cls in
            ('SearchImeMotionTest', 'ListSearchTest', 'FrostedSurfaceTest')}


def repeat_count(value):
    if not re.fullmatch(r'[1-9]|[12][0-9]|30', value):
        raise ValueError('PROTOTYPE_REPEAT must be an integer from 1 through 30')
    return int(value)


def main():
    count = repeat_count(os.environ.get('PROTOTYPE_REPEAT', '1'))
    requested_methods = os.environ.get('PROTOTYPE_METHODS', '')
    root = Path('prototype-evidence')
    archive = Path('prototype-iterations')
    if root.exists() or archive.exists():
        raise ValueError('Refusing stale prototype evidence or iterations')
    archive.mkdir()
    summary = {'schema_version': 1, 'sha': os.environ['GITHUB_SHA'],
               'api': os.environ['TEST_API'], 'requested': count,
               'iterations': [{'iteration': i, 'status': 'not-run'} for i in range(1, count + 1)]}
    child = None
    cancelled = None

    def stop(signum, _frame):
        nonlocal cancelled
        cancelled = signum
        if child is not None and child.poll() is None:
            child.send_signal(signum)

    signal.signal(signal.SIGTERM, stop)
    signal.signal(signal.SIGINT, stop)

    def save():
        (archive / 'summary.json').write_text(json.dumps(summary, indent=2) + '\n')

    def execute(folder, item, overrides):
        nonlocal child
        folder.mkdir()
        item['status'] = 'running'
        save()
        env = dict(os.environ, PROTOTYPE_REPEAT='1', **overrides)
        child = subprocess.Popen(['bash', '.github/run-prototype-tests.sh'], env=env)
        if cancelled:
            child.send_signal(cancelled)
        result = child.wait()
        child = None
        if cancelled:
            result = 128 + cancelled
        elif result < 0:
            result = 128 - result
        item.update(status='passed' if result == 0 else 'failed', exit_code=result)
        if root.exists():
            shutil.move(str(root), folder / 'prototype-evidence')
        # Missing prior outputs force a fresh AGP invocation, with no stale XML.
        for path in ('app/build/outputs/androidTest-results',
                     'app/build/outputs/connected_android_test_additional_output',
                     'app/build/reports/androidTests'):
            source = Path(path)
            if source.exists():
                shutil.move(str(source), folder / source.name)
        receipt = folder / 'prototype-evidence/reconciled-tests.json'
        if result == 0 and not receipt.is_file():
            result = 2
            item.update(status='failed', exit_code=result, error='Missing reconciled receipt')
        if receipt.is_file():
            item['receipt_sha256'] = hashlib.sha256(receipt.read_bytes()).hexdigest()
        recovered = folder / 'prototype-evidence/teardown-offline/recovered.txt'
        if recovered.is_file():
            # #675: Gradle lost adb during teardown after a complete pass; the first attempt's
            # XML was reconciled once the same guest answered again. Keep that visible.
            record = json.loads((recovered.parent / 'recovery.json').read_text())
            item['infrastructure_recovered'] = {'issue': 675, 'signature': 'teardown-device-offline',
                                                'recovered_after_seconds': record.get('recovered_after_seconds'),
                                                'first_attempt_exit': recovered.read_text().strip(),
                                                'evidence': str(recovered.parent.relative_to(folder))}
        save()
        return result

    save()
    result = 0
    last = None
    gate = None
    if requested_methods:
        inventory_file = archive / 'requested-methods.json'
        # The same API-gated inventory every iteration's runner records (#657).
        subprocess.run(['python3', '.github/test-evidence/source_methods.py', '--classes',
                        ','.join(sorted(os.environ['AFFECTED_CLASSES'].split(','))), '--api', os.environ['TEST_API'],
                        '--output', str(inventory_file)], check=True)
        complete = json.loads(inventory_file.read_text())
        # Preserve source identities while restricting runtime expectations to requested methods.
        subprocess.run(['python3', '.github/prototype-methods.py', str(inventory_file), requested_methods], check=True)
        candidates = [c for c in complete['classes'] if c['class'] not in REQUIRED]
        if not candidates:
            raise ValueError('Select affected coverage beyond the mandatory regression classes')
        gate_class = min(candidates, key=lambda c: (len(c['methods']), c['class']))['class']
        summary.update(kind='methods', methods=sorted(requested_methods.split(',')),
                       gate={'class': gate_class, 'status': 'not-run'})
        gate = archive / 'mandatory-gate'
        result = execute(gate, summary['gate'], {'PROTOTYPE_METHODS': '',
                         'PROTOTYPE_METHOD_ITERATION': 'false', 'AFFECTED_CLASSES': gate_class})
    if not result:
        for item in summary['iterations']:
            if cancelled:
                result = 128 + cancelled
                break
            folder = archive / f"iteration-{item['iteration']:02d}"
            result = execute(folder, item, {'PROTOTYPE_METHOD_ITERATION':
                             'true' if requested_methods else 'false'})
            last = folder
            recovered = ' (recovered from a #675 teardown adb drop)' if 'infrastructure_recovered' in item else ''
            print(f"Prototype iteration {item['iteration']}/{count}: {item['status']}{recovered}", flush=True)
            if result:
                break
    # Default proofs retain the final full selection. Method proofs retain their
    # mandatory gate at the same path and all exact-method iterations alongside it.
    baseline = gate or last
    if baseline and (baseline / 'prototype-evidence').exists():
        shutil.copytree(baseline / 'prototype-evidence', root)
    else:
        root.mkdir()
    shutil.move(str(archive), root / 'iterations')
    return result


if __name__ == '__main__':
    raise SystemExit(main())
