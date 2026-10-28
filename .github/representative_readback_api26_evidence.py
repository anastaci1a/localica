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
    require(env['source_sha'] == '88694c546bed72b027c18b6df3df14b16ada7412' and env['baseline_run'] == '40593355374', 'Wrong baseline provenance')
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


CASE_NAMES = ['a_directImageReaderSmallControl', 'b_directImageReaderRepresentativeControl',
              'c_composeLayerRepresentativeControl', 'd_retainedLayerRepresentativeControl',
              'e_retainedLayerRepresentativeCancellationResize']
ROUTES = ['android-imagereader', 'android-imagereader', 'compose-layer', 'retained-layer', 'retained-mutation']


def expected_representative_records():
    records = []
    for index, case in enumerate(CASE_NAMES):
        mutation = index == 4
        width, height = (64, 64) if index == 0 else (1067 if mutation else 1066, 1731)
        events = [dict(event='begin', route=ROUTES[index], width=width, height=height, copies=2)]
        for generation in (0, 1):
            w = 1066 if mutation and generation == 1 else width
            if index >= 3: events.append(dict(event='lease', generation=generation, stage='ACQUIRED'))
            if mutation and generation == 0:
                events += [dict(event='native_pending', generation=0, pending=True),
                           dict(event='successor_blocked', blocked=True, source_live=True, parent_live=True)]
            if index < 2:
                events.append(dict(event='plane', generation=generation, width=w, height=height,
                                   row_stride=w*4, pixel_stride=4, remaining_bytes=w*height*4))
            events.append(dict(event='copied', generation=generation, width=w, height=height))
            if index >= 3:
                events += [dict(event='lease', generation=generation, stage=stage) for stage in ('COPIED', 'RELEASED')]
            if not mutation or generation == 1:
                events.append(dict(event='verified', generation=generation, width=w, height=height,
                                   pixel_count=w*height, argb=-65536 if generation == 0 else -16776961))
        end = dict(event='end', completed_copies=2, verified_copies=1 if mutation else 2,
                   owned_resources_released=True, failures=0)
        if mutation: end.update(first_caller_cancelled=True, old_publications=0)
        events.append(end)
        records += [dict(schema=1, case=case, sequence=sequence, api=26, **event) for sequence, event in enumerate(events)]
    return records


def _read_representative(raw, require_complete):
    expected = expected_representative_records()
    records, lines, physical, duplicates, seen, pids = [], [], [], [], {}, set()
    previous_ns = 0
    def unique(pairs):
        result = {}
        for key, value in pairs:
            require(key not in result, 'Duplicate diagnostic JSON field')
            result[key] = value
        return result
    for number, line in enumerate(raw.splitlines(keepends=True), 1):
        if 'RepresentativeReadback' not in line: continue
        match = re.fullmatch(r'\S+\s+\S+\s+(\d+)\s+\d+ I RepresentativeReadback:\s+(.*)\n', line)
        require(match is not None, 'Malformed/error/truncated representative record')
        value = json.loads(match.group(2), object_pairs_hook=unique)
        require(isinstance(value, dict) and isinstance(value.get('case'), str) and type(value.get('sequence')) is int,
                'Invalid representative identity')
        key = (value['case'], value['sequence'])
        if key in seen:
            first = seen[key]
            require(line == lines[first], 'Conflicting repeated representative record')
            duplicates.append(dict(case=key[0], sequence=key[1], first_line=physical[first], repeated_line=number,
                                   raw_line_sha256=hashlib.sha256(line.encode()).hexdigest()))
            continue
        require(len(records) < len(expected), 'Unexpected extra representative record')
        want = expected[len(records)]
        require(set(value) == set(want) | {'elapsed_ns', 'thread'}, 'Wrong representative receipt fields')
        for field, exact in want.items():
            if want['event'] == 'plane' and field in ('row_stride', 'remaining_bytes'): continue
            require(type(value[field]) is type(exact) and value[field] == exact, 'Wrong representative value/order: '+field)
        if want['event'] == 'plane':
            require(type(value['row_stride']) is int and value['row_stride'] >= want['width']*4, 'Invalid plane row stride')
            require(type(value['remaining_bytes']) is int and value['remaining_bytes'] >= want['width']*want['height']*4, 'Insufficient plane buffer')
        ns = value['elapsed_ns']; thread = value['thread']
        require(type(ns) is int and ns > previous_ns, 'Nonmonotonic representative timestamp')
        require(isinstance(thread, str) and (thread.startswith('Instr:') if value['event'] in ('begin','end') else thread == 'main'),
                'Wrong representative receipt thread')
        previous_ns = ns; pids.add(match.group(1)); require(len(pids) == 1, 'Representative records span processes')
        seen[key] = len(records); records.append(value); lines.append(line); physical.append(number)
    complete = len(records) == len(expected)
    if require_complete: require(complete, 'Incomplete representative diagnostic receipts')
    return records, lines, duplicates, complete


def validate_representative_evidence(evidence, require_complete=True):
    """Strict focused receipts only; does not replace original player/full-suite gates."""
    evidence = Path(evidence)
    original = data(evidence/'logcat-all.original.log').decode(errors='strict')
    records, lines, duplicates, complete = _read_representative(original, require_complete)
    replay = dict(duplicate_count=len(duplicates), duplicates=duplicates, independently_corroborated=False)
    if duplicates:
        root = evidence/'guest-after'
        snapshot = data(root/'logcat-all.stdout').decode(errors='strict')
        require(snapshot.endswith('\n'), 'Independent snapshot truncated final line')
        receipt = read_json(root/'logcat-all.json'); status = read_json(root/'snapshot.json')
        require(receipt.get('argv') == ['adb','-s','emulator-5554','logcat','-b','all','-d','-v','threadtime']
                and type(receipt.get('returncode')) is int and receipt['returncode'] == 0, 'Independent snapshot command failed')
        require(receipt.get('stdout_sha256') == digest(root/'logcat-all.stdout')
                and receipt.get('stderr_sha256') == digest(root/'logcat-all.stderr')
                and not data(root/'logcat-all.stderr'), 'Independent snapshot hash/stderr mismatch')
        require(status.get('healthy') is True and status.get('statuses', {}).get('logcat-all') == receipt,
                'Independent snapshot incomplete/unhealthy')
        _, corroborated, repeated, _ = _read_representative(snapshot, True)
        require(not repeated and corroborated == lines, 'Independent snapshot records differ/missing/replayed')
        replay.update(independently_corroborated=True, snapshot_sha256=digest(root/'logcat-all.stdout'),
                      snapshot_receipt_sha256=digest(root/'logcat-all.json'))
    return dict(schema_version=1, status='all-focused-receipts-observed' if complete else 'incomplete-nonpassing-prefix',
                records=len(records), completed_cases=[r['case'] for r in records if r['event'] == 'end'],
                verified_images=[{k:r[k] for k in ('case','generation','width','height','pixel_count','argb')} for r in records if r['event']=='verified'],
                planes=[{k:r[k] for k in ('case','generation','width','height','row_stride','pixel_stride','remaining_bytes')} for r in records if r['event']=='plane'],
                original_sha256=digest(evidence/'logcat-all.original.log'), transport_replay=replay,
                receipt_sequence_complete=complete, native_success_proven=False, full_suite_pass=False,
                original_player_gate_replaced=False, causal_claim=False)
