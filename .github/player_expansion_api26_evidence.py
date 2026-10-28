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
    require(env['source_sha'] == '0e45152dfe0dbd1adb089b583f35264c34349814' and env['baseline_run'] == '40593355374', 'Wrong baseline provenance')
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


def validate_draw_probe_evidence(evidence, require_complete=True):
    """Validate passive scalar draw evidence; never a native-consumption/lifetime fence.

    A failed native run may be summarized with require_complete=False. Such a
    prefix remains explicitly incomplete and cannot satisfy the passing reconciler.
    """
    evidence = Path(evidence)
    raw = data(evidence/'logcat-all.original.log').decode(errors='strict')
    records, phases, pids = [], [], set()
    common = {'schema', 'event', 'sequence', 'elapsed_ns', 'thread', 'content', 'layout', 'progress'}
    draw_keys = {'observed_uptime_ms', 'surface_id', 'view_id', 'attached', 'visible', 'width', 'height',
                 'root_corners', 'silhouette', 'shadow_enabled', 'source', 'home'}
    source_keys = {'source_id', 'parent_source_id', 'layer_id', 'parent_layer_id', 'recorded', 'released',
                   'parent_recorded', 'parent_released', 'width', 'height', 'layer_width', 'layer_height',
                   'revision', 'requested_readback', 'published_readback', 'published_draw_request',
                   'published_composite', 'snapshot_id', 'frost_id', 'lens_id', 'source_to_consumer'}
    def integer(value, minimum=None):
        return type(value) is int and (minimum is None or value >= minimum)
    def finite(value):
        return type(value) in (int, float) and math.isfinite(value)
    def vector(value, length):
        return value is None or isinstance(value, list) and len(value) == length and all(finite(v) for v in value)
    def source(value):
        if value is None: return
        require(isinstance(value, dict) and set(value) == source_keys, 'Malformed draw source fields')
        for key in ('source_id', 'layer_id'): require(integer(value[key]), 'Invalid source identity '+key)
        for key in ('parent_source_id', 'parent_layer_id', 'published_draw_request', 'published_composite', 'snapshot_id', 'frost_id', 'lens_id'):
            require(value[key] is None or integer(value[key]), 'Invalid optional source scalar '+key)
        for key in ('recorded', 'released'): require(type(value[key]) is bool, 'Invalid source flag '+key)
        for key in ('parent_recorded', 'parent_released'): require(value[key] is None or type(value[key]) is bool, 'Invalid parent flag '+key)
        for key in ('width', 'height', 'layer_width', 'layer_height', 'revision', 'requested_readback'):
            require(integer(value[key], 0), 'Invalid source dimension/generation '+key)
        require(integer(value['published_readback'], -1), 'Invalid publication generation')
        require(vector(value['source_to_consumer'], 6), 'Invalid source registration')
    def unique(pairs):
        value = {}
        for key, item in pairs:
            require(key not in value, 'Duplicate draw JSON key')
            value[key] = item
        return value
    for line in raw.splitlines():
        if ' PlayerExpansionDraw:' in line:
            match = re.fullmatch(r'\S+\s+\S+\s+(\d+)\s+\d+ ([A-Z]) PlayerExpansionDraw:\s+(.*)', line)
            require(match is not None, 'Malformed/truncated draw log record')
            pid, level, body = match.groups()
            value = json.loads(body, object_pairs_hook=unique)
            require(isinstance(value, dict) and value.get('event') != 'observation_error' and level == 'I', 'Passive observer reported an error')
            event = value.get('event')
            require(event in ('begin', 'draw', 'disposed'), 'Unknown draw observer event')
            extra = draw_keys if event == 'draw' else {'observations', 'failed'} if event == 'disposed' else set()
            require(set(value) == common | extra, 'Malformed draw observer fields')
            require(value['schema'] == 1 and type(value['schema']) is int, 'Wrong draw observer schema')
            require(integer(value['sequence'], 0) and value['sequence'] == len(records), 'Missing/replayed/out-of-order draw observation')
            require(integer(value['elapsed_ns'], 1), 'Invalid draw timestamp')
            require(not records or value['elapsed_ns'] > records[-1]['elapsed_ns'], 'Nonmonotonic draw timestamp')
            require(value['content'] == 'empty-lyrics' and value['layout'] in ('FullBackground', 'Floating'), 'Wrong draw scenario')
            require(isinstance(value['thread'], str) and value['thread'], 'Missing draw thread')
            require(finite(value['progress']) and 0 <= value['progress'] <= 1, 'Invalid draw progress')
            if event == 'draw':
                require(value['thread'] == 'main', 'Draw observation on wrong thread')
                require(integer(value['observed_uptime_ms'], 0), 'Invalid draw uptime')
                for key in ('surface_id', 'view_id'): require(integer(value[key]), 'Invalid surface identity')
                for key in ('attached', 'visible', 'silhouette', 'shadow_enabled'): require(type(value[key]) is bool, 'Invalid surface flag')
                for key in ('width', 'height'): require(finite(value[key]) and value[key] >= 0, 'Invalid surface extent')
                require(vector(value['root_corners'], 8), 'Invalid surface coordinates')
                source(value['source']); source(value['home'])
            if event == 'disposed':
                require(value['thread'] == 'main' and value['failed'] is False, 'Invalid observer disposal')
                require(integer(value['observations'], 0) and value['observations'] == sum(r['event'] == 'draw' for r in records), 'Incomplete draw count')
            pids.add(pid); records.append(value)
        elif ' I PlayerExpansionPhase:' in line:
            match = re.fullmatch(r'\S+\s+\S+\s+(\d+)\s+\d+ I PlayerExpansionPhase:\s+ns=(\d+) thread=.+ phase=(\S+) processReadbackActiveChildren=(\d+)', line)
            require(match is not None, 'Malformed player phase in draw correlation')
            pid, ns, phase, _ = match.groups(); pids.add(pid); phases.append((int(ns), phase))
    require(records and records[0]['event'] == 'begin', 'Missing draw observer begin')
    require(len(pids) == 1, 'Draw and phase observations span processes')
    require(sum(r['event'] == 'begin' for r in records) == 1, 'Duplicate draw observer begin')
    disposals = [r for r in records if r['event'] == 'disposed']
    require(len(disposals) <= 1 and (not disposals or records[-1] is disposals[0]), 'Draw observations after disposal')
    require(not phases or records[0]['elapsed_ns'] < phases[0][0], 'Draw observer begins after scenario')
    complete = bool(disposals) and bool(phases) and phases[-1][1] == 'composition-dispose:empty-lyrics' and disposals[0]['elapsed_ns'] > phases[-1][0]
    draws = [r for r in records if r['event'] == 'draw']
    witnesses = {}
    for layout in ('FullBackground', 'Floating'):
        layout_samples = {}
        begin78 = [(ns, i) for i, (ns, phase) in enumerate(phases) if phase == 'sweep-begin:empty-lyrics:'+layout+':0.78']
        begin50 = [(ns, i) for i, (ns, phase) in enumerate(phases) if phase == 'sweep-begin:empty-lyrics:'+layout+':0.5']
        require(len(begin78) <= 2 and len(begin50) <= 1, 'Ambiguous critical sweep observation')
        for label, candidates, expected, predecessor in (
            ('descending_078', begin78[:1], .78, 'sweep-end:empty-lyrics:'+layout+':0.8'),
            ('middle_05', begin50, .5, 'sweep-end:empty-lyrics:'+layout+':0.78'),
            ('increasing_078', begin78[1:], .78, 'sweep-end:empty-lyrics:'+layout+':0.5')):
            if not candidates:
                layout_samples[label] = {'status': 'unobserved'}; continue
            start, index = candidates[0]
            require(index > 0 and phases[index-1][1] == predecessor, 'Critical draw phase lacks expected predecessor')
            end_name = 'sweep-end:empty-lyrics:'+layout+(':'+str(expected))
            end = phases[index+1][0] if index+1 < len(phases) and phases[index+1][1] == end_name else None
            samples = [r for r in draws if r['layout'] == layout and r['elapsed_ns'] >= start and (end is None or r['elapsed_ns'] <= end)
                       and abs(r['progress']-expected) <= .00001 and r['attached'] and r['visible']]
            # At .78 the compound lyric header actually draws and names its source.
            # At .5 that header is uncomposed; the base surface is still observable.
            eligible = [r for r in samples if expected == .5 or r['source'] is not None and r['silhouette']]
            scalars = [{'sequence': r['sequence'], 'surface_id': r['surface_id'], 'silhouette': r['silhouette'],
                        'source_id': None if r['source'] is None else r['source']['source_id'],
                        'layer_id': None if r['source'] is None else r['source']['layer_id'],
                        'requested_readback': None if r['source'] is None else r['source']['requested_readback'],
                        'published_readback': None if r['source'] is None else r['source']['published_readback']} for r in samples]
            layout_samples[label] = {'status': 'observed' if eligible and end is not None else 'inconclusive',
                                     'phase_start_ns': start, 'phase_end_ns': end, 'draws': len(samples), 'scalars': scalars}
        witnesses[layout] = layout_samples
    informative = complete and all(w['status'] == 'observed' for layout in witnesses.values() for w in layout.values())
    if require_complete: require(informative, 'Incomplete/inconclusive passive draw observation')
    return {'schema_version': 1, 'status': 'required-witnesses-observed' if informative else 'incomplete-or-inconclusive',
            'records': len(records), 'draws': len(draws), 'observer_disposed': bool(disposals),
            'scenario_complete': complete, 'witnesses': witnesses, 'original_sha256': digest(evidence/'logcat-all.original.log'),
            'native_completion_proven': False, 'resource_ownership_proven': False, 'causal_claim': False}
