#!/usr/bin/env python3
"""Observe disposable hosted emulator loss without treating an empty UTP failure as an assertion."""
import argparse
import json
import os
import re
from pathlib import Path
import signal
import subprocess
import threading
import time
import xml.etree.ElementTree as ET


def command(args, timeout=4):
    try:
        result = subprocess.run(args, capture_output=True, text=True, timeout=timeout)
        return {'exit_code': result.returncode, 'stdout': result.stdout[-32768:], 'stderr': result.stderr[-8192:]}
    except (OSError, subprocess.TimeoutExpired) as error:
        return {'exit_code': None, 'error': type(error).__name__}


def emulator_processes():
    found = []
    for proc in Path('/proc').glob('[0-9]*'):
        try:
            argv = (proc / 'cmdline').read_bytes().split(b'\0')
            executable = Path(os.fsdecode(argv[0])).name
            if executable.startswith('qemu-system-') or executable in ('emulator', 'emulator64-x86'):
                found.append({'pid': int(proc.name), 'argv': [os.fsdecode(arg) for arg in argv if arg]})
        except (OSError, IndexError):
            pass
    return found


def classify(result, samples, results, iterations=None):
    """Any substantive assertion/error overrides host loss. Unknown/malformed evidence fails closed."""
    if result == 0:
        return None
    failures = []
    files = list(results.rglob('*.xml')) if results.exists() else []
    if iterations and iterations.exists():
        files += list(iterations.rglob('*.xml'))
    for path in files:
        try:
            tree = ET.parse(path)
            for node in tree.iter():
                if node.tag in ('failure', 'error'):
                    if (node.text or '').strip() or any(str(v).strip() for v in node.attrib.values()) or len(node):
                        return None
                    failures.append(str(path))
        except (OSError, ET.ParseError):
            return None
    if not files or not failures:
        return None
    log_roots = [results] + ([iterations] if iterations else [])
    for log_root in log_roots:
        if not log_root.exists():
            continue
        for log in log_root.rglob('logcat-*.txt'):
            try:
                text = log.read_text(errors='replace')
            except OSError:
                return None
            if re.search(r'TestRunner: (?:failed|failure):|AssertionError|FATAL EXCEPTION', text):
                return None
    # Require two independent samples of a vanished process AND absent adb transport.
    # A shell timeout on a busy emulator is never sufficient.
    lost = [s for s in samples if not s['processes'] and s['adb']['exit_code'] not in (None, 0)]
    if len(lost) < 2 or not any(s['processes'] and s['adb']['exit_code'] == 0 for s in samples):
        return None
    return {'schema_version': 1, 'classification': 'infrastructure', 'reason': 'emulator-process-lost',
            'issue': 738, 'exit_code': result, 'empty_utp_failures': failures,
            'first_loss_seconds': lost[0]['elapsed_seconds'], 'lost_samples': len(lost)}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--prepare-restart', action='store_true')
    args = parser.parse_args()
    root = Path('ci-infrastructure')
    if args.prepare_restart:
        receipt = json.loads((root / 'emulator.json').read_text())
        if receipt.get('classification') != 'infrastructure' or receipt.get('issue') != 738:
            raise SystemExit('Refusing restart without an emulator-loss classification')
        archive = Path('prototype-infrastructure/attempt-1')
        archive.mkdir(parents=True, exist_ok=False)
        for name in ('ci-infrastructure', 'prototype-evidence', 'prototype-iterations',
                     'app/build/outputs/androidTest-results',
                     'app/build/outputs/connected_android_test_additional_output',
                     'app/build/reports/androidTests'):
            path = Path(name)
            if path.exists():
                target = archive / name
                target.parent.mkdir(parents=True, exist_ok=True)
                path.rename(target)
        return
    root.mkdir(exist_ok=True)
    samples = []
    stop = threading.Event()
    serial = os.environ.get('ANDROID_SERIAL', 'emulator-5554')
    started = time.monotonic()
    def sample():
        item = {'elapsed_seconds': round(time.monotonic() - started, 3),
                'processes': emulator_processes(),
                'adb': command(['adb', '-s', serial, 'get-state']),
                'boot_id': command(['adb', '-s', serial, 'shell', 'cat', '/proc/sys/kernel/random/boot_id']),
                'meminfo': Path('/proc/meminfo').read_text()}
        samples.append(item)
        with (root / 'health.jsonl').open('a') as stream:
            stream.write(json.dumps(item) + '\n')
    sample()
    def watch():
        while not stop.wait(5):
            sample()
    thread = threading.Thread(target=watch)
    thread.start()
    child = subprocess.Popen(['bash', '.github/run-prototype-tests.sh'])
    cancelled = False
    def cancel(signum, _frame):
        nonlocal cancelled
        cancelled = True
        child.send_signal(signum)
    signal.signal(signal.SIGTERM, cancel)
    signal.signal(signal.SIGINT, cancel)
    try:
        result = child.wait()
    finally:
        stop.set()
        thread.join()
    sample()
    if not cancelled:
        receipt = classify(result, samples, Path('app/build/outputs/androidTest-results'), Path('prototype-iterations'))
        if receipt:
            receipt.update({name.lower().removeprefix('github_'): os.environ.get(name, '')
                            for name in ('GITHUB_SHA', 'GITHUB_RUN_ID', 'GITHUB_RUN_ATTEMPT', 'GITHUB_JOB')})
            (root / 'emulator.json').write_text(json.dumps(receipt, indent=2) + '\n')
            diagnostics = {'processes': command(['ps', '-eo', 'pid,ppid,rss,args']),
                           'dmesg': command(['sudo', '-n', 'dmesg', '--ctime']),
                           'disk': command(['df', '-h'])}
            (root / 'host-diagnostics.json').write_text(json.dumps(diagnostics, indent=2) + '\n')
            print('::error title=Infrastructure failure (#738)::Emulator process and adb transport vanished; no product verdict.', flush=True)
    raise SystemExit(result if result >= 0 else 128 - result)


if __name__ == '__main__':
    main()
