#!/usr/bin/env python3
"""Repeat complete prototype selections, retaining each outcome without retrying failures."""
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import signal
import subprocess


def repeat_count(value):
    if not re.fullmatch(r'[1-9]|[12][0-9]|30', value):
        raise ValueError('PROTOTYPE_REPEAT must be an integer from 1 through 30')
    return int(value)


def main():
    count = repeat_count(os.environ.get('PROTOTYPE_REPEAT', '1'))
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

    save()
    result = 0
    last = None
    for item in summary['iterations']:
        if cancelled:
            result = 128 + cancelled
            break
        folder = archive / f"iteration-{item['iteration']:02d}"
        folder.mkdir()
        item['status'] = 'running'
        save()
        env = dict(os.environ, PROTOTYPE_REPEAT='1')
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
        # AGP must execute again, with no prior XML or stale UP-TO-DATE result.
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
        last = folder
        save()
        print(f"Prototype iteration {item['iteration']}/{count}: {item['status']}", flush=True)
        if result:
            break
    # Keep the existing single-selection receipt layout for delivery reconciliation.
    # The complete, immutable iteration evidence remains alongside it.
    if last and (last / 'prototype-evidence').exists():
        shutil.copytree(last / 'prototype-evidence', root)
    else:
        root.mkdir()
    shutil.move(str(archive), root / 'iterations')
    return result


if __name__ == '__main__':
    raise SystemExit(main())
