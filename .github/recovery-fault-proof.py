#!/usr/bin/env python3
"""Disposable qualification branch only: terminate one hosted API36 emulator after a native witness."""
import argparse
import json
import os
from pathlib import Path
import re
import select
import subprocess
import time

parser = argparse.ArgumentParser()
parser.add_argument('--mode', choices=('started', 'product-failure'), required=True)
args = parser.parse_args()
if not (os.environ.get('GITHUB_ACTIONS') == 'true' and os.environ.get('RUNNER_ENVIRONMENT') == 'github-hosted'
        and os.environ.get('TEST_API') == '36'):
    raise SystemExit('Fault injection requires the hosted disposable API36 qualification job')
serial = os.environ.get('ANDROID_SERIAL', 'emulator-5554')
if not re.fullmatch(r'emulator-[0-9]+', serial):
    raise SystemExit('Require an emulator serial')
root = Path('ci-infrastructure')
root.mkdir(exist_ok=True)
record = {'qualification_only': True, 'mode': args.mode, 'serial': serial, 'injected': False,
          'sha': os.environ['GITHUB_SHA'], 'run_id': os.environ['GITHUB_RUN_ID']}
started = time.monotonic()
pattern = (r'TestRunner[^:]*:\s*started:' if args.mode == 'started' else
           r'TestRunner[^:]*:\s*failed:\s*a_deliberateProductAssertion')
with (root / 'fault-driver.log').open('w') as errors:
    stream = subprocess.Popen(['adb', '-s', serial, 'logcat', '-v', 'brief', '-s', 'TestRunner:I', '*:S'],
                              stdout=subprocess.PIPE, stderr=errors, bufsize=0)
    try:
        while stream.poll() is None and time.monotonic() - started < 300:
            if not select.select([stream.stdout], [], [], 1)[0]:
                continue
            line = stream.stdout.readline().decode("utf-8", errors="replace")
            if not re.search(pattern, line):
                continue
            record['witness'] = line.strip()
            # Let UTP receive the failure status in the negative control while its second method stays active.
            time.sleep(2 if args.mode == 'product-failure' else .5)
            boot = subprocess.run(['adb', '-s', serial, 'shell', 'cat', '/proc/sys/kernel/random/boot_id'],
                                  capture_output=True, text=True, timeout=5)
            record['boot_id_before_kill'] = boot.stdout.strip()
            killed = subprocess.run(['adb', '-s', serial, 'emu', 'kill'], capture_output=True, text=True, timeout=15)
            record.update(injected=killed.returncode == 0, kill_exit=killed.returncode,
                          kill_stdout=killed.stdout, kill_stderr=killed.stderr)
            break
    finally:
        stream.terminate()
        try:
            stream.communicate(timeout=5)
        except subprocess.TimeoutExpired:
            stream.kill()
            stream.communicate()
        record['elapsed_seconds'] = round(time.monotonic() - started, 3)
        (root / 'fault-injection.json').write_text(json.dumps(record, indent=2) + '\n')
if not record['injected']:
    raise SystemExit('Fault witness not reached; this run is not a recovery qualification')
print('Qualification-only emulator termination recorded', flush=True)
