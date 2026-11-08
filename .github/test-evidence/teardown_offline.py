#!/usr/bin/env python3
"""Classify and recover from an adb transport drop AFTER a complete, all-pass instrumentation run (#675).

The hosted API 36 emulator occasionally reports ``device offline`` in the first
second after ``am instrument`` finishes, while UTP pulls additional outputs and
uninstalls the test APKs. The instrumentation XML is already complete, every
test has passed and the emulator process is still alive, yet Gradle fails the
task with a PLUGIN_ERROR from a teardown-only plugin. This module recognises
exactly that signature so the prototype runner can wait for the same guest to
reconnect, finish the interrupted cleanup and accept the first attempt's
complete results (never a second run, which would give every test another
chance). Any failed, errored, missing or uncounted test, any observed failure
line, any other plugin or message, a rebooted guest or an uninstall that cannot
complete keeps the original failure.
"""
import argparse
import json
from pathlib import Path
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

# UTP plugins whose onAfterAll runs only after every test result was delivered.
TEARDOWN_PLUGINS = ('AndroidTestApkInstallerPlugin', 'AndroidAdditionalTestOutputPlugin',
                    'AndroidTestLogcatPlugin', 'AndroidTestDeviceInfoPlugin')
SIGNATURE = re.compile(r'Exception thrown during onAfterAll invocation of plugin (?P<plugin>[A-Za-z0-9_]+): device offline')
REJECTED = 'com.android.ddmlib.AdbCommandRejectedException: device offline'
PACKAGES = ('io.github.anastaci1a.localica.debug', 'io.github.anastaci1a.localica.debug.test')


def classify(live, results, expected):
    """Return (accepted, record). accepted is True only for the exact teardown-offline signature."""
    record = {'schema_version': 1, 'issue': 675, 'signature': 'teardown-device-offline', 'accepted': False, 'reasons': []}
    reasons = record['reasons']
    try:
        manifest = json.loads((live / 'observed/manifest.json').read_text())
    except (OSError, ValueError) as error:
        reasons.append('Observer manifest unreadable: ' + type(error).__name__)
        return False, record
    record['observed'] = {key: manifest.get(key) for key in
                          ('status', 'child_returncode', 'observed_failure_lines', 'cancel_signal', 'observer_error')}
    if manifest.get('status') != 'failed' or not manifest.get('child_returncode'):
        reasons.append('Gradle did not fail on its own')
    if manifest.get('observed_failure_lines') != 0:
        reasons.append('Observed failure lines')
    if manifest.get('cancel_signal') is not None or manifest.get('observer_error') is not None:
        reasons.append('Cancelled or degraded observation')
    try:
        log = (live / 'observed/command.log').read_text(encoding='utf-8', errors='replace')
    except OSError as error:
        reasons.append('Command log unreadable: ' + type(error).__name__)
        return False, record
    matches = SIGNATURE.findall(log)
    record['plugins'] = sorted(set(matches))
    if not matches:
        reasons.append('No teardown device-offline plugin error')
    elif any(plugin not in TEARDOWN_PLUGINS for plugin in matches):
        reasons.append('Device offline reported by a plugin that is not teardown-only')
    if REJECTED not in log:
        reasons.append('No AdbCommandRejectedException: device offline')
    if re.search(r'Test run failed to complete|Process crashed|INSTRUMENTATION_ABORTED|Instrumentation run failed', log):
        reasons.append('Instrumentation did not complete')
    finished = re.findall(r'Finished (\d+) tests on ', log)
    if len(finished) != 1:
        reasons.append('Expected exactly one finished test run')
    progress = re.findall(r'Tests (\d+)/(\d+) completed\. \((\d+) skipped\) \((\d+) failed\)', log)
    if not progress:
        reasons.append('No progress lines')
    else:
        done, total, _skipped, failed = progress[-1]
        if done != total or failed != '0' or (finished and finished[0] != total):
            reasons.append('Last progress line is not a complete, failure-free run')
    try:
        runtime_methods = set(json.loads(expected.read_text())['runtime_methods'])
    except (OSError, ValueError, KeyError) as error:
        reasons.append('Expected methods unreadable: ' + type(error).__name__)
        return False, record
    files = sorted(results.rglob('*.xml')) if results.is_dir() else []
    methods = {}
    xml_problems = []
    for file in files:
        try:
            tree = ET.parse(file)
        except ET.ParseError:
            xml_problems.append('Unparseable XML ' + file.name)
            continue
        root = tree.getroot()
        suites = [root] if root.tag == 'testsuite' else root.findall('.//testsuite')
        for suite in suites:
            cases = suite.findall('testcase')
            if int(suite.attrib.get('tests', -1)) != len(cases):
                xml_problems.append('Inconsistent XML test count in ' + file.name)
            if int(suite.attrib.get('failures', 0)) or int(suite.attrib.get('errors', 0)):
                xml_problems.append('XML reports failures or errors in ' + file.name)
            for case in cases:
                identity = case.attrib.get('classname', '') + '#' + case.attrib.get('name', '')
                if case.find('failure') is not None or case.find('error') is not None:
                    xml_problems.append('Failed test ' + identity)
                if identity in methods:
                    xml_problems.append('Duplicate test ' + identity)
                methods[identity] = 'skipped' if case.find('skipped') is not None else 'passed'
    if not files:
        xml_problems.append('No instrumentation XML')
    if set(methods) != runtime_methods:
        xml_problems.append('XML methods differ from the source inventory')
    reasons.extend(xml_problems)
    record['xml'] = {'files': [str(f) for f in files], 'methods': len(methods),
                     'skipped': sum(v == 'skipped' for v in methods.values())}
    textproto = sorted(results.rglob('test-result.textproto')) if results.is_dir() else []
    if textproto:
        try:
            text = textproto[0].read_text(encoding='utf-8', errors='replace')
        except OSError:
            text = ''
        if 'platform_error' not in text or 'device offline' not in text:
            reasons.append('UTP result lacks the device-offline platform error')
        record['textproto'] = str(textproto[0])
    record['accepted'] = not reasons
    return record['accepted'], record


def adb(serial, *args, timeout=20):
    try:
        done = subprocess.run(['adb', '-s', serial, *args], capture_output=True, text=True, timeout=timeout)
    except (OSError, subprocess.TimeoutExpired) as error:
        return None, type(error).__name__
    return done.returncode, (done.stdout + done.stderr).replace('\r', '').strip()


def recover(serial, api, boot_id, timeout, output, now=time.monotonic, sleep=time.sleep):
    """Wait for the SAME guest to answer adb again. Return (recovered, record)."""
    output.mkdir(parents=True, exist_ok=True)
    record = {'schema_version': 1, 'issue': 675, 'serial': serial, 'expected_api': str(api),
              'expected_boot_id': boot_id, 'timeout_seconds': timeout, 'recovered': False,
              'states': [], 'reason': None}
    start = now()
    deadline = start + timeout
    while True:
        elapsed = round(now() - start, 1)
        code, state = adb(serial, 'get-state', timeout=10)
        record['states'].append({'elapsed': elapsed, 'exit': code, 'state': state})
        if code == 0 and state == 'device':
            _, booted = adb(serial, 'shell', 'getprop', 'sys.boot_completed')
            _, sdk = adb(serial, 'shell', 'getprop', 'ro.build.version.sdk')
            _, boot = adb(serial, 'shell', 'cat', '/proc/sys/kernel/random/boot_id')
            record.update(observed_api=sdk, observed_boot_id=boot, boot_completed=booted, recovered_after_seconds=elapsed)
            if booted == '1' and sdk == str(api) and boot == boot_id:
                record['recovered'] = True
            elif booted == '1' and sdk == str(api) and boot != boot_id:
                record['reason'] = 'Guest rebooted: not a transient transport drop'
            elif booted == '1':
                record['reason'] = 'Different device answered'
            if record['recovered'] or record['reason']:
                break
        if now() >= deadline:
            record['reason'] = record['reason'] or 'Device did not return within the timeout'
            break
        sleep(5)
    # Read-only diagnostics for the next investigation; never fail recovery on them.
    if record['recovered']:
        _, record['adbd'] = adb(serial, 'shell', 'getprop', 'init.svc.adbd')
        _, record['uptime'] = adb(serial, 'shell', 'cat', '/proc/uptime')
        _, devices = adb(serial, 'devices', '-l')
        record['devices'] = devices
        code, logcat = adb(serial, 'shell', 'logcat', '-d', '-b', 'main,system,crash', '-t', '3000', timeout=60)
        (output / 'logcat-after-recovery.txt').write_text(logcat or '')
        record['logcat_exit'] = code
    (output / 'recovery.json').write_text(json.dumps(record, indent=2) + '\n')
    return record['recovered'], record


def uninstall(serial):
    """Finish the interrupted teardown: both test packages must be gone afterwards. Return (done, record)."""
    record = {}
    for package in PACKAGES:
        _, output = adb(serial, 'uninstall', package, timeout=60)
        _, listed = adb(serial, 'shell', 'pm', 'list', 'packages', package)
        record[package] = {'uninstall': output, 'listed': listed,
                           'removed': 'package:' + package not in (listed or '').split()}
    return all(entry['removed'] for entry in record.values()), record


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    commands = parser.add_subparsers(dest='command', required=True)
    c = commands.add_parser('classify')
    c.add_argument('--live', type=Path, required=True)
    c.add_argument('--results', type=Path, required=True)
    c.add_argument('--expected', type=Path, required=True)
    c.add_argument('--output', type=Path, required=True)
    r = commands.add_parser('recover')
    r.add_argument('--serial', required=True)
    r.add_argument('--api', required=True)
    r.add_argument('--boot-id', required=True)
    r.add_argument('--timeout', type=int, default=180)
    r.add_argument('--output', type=Path, required=True)
    u = commands.add_parser('uninstall')
    u.add_argument('--serial', required=True)
    u.add_argument('--output', type=Path, required=True)
    args = parser.parse_args(argv)
    if args.command == 'classify':
        accepted, record = classify(args.live, args.results, args.expected)
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(record, indent=2) + '\n')
        print(('Teardown device-offline signature: ' if accepted else 'Not the teardown device-offline signature: ')
              + json.dumps(record['reasons']), file=sys.stderr)
        return 0 if accepted else 1
    if args.command == 'recover':
        if not re.fullmatch(r'emulator-[0-9]+', args.serial) or not args.boot_id:
            print('Recovery needs an explicit emulator serial and the pre-run boot id', file=sys.stderr)
            return 2
        recovered, record = recover(args.serial, args.api, args.boot_id, args.timeout, args.output)
        print(('Device reconnected after %ss' % record.get('recovered_after_seconds')) if recovered
              else 'Device did not recover: ' + str(record['reason']), file=sys.stderr)
        return 0 if recovered else 1
    done, record = uninstall(args.serial)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(record, indent=2) + '\n')
    if not done:
        print('Interrupted teardown could not complete: a test package is still installed', file=sys.stderr)
    return 0 if done else 1


if __name__ == '__main__':
    raise SystemExit(main())
