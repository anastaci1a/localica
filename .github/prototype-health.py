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


PRODUCT_DIAGNOSTIC = re.compile(
    r'\b[A-Za-z_$][A-Za-z0-9_.$]*(?:Error|Failure)\b|FATAL EXCEPTION|'
    r'TestRunner: (?:failed|failure):|java\.lang\.(?:\w*Exception|\w*Error)|'
    r'org\.junit\.runners\.model\.(?:TestTimedOutException|MultipleFailureException)')


def product_diagnostic(text):
    return PRODUCT_DIAGNOSTIC.search(text) is not None


def command(args, timeout=4):
    try:
        result = subprocess.run(args, capture_output=True, text=True, timeout=timeout)
        return {'exit_code': result.returncode, 'stdout': result.stdout[-32768:], 'stderr': result.stderr[-8192:]}
    except (OSError, subprocess.TimeoutExpired) as error:
        return {'exit_code': None, 'error': type(error).__name__}


def emulator_processes(serial, proc_root=Path('/proc')):
    found = []
    for proc in proc_root.glob('[0-9]*'):
        try:
            argv = (proc / 'cmdline').read_bytes().split(b'\0')
            executable = Path(os.fsdecode(argv[0])).name
            port = os.fsencode(serial.removeprefix('emulator-'))
            selected = any(argv[i] == b'-port' and i + 1 < len(argv) and argv[i + 1] == port
                           for i in range(len(argv)))
            if selected and (executable.startswith('qemu-system-') or executable in ('emulator', 'emulator64-x86')):
                start_ticks = (proc / 'stat').read_text().rpartition(') ')[2].split()[19]
                found.append({'pid': int(proc.name), 'start_ticks': start_ticks,
                              'argv': [os.fsdecode(arg) for arg in argv if arg]})
        except (OSError, IndexError):
            pass
    return found


def infrastructure_diagnostic(text, serial='emulator-5554'):
    """The original UTP device-loss diagnostic; unknown stderr remains fatal."""
    missing = "device '" + re.escape(serial) + "' not found"
    allowed = [r'Exception thrown during onAfterAll invocation of plugin AndroidTestApkInstallerPlugin: ' + missing,
               missing, r'com\.android\.ddmlib\.AdbCommandRejectedException: ' + missing,
               r'at (?:com\.android\.ddmlib\.|com\.android\.tools\.utp\.plugins\.deviceprovider\.ddmlib\.|'
               r'kotlin\.coroutines\.|kotlinx\.coroutines\.)[^\r\n]+',
               r'Test run failed to complete\. Expected [0-9]+ tests, received [0-9]+']
    lines = [line.strip() for line in text.splitlines() if line.strip()]
    return (bool(lines) and any('com.android.ddmlib.AdbCommandRejectedException:' in line for line in lines)
            and all(any(re.fullmatch(pattern, line) for pattern in allowed) for line in lines))


def classify(result, samples, results, iterations=None, evidence=None):
    """Any substantive assertion/error overrides host loss. Unknown/malformed evidence fails closed."""
    if result == 0:
        return None
    if evidence is None or not samples:
        return None
    try:
        context = dict(line.split('=', 1) for line in (evidence / 'context.txt').read_text().splitlines() if line)
        if (context['boot_id'] != samples[0].get('boot_id', {}).get('stdout', '').strip()
                or context['api'] != context['actual_api'] or context['api'] not in ('26', '36')
                or not re.fullmatch(r'emulator-[0-9]+', context['serial'])
                or not re.fullmatch(r'[0-9a-f]{40}', context['candidate_sha'])):
            return None
    except (OSError, ValueError, KeyError):
        return None
    log_roots = [evidence] + ([iterations] if iterations else [])
    observed_logs = [p for root in log_roots if root.exists() for p in root.rglob('live/observed/command.log')]
    if not observed_logs:
        return None
    failed_observer = False
    for log in observed_logs:
        try:
            observed = json.loads((log.parent / 'manifest.json').read_text())
            supervision = json.loads((log.parent.parent / 'supervision.json').read_text())
            text = log.read_text(errors='replace')
            required = {'sha', 'api', 'observer_error', 'cancel_signal', 'status', 'oversize_lines_not_parsed'}
            if (not required.issubset(observed) or not {'monitoring_complete', 'supervisor_error', 'sha', 'api'}.issubset(supervision)
                    or observed['sha'] != context['candidate_sha'] or str(observed['api']) != context['api']
                    or supervision['sha'] != context['candidate_sha'] or str(supervision['api']) != context['api']
                    or not text.strip() or supervision.get('monitoring_complete') is not True
                    or supervision.get('supervisor_error') is not None
                    or observed.get('observer_error') is not None or observed.get('cancel_signal') is not None
                    or observed.get('status') not in ('completed', 'failed')
                    or observed.get('oversize_lines_not_parsed') != 0):
                return None
            if product_diagnostic(text):
                return None
            failed_observer |= observed['status'] == 'failed'
        except (OSError, ValueError, KeyError):
            return None
    if not failed_observer:
        return None
    failures = []
    files = list(results.rglob('*.xml')) if results.exists() else []
    if iterations and iterations.exists():
        files += list(iterations.rglob('*.xml'))
    for path in files:
        try:
            tree = ET.parse(path)
            for node in tree.iter():
                text = ''.join(node.itertext()).strip() if node.tag in ('system-err', 'system-out') else ''
                if (node.tag == 'system-err' and text and not infrastructure_diagnostic(text, context['serial'])) or product_diagnostic(text):
                    return None
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
            if product_diagnostic(text):
                return None
    # Only the original observed guest may lead into an ordered, terminal loss.
    # An earlier loss followed by recovery cannot authorize retry of a later failure.
    if len(samples) < 3:
        return None
    origin = samples[0]
    boot = origin.get('boot_id', {}).get('stdout', '').strip()
    if (origin['adb']['exit_code'] != 0 or not origin['processes']
            or not re.fullmatch(r'[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}', boot)
            or origin.get('boot_id', {}).get('exit_code') != 0):
        return None
    def identities(sample):
        return {(p['pid'], p.get('start_ticks')) for p in sample['processes']}
    original = identities(origin)
    if any(ticks is None for _, ticks in original):
        return None
    lost = []
    previous = -1
    for sample in samples:
        if sample['elapsed_seconds'] <= previous:
            return None
        previous = sample['elapsed_seconds']
        absent = not sample['processes'] and sample['adb']['exit_code'] not in (None, 0)
        if absent:
            lost.append(sample)
        elif lost or identities(sample) != original:
            return None
        elif (sample.get('boot_id', {}).get('exit_code') != 0
              or sample.get('boot_id', {}).get('stdout', '').strip() != boot):
            return None
    if len(lost) < 2:
        return None
    return {'schema_version': 1, 'classification': 'infrastructure', 'reason': 'emulator-process-lost',
            'issue': 738, 'exit_code': result, 'empty_utp_failures': failures,
            'first_loss_seconds': lost[0]['elapsed_seconds'], 'lost_samples': len(lost),
            'original_boot_id': boot, 'original_processes': origin['processes']}


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
                'processes': emulator_processes(serial),
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
        receipt = classify(result, samples, Path('app/build/outputs/androidTest-results'), Path('prototype-iterations'), Path('prototype-evidence'))
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
