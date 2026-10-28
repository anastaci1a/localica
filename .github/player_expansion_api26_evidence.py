"""Fail-closed validation of healthy capture evidence. Not a test-result authority."""
import hashlib
import math
import json
from pathlib import Path
import re

STREAMS = {'host-kernel': ['sudo', '-n', 'dmesg', '--follow', '--ctime'],
           'logcat-all': ['adb', '-s', 'emulator-5554', 'logcat', '-b', 'all', '-v', 'threadtime'],
           'logcat-crash': ['adb', '-s', 'emulator-5554', 'logcat', '-b', 'crash', '-v', 'threadtime']}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def data(path):
    require(path.is_file() and not any(p.is_symlink() for p in [path, *path.parents]), 'Missing/symlink evidence: '+str(path))
    return path.read_bytes()


def digest(path):
    return hashlib.sha256(data(path)).hexdigest()


def read_json(path):
    def unique(pairs):
        output = {}
        for key, value in pairs:
            require(key not in output, 'Duplicate JSON key: '+key)
            output[key] = value
        return output
    return json.loads(data(path), object_pairs_hook=unique)


def validate_commands(root, commands):
    for name, record in commands.items():
        require(read_json(root/(name+'.json')) == record, 'Command receipt mismatch: '+name)
        for suffix in ('stdout', 'stderr'):
            require(record[suffix+'_sha256'] == digest(root/(name+'.'+suffix)), 'Changed command original: '+name)


def validate_loss_capture(root):
    root = Path(root)
    monitor = read_json(root/'monitor.json')
    attached = read_json(root/'monitor.attached')
    require(monitor['schema_version'] == 2 and monitor['healthy'] is True and monitor['attached'] is True,
            'Loss monitor not healthy/attached')
    require(monitor['connected_once'] is True and monitor['device_lost'] is False and not monitor['stream_exits'] and not monitor['errors'] and 'stop_signal' not in monitor,
            'Monitor loss, early stream exit, interruption, or error')
    require(attached['serial'] == 'emulator-5554' and attached['api'] == '26' and attached['boot_id'] == monitor['boot_id'], 'Monitor device/boot mismatch')
    require(re.fullmatch(r'[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}', monitor['boot_id']), 'Invalid monitor boot identity')
    require(attached['streams'] == ['host-kernel', 'logcat-all', 'logcat-crash'], 'Incomplete attached streams')
    starts = monitor['streams_started']
    require(len(starts) == 3 and {r['name']: r['argv'] for r in starts} == STREAMS, 'Wrong stream invocation inventory')
    expected = {name+suffix for name in STREAMS for suffix in ('.original.log', '.stderr.log')}
    require(set(monitor['original_sha256']) == expected, 'Incomplete stream originals')
    for name, sha in monitor['original_sha256'].items():
        require(digest(root/name) == sha, 'Changed stream original: '+name)
    require(data(root/'logcat-all.original.log').strip(), 'Empty original all-buffer logcat')
    require(data(root/'host-kernel.original.log').strip(), 'Empty original host kernel stream')
    require(digest(root/'adb-timeline.jsonl') == monitor['timeline_sha256'], 'Changed ADB timeline')
    rows = [json.loads(line) for line in data(root/'adb-timeline.jsonl').splitlines()]
    require(len(rows) == monitor['samples'] and len(rows) >= 2, 'Incomplete ADB timeline')
    native_start = read_json(root/'native-start.json')['monotonic']
    native_end = read_json(root/'native-end.json')['monotonic']
    require(all(isinstance(t, (int, float)) and not isinstance(t, bool) and math.isfinite(t) for t in (native_start, native_end)), 'Invalid native window')
    require(native_start <= native_end and attached['monotonic'] <= native_start, 'Invalid native/attachment order')
    require(rows[0]['monotonic'] <= native_start and rows[-1]['monotonic'] >= native_end, 'ADB timeline does not bracket native execution')
    connected, previous, uptime = False, None, -float('inf')
    for row in rows:
        require(isinstance(row['monotonic'], (int,float)) and math.isfinite(row['monotonic']), 'Invalid host timestamp')
        if previous is not None:
            require(0 <= row['monotonic']-previous <= 30, 'Nonmonotonic or stalled ADB sampling timeline')
        previous = row['monotonic']
        require(not row['device_lost'] and not row['stream_exits'] and not row['errors'], 'Degraded timeline contradicts healthy receipt')
        if row['selected']:
            connected = True
            require(row['boot_id'] == monitor['boot_id'] and row['uptime_seconds'] >= uptime, 'Guest restarted in timeline')
            uptime = row['uptime_seconds']
        else:
            require(not connected, 'Lost device in supposedly healthy timeline')
    require(connected, 'No connected timeline')
    for label in ('before', 'after'):
        guest = root/('guest-'+label)
        snapshot = read_json(guest/'snapshot.json')
        require(snapshot['schema_version'] == 2 and snapshot['healthy'] is True, 'Degraded '+label+' snapshot')
        required = {'logcat-all', 'logcat-crash', 'devices', 'state', 'boot-id', 'uptime', 'tombstones-list', 'tombstones-pull'}
        require(set(snapshot['statuses']) == required, 'Incomplete snapshot inventory')
        validate_commands(guest, snapshot['statuses'])
        require(all(snapshot['statuses'][name]['returncode'] == 0 for name in required-{'tombstones-list', 'tombstones-pull'}), 'Failed critical snapshot')
        require(data(guest/'boot-id.stdout').decode().strip() == monitor['boot_id'], 'Snapshot changed boot identity')
        require(data(guest/'state.stdout').strip() == b'device', 'Snapshot missing device')
    host = read_json(root/'host-final/host.json')
    require(not host['errors'] and host['statuses']['dmesg']['returncode'] == 0, 'Degraded host capture')
    validate_commands(root/'host-final', host['statuses'])
    for database in host['crash_databases']:
        require(database['status'] == 'copied', 'Unavailable host crash database')
        for name, sha in database['sha256'].items():
            path = root/'host-final/crash-databases'/Path(database['source']).name/name
            require(path.resolve().is_relative_to((root/'host-final/crash-databases').resolve()), 'Crash path escaped root')
            require(digest(path) == sha, 'Changed crash database file')
    env = read_json(root/'pinned-environment.json')
    require(env['valid'] is True and env['emulator_version'] == '37.2.12.0' and env['emulator_build'] == '16428233' and env['image_revision'] == '16', 'Wrong emulator/image pin')
    require(set(env['original_sha256']) == {'emulator-source.properties', 'image-source.properties', 'avd-config.ini', 'emulator-version.txt'}, 'Incomplete environment originals')
    require(re.fullmatch('[0-9a-f]{64}', env['binary_sha256']), 'Invalid emulator binary digest')
    require(env['source_sha'] == '1a33ec700da54044a552fbf45d012854d1ffee5c' and env['baseline_run'] == '40593355374', 'Wrong baseline provenance')
    require(env['baseline_options'] == ['-no-window','-gpu','swiftshader_indirect','-no-snapshot','-noaudio','-no-boot-anim','-memory','3072'] and env['logging_only_options'] == ['-verbose','-show-kernel'], 'Changed baseline emulator options')
    for name, sha in env['original_sha256'].items():
        require('/' not in name and digest(root/name) == sha, 'Changed environment original')
    emulator = data(root/'emulator-original.log').decode(errors='replace')
    require('Android emulator version 37.2.12.0 (build_id 16428233)' in emulator, 'Wrong executed emulator version')
    adapters = re.findall(r'(?m)^.*\bGraphics Adapter (?!Vendor\b)([^\r\n]+)$', emulator)
    require(adapters and set(adapters) == {'Android Emulator OpenGL ES Translator (Google SwiftShader)'}, 'Expected SwiftShader adapter identity absent/contradictory')
    return {str(p.relative_to(root)): digest(p) for p in root.rglob('*') if p.is_file() and p.name not in ('reconciled-tests.json',)}


def validate_device(raw):
    """Require the fixed API26 profile, not merely requested workflow options."""
    def effective(label):
        rows = re.findall(r'^(Physical|Override) '+label+r': (.+)$', raw, re.M)
        require(rows and len({kind for kind, _ in rows}) == len(rows), 'Missing/ambiguous '+label)
        values = dict(rows)
        return values.get('Override', values.get('Physical'))
    require(raw.splitlines()[:2] == ['serial=emulator-5554', 'requested_api=26'], 'Wrong device header')
    require(effective('size') == '1080x1920' and effective('density') == '420', 'Wrong API26 resolution/density')
    for key in ('animator_duration_scale', 'transition_animation_scale', 'window_animation_scale'):
        values = re.findall(r'^'+key+r'=(.+)$', raw, re.M)
        require(len(values) == 1 and values[0] in ('0', '0.0'), 'Wrong/missing API26 '+key)
    return dict(api='26', serial='emulator-5554', effective_size=[1080, 1920], effective_density=420,
                animation_scales={key: 0.0 for key in ('animator_duration_scale', 'transition_animation_scale', 'window_animation_scale')})


def expected_player_phases():
    phases = ['composition-enter:empty-lyrics']
    for layout in ('FullBackground', 'Floating'):
        suffix = 'empty-lyrics:'+layout
        phases += ['layout-begin:'+suffix, 'capture-content-begin:'+suffix, 'capture-content-end:'+suffix,
                   'semantics-begin:'+layout+':held-empty-lyrics', 'semantics-end:'+layout+':held-empty-lyrics',
                   'capture-player-begin:'+suffix, 'capture-player-end:'+suffix,
                   'semantics-begin:'+layout+':released-empty-lyrics', 'semantics-end:'+layout+':released-empty-lyrics',
                   'release-equalities-end:'+suffix]
        for progress in ('0.99','0.96','0.92','0.86','0.8','0.78','0.5','0.78','0.8','0.86','0.92','0.96','0.99','1.0'):
            phases += ['sweep-begin:'+suffix+':'+progress, 'sweep-end:'+suffix+':'+progress]
        phases += ['layout-end:'+suffix]
    return phases + ['finally-begin:empty-lyrics:fingerDown=false', 'finally-end:empty-lyrics', 'composition-dispose:empty-lyrics']


def validate_player_evidence(source, evidence):
    """Check original source-emitted lifecycle/semantic coverage, not causal ownership."""
    source, evidence = Path(source), Path(evidence)
    raw = data(evidence/'device.txt').decode()
    require(read_json(evidence/'device.json') == validate_device(raw), 'Device receipt differs from originals')
    all_logs = data(evidence/'logcat-all.original.log').decode(errors='strict')
    events = []
    pids = set()
    for line in all_logs.splitlines():
        if ' I PlayerExpansionPhase:' not in line:
            continue
        match = re.fullmatch(r'\S+\s+\S+\s+(\d+)\s+\d+ I PlayerExpansionPhase:\s+ns=(\d+) thread=.+ phase=(\S+) processReadbackActiveChildren=(\d+)', line)
        require(match is not None, 'Malformed player lifecycle trace')
        pid, ns, phase, count = match.groups()
        pids.add(pid); events.append((int(ns), phase, int(count)))
    require(len(pids) == 1 and [phase for _,phase,_ in events] == expected_player_phases(), 'Incomplete/replayed/out-of-order player lifecycle')
    require(all(left[0] < right[0] for left,right in zip(events, events[1:])), 'Nonmonotonic player lifecycle')
    # This counter is retained for diagnosis only; zero is not a native completion fence.
    routes = re.findall(r'I ReadbackFixture:\s+api=(\d+) nativeCompletedBeforeOwnerRelease=(\w+)', all_logs)
    require(routes == [('26', 'false')]*3, 'Missing/contradictory API26 pending native-copy route evidence')
    roots = [source/'app/build/outputs/connected_android_test_additional_output', evidence/'device-output']
    originals = {}
    for layout in ('FullBackground', 'Floating'):
        for stage in ('held', 'released'):
            name = layout+'-'+stage+'-empty-lyrics.txt'
            candidates = [p for root in roots for p in root.glob('**/player-expansion-motion/'+name)]
            require(candidates, 'Missing original player semantics: '+name)
            hashes = {digest(p) for p in candidates}
            require(len(hashes) == 1, 'Contradictory duplicate player semantics: '+name)
            for path in candidates:
                body = data(path).decode()
                require(body.startswith('MERGED\n') and '\nUNMERGED\n' in body and 'empty-lyrics' in body and 'lyrics-panel' in body and 'player-surface' in body,
                        'Incomplete original player semantics: '+name)
            originals[name] = next(iter(hashes))
    return dict(player_phase_count=len(events), player_log_sha256=digest(evidence/'logcat-all.original.log'),
                semantics_sha256=originals, pending_native_routes=len(routes), native_ownership_causality_proven=False)
