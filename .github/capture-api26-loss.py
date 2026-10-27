#!/usr/bin/env python3
"""Read-only API26 evidence. No explicit adb/device/logcat restart or guest-state writes."""
import argparse
import datetime
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import signal
import subprocess
import sys
import time

SERIAL = 'emulator-5554'
ADB = ['adb', '-s', SERIAL]
BOOT_RE = re.compile(r'[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}')


def utc():
    return datetime.datetime.now(datetime.timezone.utc).isoformat()


def write_json(path, value):
    with path.open('x') as handle:
        json.dump(value, handle, indent=2)
        handle.write('\n')


def root_path(value):
    root = Path(value).absolute()
    if not root.is_dir() or any(p.is_symlink() for p in [root, *root.parents]):
        raise ValueError('Evidence directory absent or symlinked')
    return root


def command(argv, timeout=8):
    started = utc()
    try:
        result = subprocess.run(argv, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=timeout)
        return dict(argv=argv, started=started, ended=utc(), returncode=result.returncode), result.stdout, result.stderr
    except subprocess.TimeoutExpired as error:
        return dict(argv=argv, started=started, ended=utc(), returncode=124, error='timeout'), error.stdout or b'', error.stderr or b''
    except OSError as error:
        return dict(argv=argv, started=started, ended=utc(), returncode=127, error=str(error)), b'', b''


def capture(root, name, argv, timeout=8):
    status, out, err = command(argv, timeout)
    for suffix, value in [('stdout', out), ('stderr', err)]:
        with (root/(name+'.'+suffix)).open('xb') as handle:
            handle.write(value)
        status[suffix+'_sha256'] = hashlib.sha256(value).hexdigest()
    write_json(root/(name+'.json'), status)
    return status, out


def processes():
    found = []
    for path in Path('/proc').glob('[0-9]*/cmdline'):
        try:
            args = path.read_bytes().rstrip(b'\0').split(b'\0')
            name = os.path.basename(os.fsdecode(args[0])) if args else ''
            if name in ('emulator', 'qemu-system-x86_64', 'qemu-system-i386', 'crashpad_handler'):
                if name == 'crashpad_handler' and not any(b'emu-crash' in a for a in args):
                    continue
                found.append(dict(pid=int(path.parent.name), argv=[os.fsdecode(a) for a in args],
                                  start_stat=(path.parent/'stat').read_text()))
        except (OSError, ValueError):
            continue
    return found


def start_ticks(pid):
    # comm can contain spaces/parentheses, so split only after its final ')'.
    return Path('/proc', str(pid), 'stat').read_text().rsplit(')', 1)[1].split()[19]


def observe_device():
    row = dict(utc=utc(), monotonic=time.monotonic(), processes=processes())
    def run(name, argv):
        result, out, err = command(argv, 4)
        row[name] = dict(**result, stdout=out.decode(errors='replace'), stderr=err.decode(errors='replace'))
        return result['returncode'] == 0, out.strip()
    ok, out = run('devices', ['adb', 'devices', '-l'])
    devices = [line.split()[:2] for line in out.decode(errors='replace').splitlines()[1:] if line.strip()]
    selected = ok and devices == [[SERIAL, 'device']]
    if selected:
        ok, out = run('state', ADB+['get-state'])
        selected = ok and out == b'device'
    if selected:
        ok, out = run('boot', ADB+['shell', 'cat', '/proc/sys/kernel/random/boot_id'])
        row['boot_id'] = out.decode(errors='replace')
        selected = ok and BOOT_RE.fullmatch(row['boot_id']) is not None
    if selected:
        ok, out = run('uptime', ADB+['shell', 'cat', '/proc/uptime'])
        try:
            row['uptime_seconds'] = float(out.split()[0])
            selected = ok and 0 <= row['uptime_seconds'] < float('inf')
        except (ValueError, IndexError):
            selected = False
    row['selected'] = selected
    return row


def monitor(root, interval=2):
    if (root/'monitor.stop').exists():
        raise ValueError('Refusing stale monitor stop sentinel')
    status = dict(schema_version=2, started_utc=utc(), ended_utc=None, connected_once=False,
                  attached=False, boot_id=None, streams_started=[], stream_exits={},
                  device_lost=False, errors=[], samples=0, healthy=False)
    streams, handles = {}, []
    stop, stable_samples, previous_uptime, previous_sample = False, 0, None, None
    def request_stop(sig, frame):
        nonlocal stop
        stop = True
        status['stop_signal'] = sig
    def launch(name, argv):
        out = (root/(name+'.original.log')).open('xb')
        err = (root/(name+'.stderr.log')).open('xb')
        handles.extend([out, err])
        child = subprocess.Popen(argv, stdout=out, stderr=err)
        streams[name] = child
        status['streams_started'].append(dict(name=name, argv=argv, pid=child.pid, utc=utc()))
    old = {sig: signal.signal(sig, request_stop) for sig in (signal.SIGTERM, signal.SIGINT)}
    try:
        launch('host-kernel', ['sudo', '-n', 'dmesg', '--follow', '--ctime'])
        with (root/'adb-timeline.jsonl').open('x', buffering=1) as timeline:
            write_json(root/'monitor.ready', dict(pid=os.getpid(), start_ticks=start_ticks(os.getpid()), started_utc=status['started_utc']))
            while not stop and not (root/'monitor.stop').exists():
                row = observe_device()
                selected = row['selected']
                if previous_sample is not None and row['monotonic']-previous_sample > 30:
                    status['errors'].append('ADB sampling gap exceeds 30 seconds')
                previous_sample = row['monotonic']
                if selected and status['boot_id'] and row['boot_id'] != status['boot_id']:
                    selected = False
                    if 'Emulator boot identity changed; no reconnect' not in status['errors']:
                        status['errors'].append('Emulator boot identity changed; no reconnect')
                if selected and previous_uptime is not None and row['uptime_seconds'] < previous_uptime:
                    selected = False
                    if 'Guest uptime decreased' not in status['errors']:
                        status['errors'].append('Guest uptime decreased')
                if selected and not status['connected_once']:
                    row['identity'] = []
                    for prop, expected in [('ro.kernel.qemu', b'1'), ('ro.build.version.sdk', b'26'), ('sys.boot_completed', b'1')]:
                        result, value, error = command(ADB+['shell', 'getprop', prop], 4)
                        row['identity'].append(dict(**result, stdout=value.decode(errors='replace'), stderr=error.decode(errors='replace')))
                        selected = selected and result['returncode'] == 0 and value.strip() == expected
                    if selected:
                        status['connected_once'] = True
                        status['boot_id'] = row['boot_id']
                        for buffer in ('all', 'crash'):
                            launch('logcat-'+buffer, ADB+['logcat', '-b', buffer, '-v', 'threadtime'])
                if status['connected_once'] and not selected and not (root/'monitor.stop').exists():
                    status['device_lost'] = True
                if selected:
                    previous_uptime = row['uptime_seconds']
                for name, child in streams.items():
                    if child.poll() is not None and name not in status['stream_exits']:
                        status['stream_exits'][name] = dict(returncode=child.returncode, utc=utc())
                stable_samples = stable_samples+1 if selected and not status['device_lost'] and not status['stream_exits'] and not status['errors'] else 0
                row.update(selected=selected, device_lost=status['device_lost'], stream_exits=dict(status['stream_exits']), errors=list(status['errors']))
                timeline.write(json.dumps(row)+'\n')
                status['samples'] += 1
                if not status['attached'] and stable_samples >= 2 and (root/'logcat-all.original.log').stat().st_size > 0:
                    write_json(root/'monitor.attached', dict(utc=utc(), monotonic=time.monotonic(), serial=SERIAL, api='26', boot_id=status['boot_id'], streams=['host-kernel', 'logcat-all', 'logcat-crash']))
                    status['attached'] = True
                deadline = time.monotonic()+interval
                while not stop and not (root/'monitor.stop').exists() and time.monotonic() < deadline:
                    time.sleep(min(.2, max(0, deadline-time.monotonic())))
    except Exception as error:
        status['errors'].append(type(error).__name__+': '+str(error))
    finally:
        for name, child in streams.items():
            if child.poll() is None:
                child.terminate()
                try:
                    child.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    child.kill()
                    child.wait()
                    status['errors'].append('Stream required kill: '+name)
            elif name not in status['stream_exits']:
                status['stream_exits'][name] = dict(returncode=child.returncode, utc=utc())
        for handle in handles:
            handle.close()
        for sig, handler in old.items():
            signal.signal(sig, handler)
        status['ended_utc'] = utc()
        status['healthy'] = status['attached'] and not status['device_lost'] and not status['stream_exits'] and not status['errors'] and 'stop_signal' not in status
        status['original_sha256'] = {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in root.glob('*.log') if p.name.startswith(('logcat-', 'host-kernel.')) and p.is_file() and not p.is_symlink()}
        status['timeline_sha256'] = hashlib.sha256((root/'adb-timeline.jsonl').read_bytes()).hexdigest() if (root/'adb-timeline.jsonl').is_file() else None
        write_json(root/'monitor.json', status)
    return 0 if status['healthy'] else 3


def check_attached(root):
    if (root/'monitor.stop').exists() or (root/'monitor.json').exists():
        raise ValueError('Monitor has stopped')
    for name in ('monitor.ready', 'monitor.attached', 'adb-timeline.jsonl'):
        if not (root/name).is_file() or (root/name).is_symlink():
            raise ValueError('Missing/nonordinary monitor receipt: '+name)
    ready = json.loads((root/'monitor.ready').read_text())
    attached = json.loads((root/'monitor.attached').read_text())
    if start_ticks(ready['pid']) != ready['start_ticks']:
        raise ValueError('Monitor process ownership changed')
    row = json.loads((root/'adb-timeline.jsonl').read_text().splitlines()[-1])
    if not row['selected'] or row['device_lost'] or row['stream_exits'] or row['errors'] or row['boot_id'] != attached['boot_id']:
        raise ValueError('Monitor already degraded')
    age = time.monotonic()-row['monotonic']
    if not 0 <= age <= 15:
        raise ValueError('Monitor heartbeat stale')
    return 0


def snapshot(root, label):
    destination = root/('guest-'+label)
    destination.mkdir()
    results, output = {}, {}
    for name, argv in [('logcat-all', ADB+['logcat', '-b', 'all', '-d', '-v', 'threadtime']),
                       ('logcat-crash', ADB+['logcat', '-b', 'crash', '-d', '-v', 'threadtime']),
                       ('devices', ['adb', 'devices', '-l']), ('state', ADB+['get-state']),
                       ('boot-id', ADB+['shell', 'cat', '/proc/sys/kernel/random/boot_id']),
                       ('uptime', ADB+['shell', 'cat', '/proc/uptime']),
                       ('tombstones-list', ADB+['shell', 'ls', '-la', '/data/tombstones'])]:
        results[name], output[name] = capture(destination, name, argv)
    # Ordinary shell privileges only; permission refusal remains evidence, not a reason to root/restart.
    results['tombstones-pull'], _ = capture(destination, 'tombstones-pull', ADB+['pull', '/data/tombstones', str(destination/'tombstones')], 30)
    critical = ['logcat-all', 'logcat-crash', 'devices', 'state', 'boot-id', 'uptime']
    healthy = all(results[name]['returncode'] == 0 for name in critical) and bool(output['logcat-all'].strip())
    healthy = healthy and output['state'].strip() == b'device' and BOOT_RE.fullmatch(output['boot-id'].decode(errors='replace').strip()) is not None
    write_json(destination/'snapshot.json', dict(schema_version=2, utc=utc(), healthy=healthy, statuses=results,
        limitation='Absent/permission-denied/lost-device tombstones remain unavailable, not evidence of no native crash.'))
    return 0 if healthy else 3


def crash_roots():
    user = os.environ.get('USER', '')
    if not re.fullmatch(r'[A-Za-z0-9_-]+', user):
        raise ValueError('Cannot safely identify Android per-user crash directory')
    base = Path('/tmp')/('android-'+user)
    if base.is_symlink():
        raise ValueError('Symlink Android temporary directory refused')
    return sorted(base.glob('emu-crash-*.db')) if base.is_dir() else []


def final(root):
    destination = root/'host-final'
    destination.mkdir()
    results = {}
    for name, argv in [('dmesg', ['sudo', '-n', 'dmesg', '--ctime']), ('free', ['free', '-h']), ('df', ['df', '-h'])]:
        results[name], _ = capture(destination, name, argv, 15)
    write_json(destination/'processes.json', dict(utc=utc(), processes=processes()))
    databases, errors = [], []
    for source in crash_roots():
        record = dict(source=str(source), status='unavailable')
        try:
            if source.is_symlink() or not source.is_dir():
                raise ValueError('Crash database is not an ordinary directory')
            if any(p.is_symlink() or (not p.is_dir() and not p.is_file()) for p in source.rglob('*')):
                raise ValueError('Crash database contains symlink/special file')
            target = destination/'crash-databases'/source.name
            target.parent.mkdir(exist_ok=True)
            shutil.copytree(source, target)
            record.update(status='copied', sha256={str(p.relative_to(target)): hashlib.sha256(p.read_bytes()).hexdigest() for p in target.rglob('*') if p.is_file()})
        except (OSError, ValueError) as error:
            record['error'] = str(error)
            errors.append(str(error))
        databases.append(record)
    write_json(destination/'host.json', dict(schema_version=2, utc=utc(), statuses=results, crash_databases=databases,
        crash_database_status='available' if databases else 'absent-at-known-emulator-location', errors=errors,
        limitation='No broad temporary/home-directory search; absent crashpad database does not rule out a native crash.'))
    return 3 if results['dmesg']['returncode'] or errors else 0


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('mode', choices=('monitor', 'snapshot', 'final', 'check'))
    parser.add_argument('--evidence', required=True)
    parser.add_argument('--label', choices=('before', 'after'))
    args = parser.parse_args()
    root = root_path(args.evidence)
    if args.mode == 'snapshot':
        if not args.label:
            parser.error('snapshot requires --label')
        return snapshot(root, args.label)
    if args.label:
        parser.error('--label only applies to snapshot')
    return {'monitor': monitor, 'final': final, 'check': check_attached}[args.mode](root)


if __name__ == '__main__':
    try:
        sys.exit(main())
    except (OSError, ValueError, KeyError, IndexError, TypeError) as error:
        print(type(error).__name__+': '+str(error), file=sys.stderr)
        sys.exit(3)
