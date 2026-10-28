#!/usr/bin/env python3
"""Fail-closed evidence reconciliation for one focused-validation API26/36 case at one immutable source.
No result is release certification, optical acceptance, or a causal comparison. Never modifies original evidence.
"""
import argparse
import hashlib
import json
import math
from pathlib import Path
import re
import subprocess
import sys
import xml.etree.ElementTree as ET

PREFIX = 'io.github.anastaci1a.localica.'
TEST_CLASS = PREFIX + 'PlayerBackgroundPolarityTest'
PIN = '1e31403cb042e9896686200a4800faffce9b2a59'
SOURCE_PIN = '68046eb20e519f51efd68f2338c945ffcc6f77fb'
SELECTED_METHODS = ['io.github.anastaci1a.localica.GlassMenuContentTest#constrainedMenuRetainsScrollRangeAndLastActionReachability', 'io.github.anastaci1a.localica.GlassMenuContentTest#fittingMenuPreservesGeometryAndTargetsWithoutInsetShadowCuts', 'io.github.anastaci1a.localica.GlassMenuViewportTouchTest#longMenuTouchScrollsToActualBoundaryThenTapsDismissesAndReopens', 'io.github.anastaci1a.localica.PlayerBackgroundPolarityTest#interruptedFrozenRoleIsExplicitlyStaleAndRecoversOnlyFromLiveSuccessor', 'io.github.anastaci1a.localica.PlayerBackgroundPolarityTest#maximumTrimDoesNotLockAColdWhiteBackgroundToWhiteInk', 'io.github.anastaci1a.localica.PlayerBackgroundPolarityTest#nativeBlurRetainsVerifiedInkAsDistinctUnavailableThenRecovers', 'io.github.anastaci1a.localica.PlayerBackgroundPolarityTest#nonzeroTrimPreservesThePre473BackingWhenInkDiffersFromPalettePolarity', 'io.github.anastaci1a.localica.PlayerBackgroundPolarityTest#opacityTransitionIsUnavailableUntilTheSuccessorIsSettled', 'io.github.anastaci1a.localica.PlayerBackgroundPolarityTest#oppositeCoversUseVisibleOutgoingIncomingWeightsAndOnePlayerQueueDecision', 'io.github.anastaci1a.localica.PlayerBackgroundPolarityTest#sameCoverVisitKeepsTheRealFadeAndItsPolarityAndRapidSuccessorCannotPublishOldInk', 'io.github.anastaci1a.localica.PlayerBackgroundPolarityTest#whiteCoverBlackLowerBorderUsesDirectAndContinuationFootprintsAtShortTallAndTranslatedInsets', 'io.github.anastaci1a.localica.PlayerBackgroundPolarityTest#zeroTrimControlHasIdenticalBackingForBothToneDirections']
DECLARED_INVENTORY = {'classes': [{'class': 'io.github.anastaci1a.localica.GlassMenuContentTest',
              'declarations': [{'id': 'io.github.anastaci1a.localica.GlassMenuContentTest#fittingMenuPreservesGeometryAndTargetsWithoutInsetShadowCuts',
                                'line': 37},
                               {'id': 'io.github.anastaci1a.localica.GlassMenuContentTest#constrainedMenuRetainsScrollRangeAndLastActionReachability',
                                'line': 86}],
              'methods': ['io.github.anastaci1a.localica.GlassMenuContentTest#fittingMenuPreservesGeometryAndTargetsWithoutInsetShadowCuts',
                          'io.github.anastaci1a.localica.GlassMenuContentTest#constrainedMenuRetainsScrollRangeAndLastActionReachability'],
              'sha256': 'a49b00da270da2e47b3495a7b8c9545d5c767f061a2b7620a59cbbe6e482f553',
              'source': 'app/src/androidTest/java/io/github/anastaci1a/localica/GlassMenuContentTest.kt'},
             {'class': 'io.github.anastaci1a.localica.GlassMenuViewportTouchTest',
              'declarations': [{'id': 'io.github.anastaci1a.localica.GlassMenuViewportTouchTest#longMenuTouchScrollsToActualBoundaryThenTapsDismissesAndReopens',
                                'line': 62}],
              'methods': ['io.github.anastaci1a.localica.GlassMenuViewportTouchTest#longMenuTouchScrollsToActualBoundaryThenTapsDismissesAndReopens'],
              'sha256': 'f309028c55ae88080679c339a42a4c95dc0380a9c95dae024464ca567f6bf61a',
              'source': 'app/src/androidTest/java/io/github/anastaci1a/localica/GlassMenuViewportTouchTest.kt'},
             {'class': 'io.github.anastaci1a.localica.PlayerBackgroundPolarityTest',
              'declarations': [{'id': 'io.github.anastaci1a.localica.PlayerBackgroundPolarityTest#zeroTrimControlHasIdenticalBackingForBothToneDirections',
                                'line': 154},
                               {'id': 'io.github.anastaci1a.localica.PlayerBackgroundPolarityTest#nonzeroTrimPreservesThePre473BackingWhenInkDiffersFromPalettePolarity',
                                'line': 156},
                               {'id': 'io.github.anastaci1a.localica.PlayerBackgroundPolarityTest#whiteCoverBlackLowerBorderUsesDirectAndContinuationFootprintsAtShortTallAndTranslatedInsets',
                                'line': 195},
                               {'id': 'io.github.anastaci1a.localica.PlayerBackgroundPolarityTest#oppositeCoversUseVisibleOutgoingIncomingWeightsAndOnePlayerQueueDecision',
                                'line': 216},
                               {'id': 'io.github.anastaci1a.localica.PlayerBackgroundPolarityTest#opacityTransitionIsUnavailableUntilTheSuccessorIsSettled',
                                'line': 239},
                               {'id': 'io.github.anastaci1a.localica.PlayerBackgroundPolarityTest#interruptedFrozenRoleIsExplicitlyStaleAndRecoversOnlyFromLiveSuccessor',
                                'line': 255},
                               {'id': 'io.github.anastaci1a.localica.PlayerBackgroundPolarityTest#maximumTrimDoesNotLockAColdWhiteBackgroundToWhiteInk',
                                'line': 278},
                               {'id': 'io.github.anastaci1a.localica.PlayerBackgroundPolarityTest#nativeBlurRetainsVerifiedInkAsDistinctUnavailableThenRecovers',
                                'line': 294},
                               {'id': 'io.github.anastaci1a.localica.PlayerBackgroundPolarityTest#sameCoverVisitKeepsTheRealFadeAndItsPolarityAndRapidSuccessorCannotPublishOldInk',
                                'line': 311}],
              'methods': ['io.github.anastaci1a.localica.PlayerBackgroundPolarityTest#zeroTrimControlHasIdenticalBackingForBothToneDirections',
                          'io.github.anastaci1a.localica.PlayerBackgroundPolarityTest#nonzeroTrimPreservesThePre473BackingWhenInkDiffersFromPalettePolarity',
                          'io.github.anastaci1a.localica.PlayerBackgroundPolarityTest#whiteCoverBlackLowerBorderUsesDirectAndContinuationFootprintsAtShortTallAndTranslatedInsets',
                          'io.github.anastaci1a.localica.PlayerBackgroundPolarityTest#oppositeCoversUseVisibleOutgoingIncomingWeightsAndOnePlayerQueueDecision',
                          'io.github.anastaci1a.localica.PlayerBackgroundPolarityTest#opacityTransitionIsUnavailableUntilTheSuccessorIsSettled',
                          'io.github.anastaci1a.localica.PlayerBackgroundPolarityTest#interruptedFrozenRoleIsExplicitlyStaleAndRecoversOnlyFromLiveSuccessor',
                          'io.github.anastaci1a.localica.PlayerBackgroundPolarityTest#maximumTrimDoesNotLockAColdWhiteBackgroundToWhiteInk',
                          'io.github.anastaci1a.localica.PlayerBackgroundPolarityTest#nativeBlurRetainsVerifiedInkAsDistinctUnavailableThenRecovers',
                          'io.github.anastaci1a.localica.PlayerBackgroundPolarityTest#sameCoverVisitKeepsTheRealFadeAndItsPolarityAndRapidSuccessorCannotPublishOldInk'],
              'sha256': 'd0711f4f37eae395edfe800798fa8db6e1587318661a5ebdfe3ccd754c969c89',
              'source': 'app/src/androidTest/java/io/github/anastaci1a/localica/PlayerBackgroundPolarityTest.kt'}],
 'limits': 'Static direct JUnit4 declarations only. Runtime missing/extra/duplicate IDs must fail closed; '
           'inherited/generated/parameterized/SDK-filter discovery is not inferred.',
 'method_count': 12,
 'methods': ['io.github.anastaci1a.localica.GlassMenuContentTest#constrainedMenuRetainsScrollRangeAndLastActionReachability',
             'io.github.anastaci1a.localica.GlassMenuContentTest#fittingMenuPreservesGeometryAndTargetsWithoutInsetShadowCuts',
             'io.github.anastaci1a.localica.GlassMenuViewportTouchTest#longMenuTouchScrollsToActualBoundaryThenTapsDismissesAndReopens',
             'io.github.anastaci1a.localica.PlayerBackgroundPolarityTest#interruptedFrozenRoleIsExplicitlyStaleAndRecoversOnlyFromLiveSuccessor',
             'io.github.anastaci1a.localica.PlayerBackgroundPolarityTest#maximumTrimDoesNotLockAColdWhiteBackgroundToWhiteInk',
             'io.github.anastaci1a.localica.PlayerBackgroundPolarityTest#nativeBlurRetainsVerifiedInkAsDistinctUnavailableThenRecovers',
             'io.github.anastaci1a.localica.PlayerBackgroundPolarityTest#nonzeroTrimPreservesThePre473BackingWhenInkDiffersFromPalettePolarity',
             'io.github.anastaci1a.localica.PlayerBackgroundPolarityTest#opacityTransitionIsUnavailableUntilTheSuccessorIsSettled',
             'io.github.anastaci1a.localica.PlayerBackgroundPolarityTest#oppositeCoversUseVisibleOutgoingIncomingWeightsAndOnePlayerQueueDecision',
             'io.github.anastaci1a.localica.PlayerBackgroundPolarityTest#sameCoverVisitKeepsTheRealFadeAndItsPolarityAndRapidSuccessorCannotPublishOldInk',
             'io.github.anastaci1a.localica.PlayerBackgroundPolarityTest#whiteCoverBlackLowerBorderUsesDirectAndContinuationFootprintsAtShortTallAndTranslatedInsets',
             'io.github.anastaci1a.localica.PlayerBackgroundPolarityTest#zeroTrimControlHasIdenticalBackingForBothToneDirections'],
 'runtime_discovery_proven': False,
 'runtime_methods': ['io.github.anastaci1a.localica.GlassMenuContentTest#constrainedMenuRetainsScrollRangeAndLastActionReachability',
                     'io.github.anastaci1a.localica.GlassMenuContentTest#fittingMenuPreservesGeometryAndTargetsWithoutInsetShadowCuts',
                     'io.github.anastaci1a.localica.GlassMenuViewportTouchTest#longMenuTouchScrollsToActualBoundaryThenTapsDismissesAndReopens',
                     'io.github.anastaci1a.localica.PlayerBackgroundPolarityTest#interruptedFrozenRoleIsExplicitlyStaleAndRecoversOnlyFromLiveSuccessor',
                     'io.github.anastaci1a.localica.PlayerBackgroundPolarityTest#maximumTrimDoesNotLockAColdWhiteBackgroundToWhiteInk',
                     'io.github.anastaci1a.localica.PlayerBackgroundPolarityTest#nativeBlurRetainsVerifiedInkAsDistinctUnavailableThenRecovers',
                     'io.github.anastaci1a.localica.PlayerBackgroundPolarityTest#nonzeroTrimPreservesThePre473BackingWhenInkDiffersFromPalettePolarity',
                     'io.github.anastaci1a.localica.PlayerBackgroundPolarityTest#opacityTransitionIsUnavailableUntilTheSuccessorIsSettled',
                     'io.github.anastaci1a.localica.PlayerBackgroundPolarityTest#oppositeCoversUseVisibleOutgoingIncomingWeightsAndOnePlayerQueueDecision',
                     'io.github.anastaci1a.localica.PlayerBackgroundPolarityTest#sameCoverVisitKeepsTheRealFadeAndItsPolarityAndRapidSuccessorCannotPublishOldInk',
                     'io.github.anastaci1a.localica.PlayerBackgroundPolarityTest#whiteCoverBlackLowerBorderUsesDirectAndContinuationFootprintsAtShortTallAndTranslatedInsets',
                     'io.github.anastaci1a.localica.PlayerBackgroundPolarityTest#zeroTrimControlHasIdenticalBackingForBothToneDirections'],
 'schema_version': 1,
 'selectors': ['io.github.anastaci1a.localica.GlassMenuContentTest',
               'io.github.anastaci1a.localica.GlassMenuViewportTouchTest',
               'io.github.anastaci1a.localica.PlayerBackgroundPolarityTest']}
CASES = {case: (SOURCE_PIN, api, SELECTED_METHODS, SELECTED_METHODS) for case, api in [('integrated26', '26'), ('integrated36', '36')]}
SOURCE_TREES = {'player': ('aad327cb3bacb06af8d33bfc11f0ef058c23790a', 'b683eea957d5ed9af361897e732d041cb198aac6')}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def data(path):
    require(path.is_file() and not path.is_symlink(), 'Missing or symlink evidence: '+str(path))
    return path.read_bytes()


def digest(path):
    return hashlib.sha256(data(path)).hexdigest()


def read_json(path):
    def unique(pairs):
        result = {}
        for k, v in pairs:
            require(k not in result, 'Duplicate JSON key: '+k)
            result[k] = v
        return result
    return json.loads(data(path), object_pairs_hook=unique)


def git(source, *args):
    return subprocess.check_output(['git', '-C', str(source), *args], text=True).strip()


def exact_files(root, pattern, count=1):
    roots = root if isinstance(root, list) else [root]
    files = []
    for base in roots:
        for path in sorted(base.glob(pattern)):
            require(path.resolve().is_relative_to(base.resolve()), 'Evidence escaped root')
            files.append(path)
    # AGP export and explicit device pull may preserve identical original bytes.
    # Distinct byte sequences are never silently selected/replaced.
    if isinstance(root, list):
        unique = {}
        for path in files:
            unique.setdefault((path.name, digest(path)), path)
        files = list(unique.values())
    require(len(files) == count, 'Expected '+str(count)+' originals for '+pattern+', found '+str(len(files)))
    for path in files:
        data(path)
    return files


def xml_methods(root, expected, expected_skips=()):
    expected_skips = set(expected_skips)
    require(expected_skips.issubset(expected), "Invalid skip contract")
    require(root.is_dir() and not any(p.is_symlink() for p in [root, *root.parents]), 'Missing/symlink XML root')
    require(not any(p.is_symlink() for p in root.rglob('*')), 'Symlink in XML tree')
    files = exact_files(root, '**/*.xml', count=len(list(root.glob('**/*.xml'))))
    require(files, 'No final instrumentation XML')
    methods = {}
    for path in files:
        document = ET.fromstring(data(path))
        require(document.tag in ('testsuite', 'testsuites'), 'Unsupported XML root')
        # Contradictory aggregate or suite-level terminal markers are failures,
        # even if every nested testcase happens to look green.
        require(not any(node.tag in ('failure', 'error') for node in document.iter()), 'Nonpassing XML marker')
        for node in document.iter():
            if node.tag in ('testsuites', 'testsuite', 'testcase'):
                if 'status' in node.attrib:
                    require(node.attrib['status'] in ('run', 'passed', 'pass'), 'Nonpassing XML status')
                if 'result' in node.attrib:
                    require(node.attrib['result'] in ('completed', 'passed', 'pass'), 'Nonpassing XML result')
        if document.tag == 'testsuites':
            require(all(node.tag in ('testsuite', 'properties', 'system-out', 'system-err') for node in document), 'Unsupported XML wrapper child')
            total = len(list(document.iter('testcase')))
            for attr, value in [('tests', total), ('failures', 0), ('errors', 0), ('skipped', len(list(document.iter('skipped'))))]:
                if attr in document.attrib:
                    require(int(document.attrib[attr]) == value, 'Contradictory XML wrapper '+attr)

        suites = [document] if document.tag == 'testsuite' else list(document.iter('testsuite'))
        require(suites, 'Missing XML suites')
        for suite in suites:
            require(not suite.findall('testsuite'), 'Nested XML suite unsupported')
            require(all(node.tag in ('testcase', 'properties', 'system-out', 'system-err') for node in suite), 'Unsupported XML suite child')
            cases = suite.findall('testcase')
            require(int(suite.attrib['tests']) == len(cases), 'Incomplete XML tests count')
            for attr, tag in [('failures', 'failure'), ('errors', 'error'), ('skipped', 'skipped')]:
                actual = sum(len(case.findall(tag)) for case in cases)
                require(int(suite.attrib.get(attr, '0')) == actual, 'Inconsistent XML '+attr)
                require(attr == 'skipped' or actual == 0, 'Nonpassing XML '+attr)
            require(not suite.findall('failure') and not suite.findall('error'), 'Suite-level error')
            for case in cases:
                identity = case.attrib['classname']+'#'+case.attrib['name']
                require(identity in expected and identity not in methods, 'Unexpected or duplicate test: '+identity)
                require(all(n.tag in ('skipped', 'system-out', 'system-err') for n in case), 'Unknown testcase child')
                skips = case.findall('skipped')
                require(len(skips) == (1 if identity in expected_skips else 0), 'Unexpected/missing SDK skip')
                methods[identity] = 'skipped-sdk-api<31' if skips else 'passed'
    require(set(methods) == set(expected), 'Missing expected test methods')
    return methods, {str(p.relative_to(root)): digest(p) for p in files}


def boundary_summary(path, run_id, attempt, sha, api):
    """Bound original log to one attempt; logs never establish test success."""
    result=dict(schema_version=1,status='partial',run_id=run_id,attempt=attempt,source_sha=sha,api=api,boundaries=[],errors=[],causal_claim=False,
        qualification='Run delimitation only. Strict native XML and monitoring receipts own outcomes; no log-derived test pass or atomic provider/pixel claim.')
    try:
        raw=data(path);result['raw_sha256']=hashlib.sha256(raw).hexdigest()
        lines=raw.decode('utf-8',errors='strict').splitlines()
    except (OSError,ValueError,UnicodeError) as error:
        result['errors'].append(str(error));return result
    pattern=re.compile(r'\bIntegratedPlayerValidation\s*:\s*(.*)$')
    boundaries=[dict(line=i+1,message=m.group(1)) for i,line in enumerate(lines) if (m:=pattern.search(line))]
    result['boundaries']=boundaries
    identity='run='+run_id+' attempt='+attempt+' source='+sha+' api='+api
    if [v['message'] for v in boundaries]!=['START '+identity,'END '+identity]:
        result['errors'].append('Missing, repeated, foreign, or unordered attempt boundary')
    if not result['errors']:result['status']='complete'
    return result


# Reviewed viewport integrity helpers, unchanged; no real-family diagnostic selected.
"""Structural integrity of descriptive real-caller originals; never optical acceptance."""
import csv
import hashlib
import io
import json
import math
from pathlib import Path
import re
import struct
import zlib

FAMILY_LABELS = ['library-utility', 'playlist-plus', 'sort-view']
FAMILY_HEADER = 'caller,edge,region,row_y,distance_dp,x_start,x_end_exclusive,pixels,mean_r,mean_g,mean_b,mean_a'.split(',')

def family_require(value, message):
    if not value:
        raise ValueError(message)

def family_bytes(path):
    family_require(path.is_file() and not any(p.is_symlink() for p in [path, *path.parents]), 'Missing/symlink family evidence: '+str(path))
    return path.read_bytes()

def family_hash(path):
    return hashlib.sha256(family_bytes(path)).hexdigest()

def family_json(path):
    def unique(pairs):
        out = {}
        for k,v in pairs:
            family_require(k not in out, 'Duplicate family JSON key')
            out[k] = v
        return out
    def invalid(value):
        raise ValueError('Nonfinite JSON constant: '+value)
    return json.loads(family_bytes(path), object_pairs_hook=unique, parse_constant=invalid)

def family_number(value):
    return type(value) in (float,int) and math.isfinite(value)

def family_fields(value, fields):
    family_require(isinstance(value, dict) and set(fields.split()).issubset(value), 'Incomplete family evidence fields: '+fields)

def family_rect(value):
    family_require(isinstance(value,list) and len(value)==4 and all(family_number(x) for x in value) and value[2]>value[0] and value[3]>value[1], 'Invalid surface rectangle')

def family_png(path):
    raw = family_bytes(path)
    family_require(raw.startswith(b'\x89PNG\r\n\x1a\n'), 'Not an original PNG')
    at=8; chunks=[]; compressed=bytearray(); dimensions=None
    while at<len(raw):
        family_require(at+12<=len(raw), 'Truncated PNG chunk')
        size=struct.unpack('>I',raw[at:at+4])[0]; kind=raw[at+4:at+8]
        end=at+12+size
        family_require(end<=len(raw), 'Truncated PNG content')
        payload=raw[at+8:at+8+size]
        family_require(zlib.crc32(kind+payload)&0xffffffff==struct.unpack('>I',raw[end-4:end])[0], 'Corrupt PNG CRC')
        if kind==b'IHDR':
            family_require(not chunks and size==13, 'Bad PNG header')
            w,h,depth,color,compression,filtering,interlace=struct.unpack('>IIBBBBB',payload)
            family_require(0<w<=16384 and 0<h<=16384 and depth==8 and color in (2,6) and compression==filtering==interlace==0, 'Unsupported original PNG encoding')
            dimensions=(w,h,color)
        elif kind==b'IDAT': compressed.extend(payload)
        elif kind==b'IEND':
            family_require(size==0 and end==len(raw), 'Trailing PNG bytes')
        chunks.append(kind);at=end
    family_require(dimensions and chunks[-1:]==[b'IEND'] and b'IDAT' in chunks and chunks.count(b'IHDR')==1 and chunks.count(b'IEND')==1, 'Incomplete PNG')
    w,h,color=dimensions; stride=w*(4 if color==6 else 3)+1
    inflater=zlib.decompressobj()
    try:
        decoded=inflater.decompress(bytes(compressed), stride*h+1)
    except zlib.error as error:
        raise ValueError('Invalid original PNG compression') from error
    family_require(len(decoded)==stride*h and inflater.eof and not inflater.unused_data and not inflater.unconsumed_tail, 'Malformed PNG image stream')
    family_require(all(decoded[y*stride]<=4 for y in range(h)), 'Invalid PNG row filter')
    return w,h,color

VIEWPORT_SCOPE = 'Shared long GlassMenu/ActionMenuItem fixture; touch-injected swipes, native coordinate taps, system Back; reduced motion'
VIEWPORT_LIMITS = 'Test-only 4dp red fade/stage rail; not an unmodified Library route or physical-finger/device acceptance. Single native copy per phase; cached backing shader execution is not established.'
VIEWPORT_PHASES = ['start', 'middle', 'end']


def viewport_bundle(directory, api):
    manifest = family_json(directory/'manifest.json')
    family_fields(manifest, 'status failure api records scope limits')
    family_require(manifest['status'] == 'passed' and manifest['failure'] is None,
                   'Viewport execution/cleanup did not pass')
    family_require(type(manifest['api']) is int and manifest['api'] == int(api), 'Wrong viewport API')
    family_require(manifest['scope'] == VIEWPORT_SCOPE and manifest['limits'] == VIEWPORT_LIMITS,
                   'Viewport scope/qualification drift')
    records = manifest['records']
    family_require(isinstance(records, list) and len(records) == 3,
                   'Viewport requires three original phases')
    expected = {'manifest.json', *(p+'-original-window.png' for p in VIEWPORT_PHASES)}
    family_require({p.name for p in directory.iterdir()} == expected, 'Missing/extra viewport original files')
    dimensions = None
    bounds = None
    maximum = None
    for index, (record, phase) in enumerate(zip(records, VIEWPORT_PHASES)):
        family_fields(record, 'phase image sha256 scrollValue scrollMax boundsInRoot viewportBoundsInRoot stage topFaded bottomFaded')
        family_require(record['phase'] == phase and record['image'] == phase+'-original-window.png',
                       'Wrong viewport phase/image inventory')
        family_require(type(record['stage']) is int and record['stage'] == index+1, 'Wrong viewport capture stage')
        family_require(record['topFaded'] is (phase != 'start') and record['bottomFaded'] is (phase != 'end'),
                       'Wrong viewport fade state')
        value, limit = record['scrollValue'], record['scrollMax']
        family_require(family_number(value) and family_number(limit) and limit > 0 and 0 <= value <= limit,
                       'Invalid viewport scroll observation')
        if maximum is None:
            maximum = limit
        family_require(limit == maximum, 'Viewport scroll maximum changed')
        family_require(value == 0 if phase == 'start' else value == limit if phase == 'end' else 0 < value < limit,
                       'Viewport phase is not at its required scroll position')
        family_rect(record['boundsInRoot'])
        family_rect(record['viewportBoundsInRoot'])
        family_require(record['boundsInRoot'] == record['viewportBoundsInRoot'], 'Viewport clip differs from shell')
        if bounds is None:
            bounds = record['boundsInRoot']
        family_require(record['boundsInRoot'] == bounds, 'Viewport shell changed between phases')
        path = directory/record['image']
        family_require(record['sha256'] == family_hash(path), 'Viewport PNG hash mismatch')
        current = family_png(path)
        if dimensions is None:
            dimensions = current
        family_require(current == dimensions, 'Viewport original image dimensions/encoding changed')
        # The source records neither decor extents nor root-to-window origin: do
        # not pretend boundsInRoot proves this PNG covers the full native window.
    return {name: family_hash(directory/name) for name in sorted(expected)}


def viewport_original_evidence(source, evidence, api):
    source, evidence = Path(source), Path(evidence)
    family_require(api in ('26', '36'), 'Unsupported viewport API')
    roots = [source/'app/build/outputs/connected_android_test_additional_output', evidence/'device-output']
    bundles = []
    for root in roots:
        if root.exists():
            family_require(not any(p.is_symlink() for p in [root, *root.parents]), 'Symlink viewport evidence root')
            # Path.glob does not recurse into directory symlinks. Reject them
            # explicitly rather than silently ignore a second conflicting bundle.
            family_require(not any(p.is_symlink() for p in root.rglob('*')), 'Symlink in viewport evidence tree')
            for parent in sorted(root.glob('**/menu-viewport-touch')):
                family_require(parent.is_dir() and not parent.is_symlink(), 'Invalid viewport output root')
                entries = list(parent.iterdir())
                family_require(entries, 'Empty viewport output root')
                for directory in sorted(entries):
                    family_require(directory.is_dir() and not directory.is_symlink() and
                                   re.fullmatch('api'+api+r'-[0-9]+', directory.name), 'Foreign viewport API/run bundle')
                    family_require(directory.resolve().is_relative_to(root.resolve()), 'Escaped viewport originals')
                    bundles.append((directory, viewport_bundle(directory, api)))
    family_require(bundles, 'Missing viewport original bundle')
    identity = (bundles[0][0].name, bundles[0][1])
    family_require(all((p.name, hashes) == identity for p, hashes in bundles), 'Conflicting/mixed viewport bundles')
    originals = {}
    for directory, hashes in bundles:
        for name, checksum in hashes.items():
            path = directory/name
            prefix, root = ('source/', source) if path.is_relative_to(source) else ('evidence/', evidence)
            originals[prefix+str(path.relative_to(root))] = checksum
    return originals

def reconcile(args):
    source, evidence = Path(args.source).resolve(), Path(args.evidence).resolve()
    tooling = Path(args.tooling).resolve()
    require(args.case in CASES, 'Unknown diagnostic case')
    sha, api, selectors, expected = CASES[args.case]
    require(re.fullmatch('[0-9a-f]{40}', sha), 'Unbound corrected source pin')
    require(args.runner_exit == 0, 'Native runner did not complete successfully')
    require(re.fullmatch('[0-9a-f]{40}', args.workflow_sha) and re.fullmatch('[0-9]+', args.run_id) and re.fullmatch('[1-9][0-9]*', args.attempt), 'Invalid run identity')
    require(git(source, 'rev-parse', 'HEAD') == sha, 'Source checkout differs from immutable pin')
    parent, tree = SOURCE_TREES['player']
    require(re.fullmatch('[0-9a-f]{40}', tree), 'Unbound diagnostic source tree')
    require(git(source, 'show', '-s', '--format=%P', 'HEAD') == parent and git(source, 'rev-parse', 'HEAD^{tree}') == tree, 'Corrected source parent/tree mismatch')
    require(git(source, 'diff-tree', '--no-commit-id', '--name-status', '-r', parent, sha) == 'M\tapp/src/androidTest/java/io/github/anastaci1a/localica/GlassMenuViewportTouchTest.kt', 'Candidate delta differs')
    require(not git(source, 'diff', 'HEAD', '--name-only'), 'Tracked source changed')
    context = read_json(evidence/'context.json')
    harness = source.parent/'harness'
    require(git(harness, 'rev-parse', 'HEAD') == args.workflow_sha and not git(harness, 'diff', 'HEAD', '--name-only'), 'Harness checkout drift')
    harness_paths = ['.github/workflows/hosted-integrated-player.yml', '.github/run-integrated-player.sh', '.github/reconcile-integrated-player.py', '.github/integrated_player_environment.py', '.github/integrated_player_java.py']
    require(set(context['harness_sha256']) == set(harness_paths), 'Incomplete harness hash inventory')
    for path in harness_paths:
        require(digest(harness/path) == context['harness_sha256'][path], 'Harness receipt hash mismatch')
        require(git(harness, 'hash-object', str(harness/path)) == git(harness, 'rev-parse', args.workflow_sha+':'+path), 'Harness differs from workflow tree')

    for key, value in dict(case=args.case, source_sha=sha, source_parent=parent, source_tree=tree, source_changes=['app/src/androidTest/java/io/github/anastaci1a/localica/GlassMenuViewportTouchTest.kt'], workflow_sha=args.workflow_sha, api=api, run_id=args.run_id, attempt=args.attempt, selectors=selectors).items():
        require(context[key] == value, 'Wrong context '+key)
    argv = ['./gradlew', 'connectedDebugAndroidTest', '--no-daemon', '--max-workers=2', '-Pandroid.testInstrumentationRunnerArguments.class='+','.join(selectors), '-Pandroid.testInstrumentationRunnerArguments.additionalTestOutputDir=/sdcard/Android/media/io.github.anastaci1a.localica.debug/additional_test_output', '-Pandroid.enableAdditionalTestOutput=true', '-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true']
    require(context['argv'] == argv and context['expected_methods'] == expected, 'Wrong diagnostic command/methods')
    require(all(context[key] is False for key in ('release_certified', 'full_suite_run', 'compatibility_run', 'optical_acceptance', 'causal_claim')) and context['tooling_sha'] == PIN, 'Wrong tooling/scope identity')
    require(git(tooling, 'rev-parse', 'HEAD') == PIN and not git(tooling, 'diff', 'HEAD', '--name-only'), 'Tooling checkout drift')
    helpers = ['.github/test-evidence/observe.py', '.github/test-evidence/supervise.py', '.github/test-evidence/publish.mjs', '.github/test-evidence/source_methods.py', '.github/test-evidence/package.json', '.github/test-evidence/package-lock.json', '.github/prepare-device-tests.sh', '.github/test-evidence/environment.sh']
    require(set(context['helper_sha256']) == set(helpers), 'Incomplete tooling hash inventory')
    for helper in helpers:
        require(digest(tooling/helper) == context['helper_sha256'][helper], 'Tooling receipt hash mismatch')
        require(git(tooling, 'hash-object', str(tooling/helper)) == git(tooling, 'rev-parse', PIN+':'+helper), 'Tool differs from pinned tree')
    supervisor_argv = ['python3', str(tooling/'.github/test-evidence/supervise.py'), '--output', str(evidence/'live'), '--run-id', args.run_id, '--attempt', args.attempt, '--sha', sha, '--api', api, '--lane', 'integrated-player-'+args.case, '--', *argv]
    require(context['supervisor_argv'] == supervisor_argv, 'Wrong supervisor invocation provenance')
    status = read_json(evidence/'status.json')
    require(status['native_exit'] == 0 and status['cleanup_exit'] == 0 and status['cancel_signal'] is None, 'Native execution/cleanup not successful')
    require(data(evidence/'runner-exit.txt').strip() == b'0' and status['runner_exit'] == 0 and status['phase'] == 'native-terminal', 'Runner exit receipt missing/nonzero')
    captures = {}
    for line in data(evidence/'capture-status.txt').decode().splitlines():
        name, value = line.split('=', 1)
        require(name not in captures and value.isdigit(), 'Ambiguous capture status')
        captures[name] = int(value)
    required_captures = {'logcat', 'crash-buffer', 'windows', 'activities', 'device-output-pull', 'boundary-start', 'boundary-end'}
    require(set(captures) == required_captures, 'Incomplete capture statuses')
    require(all(captures[k] == 0 for k in required_captures - {'device-output-pull'}), 'Native diagnostic capture degraded')
    certificates = data(evidence/'test-input-certificates.txt').decode()
    fingerprints = re.findall(r'^Signer #1 certificate SHA-256 digest: ([0-9a-f]{64})$', certificates, re.M)
    require(len(fingerprints) == 2 and fingerprints[0] == fingerprints[1], 'App/test signing identity mismatch')
    device = read_json(evidence/'device.json')
    require(device['api'] == api and device['serial'] == 'emulator-5554', 'Wrong native device/API')
    size,density,scale = ([1080,1920],420,0.0) if api=='26' else ([720,1280],280,1.0)
    require(device['effective_size'] == size and all(type(v) is int for v in device['effective_size']), 'Wrong device size')
    require(type(device['effective_density']) is int and device['effective_density'] == density, 'Wrong device density')
    scales = device['animation_scales']
    require(isinstance(scales,dict) and set(scales) == {'animator_duration_scale','transition_animation_scale','window_animation_scale'}, 'Incomplete animation settings')
    require(all(type(v) in (int,float) and math.isfinite(v) and v == scale for v in scales.values()), 'Wrong animation settings')
    from integrated_player_environment import validate_receipt
    validate_receipt(read_json(evidence/'environment-identity.json'),api)
    from integrated_player_java import validate_receipt as validate_java
    java_build=validate_java(evidence,'build');java_native=validate_java(evidence,'native')
    require(all(java_build[k]==java_native[k] for k in ('executable','executable_sha256','actual_version','java_home','resolved_java_home_launcher')), 'Build/native Java runtime identity changed')
    inventory = read_json(evidence/'expected-methods.json')
    classes = sorted({value.split('#')[0] for value in selectors})
    require(inventory['selectors'] == classes and set(expected).issubset(inventory['runtime_methods']), 'Wrong source method inventory')
    require(len(inventory['classes']) == len(classes), 'Wrong source class count')
    for entry in inventory['classes']:
        path = source/entry['source']
        require(path.resolve().is_relative_to(source) and entry['class'] in classes, 'Unexpected source class')
        require(digest(path) == entry['sha256'], 'Source inventory hash mismatch')
        require(git(source, 'hash-object', str(path)) == git(source, 'rev-parse', sha+':'+entry['source']), 'Source file differs from pinned tree')
    require(inventory == DECLARED_INVENTORY, 'Pinned declared source inventory differs')
    apk_paths = ['app/build/outputs/apk/debug/app-debug.apk', 'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk']
    require(data(evidence/'build-input-apks.sha256') == data(evidence/'test-input-apks.sha256'), 'Build/native APK inputs differ')
    require(data(evidence/'APK-STATUS.txt').decode().startswith('UNVALIDATED DIAGNOSTIC DEBUG INPUTS.'), 'Missing unvalidated APK qualification')
    lines = data(evidence/'test-input-apks.sha256').decode().splitlines()
    require(len(lines) == 2, 'Incomplete APK hash receipt')
    for line, path in zip(lines, apk_paths):
        require(line == digest(source/path)+'  '+path, 'APK checksum receipt mismatch')
    live = evidence/'live'
    observed = read_json(live/'observed/manifest.json')
    supervised = read_json(live/'supervision.json')
    published = read_json(live/'publishing/complete.json')
    for receipt in (observed, supervised, published):
        require(receipt['sha'] == sha and receipt['api'] == api, 'Monitoring source/API mismatch')
    require(observed['lane'] == published['lane'] == 'integrated-player-'+args.case, 'Wrong monitored lane')
    require(observed['argv'] == argv and observed['command_id'] == published['command_id'], 'Monitoring command mismatch')
    require(re.fullmatch(r'[a-f0-9]{8}-(?:[a-f0-9]{4}-){3}[a-f0-9]{12}', observed['command_id']), 'Invalid command identity')
    require(observed['status'] == 'completed' and observed['ended_utc'] is not None, 'Observer incomplete')
    for key in ('child_returncode', 'observer_exit_status', 'observed_failure_lines', 'oversize_lines_not_parsed'):
        require(observed[key] == 0, 'Degraded/nonpassing observation '+key)
    require(observed['cancel_signal'] is None and observed['observer_error'] is None, 'Observer canceled/degraded')
    require(supervised['observer_exit'] == supervised['publisher_exit'] == 0 and supervised['monitoring_complete'] is True and supervised['supervisor_error'] is None, 'Supervisor degraded')
    require(published['status'] == 'monitoring_complete' and published['observer_status'] == 'completed', 'Publisher incomplete')
    require(published['observed_failure_lines'] == published['observed_failures'] == published['uploaded_packets'] == published['read_offset'] == 0, 'Contradictory publisher evidence')
    require(data(live/'observed/events.jsonl') == b'', 'Provisional failure contradicts pass')
    require(not list((live/'publishing').glob('packet-*.json')) and not list((live/'publishing').glob('receipt-*.json')) and not (live/'publishing/monitoring-degraded.json').exists(), 'Contradictory publication state')
    require(data(live/'observed/command.log').strip(), 'Empty native command output')
    expected_skips = {TEST_CLASS+'#nativeBlurRetainsVerifiedInkAsDistinctUnavailableThenRecovers'} if api == '26' else set()
    methods, xml = xml_methods(source/'app/build/outputs/androidTest-results', expected, expected_skips)
    trace = boundary_summary(evidence/'logcat.txt', args.run_id, args.attempt, sha, api)
    require(trace['status'] == 'complete', 'Incomplete attempt boundary: '+str(trace['errors']))
    originals = viewport_original_evidence(source, evidence, api)
    for name in ('logcat', 'crash-buffer', 'windows', 'activities'):
        originals['evidence/'+name+'.txt'] = digest(evidence/(name+'.txt'))
    originals['evidence/environment-identity.json'] = digest(evidence/'environment-identity.json')
    originals['evidence/device.txt'] = digest(evidence/'device.txt')
    for phase in ('build','native'):
        for suffix in ('json','stdout.txt','stderr.txt'):
            name='java-'+phase+'.'+suffix;originals['evidence/'+name]=digest(evidence/name)
    require(data(evidence/'logcat.txt').strip(), 'Empty native logcat')
    return {'schema_version': 1, 'case': args.case, 'source_sha': sha, 'source_parent': parent, 'source_tree': tree, 'source_changes': ['app/src/androidTest/java/io/github/anastaci1a/localica/GlassMenuViewportTouchTest.kt'], 'workflow_sha': args.workflow_sha, 'api': api, 'run_id': args.run_id, 'attempt': args.attempt, 'methods': methods, 'xml_sha256': xml, 'original_sha256': originals, 'source_inventory_sha256': digest(evidence/'expected-methods.json'), 'passed_count': sum(v == 'passed' for v in methods.values()), 'sdk_skipped_count': sum(v != 'passed' for v in methods.values()), 'full_suite_run': False, 'compatibility_run': False, 'screenshots_persisted_by_test': True, 'screenshot_scope': 'GlassMenuViewportTouchTest start/middle/end originals only; player and content test pixels are not persisted', 'transition_coverage': False, 'atomic_presentation_proven': False, 'monitoring_complete': True, 'release_certified': False, 'optical_acceptance': False, 'causal_claim': False}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--case', choices=CASES, required=True)
    for name in ('source', 'tooling', 'evidence', 'workflow-sha', 'run-id', 'attempt'):
        parser.add_argument('--'+name, required=True)
    parser.add_argument('--runner-exit', type=int, required=True)
    args = parser.parse_args()
    result = {'case': args.case, 'release_certified': False, 'optical_acceptance': False, 'causal_claim': False, 'status': 'rejected'}
    code = 3
    try:
        sha,api = CASES[args.case][:2]
        trace = boundary_summary(Path(args.evidence)/'logcat.txt', args.run_id, args.attempt, sha, api)
        with (Path(args.evidence)/'boundary-summary.json').open('x') as output:
            json.dump(trace, output, indent=2)
            output.write('\n')
        result.update(reconcile(args), status='diagnostic-complete')
        code = 0
    except (OSError, ValueError, KeyError, TypeError, ET.ParseError, subprocess.SubprocessError) as error:
        result['error'] = type(error).__name__+': '+str(error)
        print('Diagnostic reconciliation rejected: '+result['error'], file=sys.stderr)
    try:
        with (Path(args.evidence)/'reconciled-tests.json').open('x') as output:
            json.dump(result, output, indent=2)
            output.write('\n')
    except OSError as error:
        print('Cannot preserve fresh reconciliation receipt: '+str(error), file=sys.stderr)
        return 3
    return code


if __name__ == '__main__':
    sys.exit(main())
