#!/usr/bin/env python3
"""Own observer/publisher lifetimes; preserve test failure before monitoring failure."""
import argparse
import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import time


def supervise(args):
    root = Path(args.output)
    root.mkdir(exist_ok=False)
    directory = Path(__file__).resolve().parent
    observer = publisher = None
    cancelled = None
    previous = {}
    publisher_result = None
    observer_result = 125
    error = None

    def forward(sig, _frame):
        nonlocal cancelled
        cancelled = cancelled or sig
        # Observer owns and forwards to the test command's process group.
        if observer is not None and observer.poll() is None:
            observer.send_signal(sig)

    try:
        for sig in (signal.SIGINT, signal.SIGTERM):
            previous[sig] = signal.signal(sig, forward)
        with (root / 'publisher.log').open('xb') as log:
            observer = subprocess.Popen([
                sys.executable, str(directory / 'observe.py'), '--output', str(root / 'observed'),
                '--sha', args.sha, '--api', args.api, '--lane', args.lane, '--', *args.command])
            if cancelled:
                observer.send_signal(cancelled)
            try:
                publisher = subprocess.Popen([
                    args.node, str(directory / 'publish.mjs'), '--evidence-dir', str(root / 'observed'),
                    '--state-dir', str(root / 'publishing'), '--run-id', args.run_id,
                    '--attempt', args.attempt], stdout=log, stderr=log, start_new_session=True)
            except OSError as failure:
                error = type(failure).__name__
            observer_result = observer.wait()
            if publisher is not None:
                try:
                    publisher_result = publisher.wait(timeout=20)
                except subprocess.TimeoutExpired:
                    # A dead observer can leave a running manifest. Do not orphan its publisher.
                    os.killpg(publisher.pid, signal.SIGTERM)
                    try:
                        publisher_result = publisher.wait(timeout=5)
                    except subprocess.TimeoutExpired:
                        os.killpg(publisher.pid, signal.SIGKILL)
                        publisher_result = publisher.wait()
                    error = 'PublisherDrainTimeout'
    finally:
        for sig, handler in previous.items():
            signal.signal(sig, handler)
    observer_result = 128 - observer_result if observer_result < 0 else observer_result
    if cancelled and observer_result == 0:
        observer_result = 128 + cancelled
    complete = root / 'publishing/complete.json'
    monitored = False
    if publisher_result == 0 and error is None:
        try:
            published = json.loads(complete.read_text())
            observed = json.loads((root / 'observed/manifest.json').read_text())
            monitored = (published['status'] == 'monitoring_complete'
                         and published['sha'] == args.sha and published['api'] == args.api
                         and published['command_id'] == observed['command_id']
                         and published['observed_failures'] == observed['observed_failure_lines'])
        except (OSError, ValueError, KeyError, TypeError):
            error = 'InvalidPublisherCompletion'
    receipt = {'schema_version': 1, 'sha': args.sha, 'api': args.api,
               'observer_exit': observer_result, 'publisher_exit': publisher_result,
               'monitoring_complete': monitored, 'supervisor_error': error,
               'ended_unix': time.time(), 'test_verdict': 'requires_final_XML'}
    (root / 'supervision.json').write_text(json.dumps(receipt, indent=2) + '\n')
    return observer_result or (0 if monitored else 3)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('output', 'sha', 'api', 'lane', 'run-id', 'attempt'):
        parser.add_argument('--' + name, required=True)
    parser.add_argument('--node', default='node')
    parser.add_argument('command', nargs=argparse.REMAINDER)
    args = parser.parse_args()
    if len(args.command) < 2 or args.command[0] != '--':
        parser.error('Provide command argv after --')
    args.command = args.command[1:]
    return supervise(args)


if __name__ == '__main__':
    sys.exit(main())
