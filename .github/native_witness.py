"""Strict retained-timeline evidence checks; not a Kotlin executor or optical oracle.

Generation is reconstructed from the one exact-source probe's arm/focus-loss/detach
records, not an independently logged native window token. The enclosing harness must
bind exact source, API, method, run and original artifact identity before calling.
"""
import hashlib
import math
import re

EVIDENCE = re.compile(
    r'NativeReturnEvidence\(uptime=(\d+), virtual=(\d+), requested=(null|\d+), '
    r'progressAt=(null|\d+), attached=(true|false), focused=(true|false), '
    r'ime=(\d+), navigation=(\d+), targetVisible=(null|true|false), '
    r'targetIme=(null|\d+), activeHideIds=\[([^\]]*)\]\)')
FIELDS = ('uptime','virtual','requested','progressAt','attached','focused','ime','navigation','targetVisible','targetIme','activeHideIds')
SAMPLE = re.compile(r'^(\d+) SearchMorphSample\(progress=([^,]+), opening=(true|false), ')
EVENT = re.compile(r'^(\d+) nativeReturn phase=(\S+) (.*?) (NativeReturnEvidence\(.*\))$')
CALLBACK = re.compile(r'animation=(null|-?\d+) type=(null|\d+)')
PLACED = re.compile(r'sample=(\d+) progress=(\S+) opening=(true|false)')


def _value(s):
    if s == 'null': return None
    if s in ('true','false'): return s == 'true'
    return int(s)


def parse_evidence(text):
    match = EVIDENCE.fullmatch(text)
    if not match: raise ValueError('Malformed native evidence record')
    values = list(match.groups())
    ids = values.pop()
    if ids and not re.fullmatch(r'-?\d+(?:, -?\d+)*', ids):
        raise ValueError('Malformed animation IDs')
    ids = [] if not ids else [int(v) for v in ids.split(', ')]
    if len(ids) != len(set(ids)): raise ValueError('Duplicate animation IDs')
    return dict(zip(FIELDS, [*map(_value,values),ids]))


def validate_timeline(raw):
    """Return source-bound observational witnesses, or raise ValueError; never rewrite raw."""
    if not isinstance(raw, bytes): raise ValueError('Original bytes required')
    text = raw.decode('utf-8', errors='strict')
    requested = None
    armed = False
    generation = 0
    prepared, active, progressed = set(), set(), set()
    progress_at = None
    animated = (0, 0)
    latest_sample = None
    sample_count = 0
    previous_time = -1
    witnesses = []
    events = 0
    def reset():
        nonlocal prepared, active, progressed, progress_at, animated, generation
        prepared, active, progressed = set(), set(), set()
        progress_at, animated = None, (0, 0)
        generation += 1
    for line_no, line in enumerate(text.splitlines(), 1):
        stamp = re.match(r'^(\d+) ',line)
        if stamp:
            now = int(stamp[1])
            if now < previous_time: raise ValueError('Timeline uptime regressed')
            previous_time = now
        sample = SAMPLE.match(line)
        if sample:
            p = float(sample[2])
            if not math.isfinite(p): raise ValueError('Nonfinite placed progress')
            latest_sample = (sample_count,p,sample[3] == 'true',line_no,int(sample[1]))
            sample_count += 1
            continue
        if 'nativeReturn' not in line:
            latest_sample = None
            continue
        event = EVENT.fullmatch(line)
        if not event: raise ValueError('Malformed nativeReturn line')
        events += 1
        stamp, phase, detail, evidence_text = event.groups()
        e = parse_evidence(evidence_text)
        if e['uptime'] != int(stamp): raise ValueError('Record timestamp mismatch')
        ids = set(e['activeHideIds'])
        if phase == 'placed':
            m = PLACED.fullmatch(detail)
            if not m or latest_sample is None: raise ValueError('Placed record lacks adjacent source sample')
            index,p,opening,source_line,sample_time = latest_sample
            if (int(m[1]),float(m[2]),m[3] == 'true') != (index,p,opening):
                raise ValueError('Placed association mismatch')
            if sample_time > e['uptime']: raise ValueError('Placed snapshot predates sample')
            if e['requested'] != requested: raise ValueError('Placed arm mismatch')
            if ids != progressed or e['progressAt'] != progress_at or (e['ime'],e['navigation']) != animated:
                raise ValueError('Placed snapshot borrows stale or unobserved progress')
            if (armed and not opening and .01 <= p < .99 and requested is not None
                and progress_at is not None and requested <= progress_at <= e['uptime']
                and e['attached'] and e['focused'] and e['targetVisible'] is False
                and e['targetIme'] == 0 and e['ime'] > e['navigation'] and ids
                and ids <= active and ids <= prepared):
                witnesses.append(dict(line=line_no,sample_line=source_line,sample_index=index,
                    generation=generation,animation_ids=sorted(ids),progress=p,evidence=e))
        else:
            m = CALLBACK.fullmatch(detail)
            if not m: raise ValueError('Malformed callback detail')
            animation, kind = map(_value,m.groups())
            if phase == 'dismissal-armed':
                if armed or animation is not None or kind is not None: raise ValueError('Rearmed/invalid probe')
                reset(); armed = True; requested = e['requested']
                if requested is None or requested > e['uptime']: raise ValueError('Arm timestamp mismatch')
            elif phase in ('focus=false','detach'):
                reset()
                # dispose clears requested before removing its view; normal loss retains it.
                if phase == 'detach' and e['requested'] is None: requested = None
            elif phase == 'focus=true': pass
            elif phase == 'prepare':
                if animation is None or kind is None: raise ValueError('Missing prepare identity')
                if armed and requested is not None and e['uptime'] >= requested and kind & 8:
                    prepared.add(animation)
            elif phase == 'start':
                if animation is None or kind is None: raise ValueError('Missing start identity')
                if kind & 8:
                    progressed, progress_at = set(), None
                    if (animation in prepared and requested is not None and e['uptime'] >= requested
                        and e['targetVisible'] is False and e['targetIme'] == 0): active.add(animation)
            elif phase == 'progress':
                if animation is not None or kind is not None: raise ValueError('Unexpected progress identity')
                if not ids <= active: raise ValueError('Unadmitted progress identity')
                progressed = ids
                progress_at = e['progressAt']
                if ids and (progress_at is None or not requested <= progress_at <= e['uptime']):
                    raise ValueError('Invalid native progress time')
                if not ids and progress_at is not None: raise ValueError('Progress without matching active hide')
                animated = (e['ime'],e['navigation'])
            elif phase == 'end':
                if animation is None or kind is None: raise ValueError('Missing end identity')
                prepared.discard(animation); active.discard(animation); progressed.discard(animation)
                if not progressed: progress_at = None
            else: raise ValueError('Unknown native phase')
            if e['requested'] != requested: raise ValueError('Callback arm mismatch')
            if ids != progressed or e['progressAt'] != progress_at:
                raise ValueError('Callback state contradicts lifecycle')
            if (e['ime'],e['navigation']) != animated: raise ValueError('Unobserved animated insets')
        latest_sample = None
    if not witnesses: raise ValueError('No independent native hide overlap on an intermediate placed return')
    return dict(schema_version=1,raw_sha256=hashlib.sha256(raw).hexdigest(),raw_bytes=len(raw),
        event_count=events,sample_count=sample_count,witnesses=witnesses,
        generation_basis='exact-source single probe lifecycle; no independently logged native window token',
        causal_proof=False,optical_acceptance=False,timing_equivalence=False)
