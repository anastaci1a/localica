#!/usr/bin/env python3
"""Fail-closed evidence reconciliation for two diagnostic-only API cases at one immutable source.
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
FAMILY = PREFIX + 'PopupFamilyEdgeComparisonTest'
PIN = '1e31403cb042e9896686200a4800faffce9b2a59'
FAMILY_PIN = '4e1c89d61efb7e2c3b3ef9040ed9894316361291'
FAMILY_METHOD = 'realLibraryMenuAndDialogEdgesRetainOriginalsAndProvenance'
CASES = {case: (FAMILY_PIN, api, [FAMILY+'#'+FAMILY_METHOD], [FAMILY+'#'+FAMILY_METHOD]) for case, api in [('family26', '26'), ('family36', '36')]}
SOURCE_TREES = {'family': ('25fa9a7b462e9d5fddc535ae69306d2e56be508d', 'a52b4affc09ef8886b31c63b41b4ba16ee1b0576')}


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


def xml_methods(root, expected):
    files = exact_files(root, '**/*.xml', count=len(list(root.glob('**/*.xml'))))
    require(files, 'No final instrumentation XML')
    methods = {}
    for path in files:
        document = ET.fromstring(data(path))
        require(document.tag in ('testsuite', 'testsuites'), 'Unsupported XML root')
        suites = [document] if document.tag == 'testsuite' else list(document.iter('testsuite'))
        require(suites, 'Missing XML suites')
        for suite in suites:
            require(not suite.findall('testsuite'), 'Nested XML suite unsupported')
            cases = suite.findall('testcase')
            require(int(suite.attrib['tests']) == len(cases), 'Incomplete XML tests count')
            for attr, tag in [('failures', 'failure'), ('errors', 'error'), ('skipped', 'skipped')]:
                actual = sum(len(case.findall(tag)) for case in cases)
                require(int(suite.attrib.get(attr, '0')) == actual, 'Inconsistent XML '+attr)
                require(actual == 0, 'Nonpassing XML '+attr)
            require(not suite.findall('failure') and not suite.findall('error'), 'Suite-level error')
            for case in cases:
                identity = case.attrib['classname']+'#'+case.attrib['name']
                require(identity in expected and identity not in methods, 'Unexpected or duplicate test: '+identity)
                require(not any(case.find(t) is not None for t in ('failure', 'error', 'skipped')), 'Nonpassing test')
                methods[identity] = 'passed'
    require(set(methods) == set(expected), 'Missing expected test methods')
    return methods, {str(p.relative_to(root)): digest(p) for p in files}


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

def family_facts(facts, record, label):
    family_fields(facts, 'observationUptime nonAtomicWithCopy viewIdentity windowIdentity tokenIdentity attached focused layoutRequested viewSize decorSize viewOriginInWindow viewOriginOnScreen density fontScale surfaceBoundsInRoot surfaceBoundsInWindow surfaceBoundsOnScreen nativeDraw hostEntries retainedDialog placement expansion bodySettled shadowOwnerInModal triggerHidden anchorBounds anchorSource ordinaryHostBackdropCandidate actualNativeSourceConsumption homeFootprintAndWeight shadowRasterConsumption surfaceDrawObservations')
    family_fields(facts['nativeDraw'], 'count uptime focused boundsInRoot')
    native=facts['nativeDraw']
    family_require(type(native['count']) is int and native['count']>=1 and type(native['uptime']) is int and native['uptime']>=0 and native['focused'] is True, 'Missing native draw witness')
    family_rect(native['boundsInRoot'])
    family_require(type(facts['observationUptime']) is int and facts['observationUptime']>=0 and type(facts['hostEntries']) is int and facts['hostEntries']>=0, 'Malformed owner counters')
    family_require(facts['nonAtomicWithCopy'] is True and facts['attached'] is True and facts['focused'] is True, 'Invalid window observation')
    family_require(facts['retainedDialog'] is (label=='sort-view'), 'Wrong caller type')
    family_require(family_number(facts['density']) and facts['density']>0, 'Invalid density')
    for key in ('surfaceBoundsInRoot','surfaceBoundsInWindow','surfaceBoundsOnScreen'): family_rect(facts[key])
    family_require(facts['decorSize']==[record['imageWidth'],record['imageHeight']], 'PNG is not complete original window')
    l,t,r,b=facts['surfaceBoundsInWindow']
    family_require(0<=l<r<=record['imageWidth'] and 0<=t<b<=record['imageHeight'], 'Surface outside original window')
    for key in ('actualNativeSourceConsumption','homeFootprintAndWeight','shadowRasterConsumption'):
        family_require(isinstance(facts[key],str) and facts[key].startswith('UNKNOWN:'), 'Unqualified consumption claim')
    for key in ('anchorSource','ordinaryHostBackdropCandidate'):
        value=facts[key]
        if value!='UNKNOWN':
            family_fields(value, 'identity parentIdentity layerIdentity parentLayerIdentity layerExtent layerReleased recorded materialOnly dark size origin revision requestedReadback publishedReadback snapshotIdentity frostIdentity lensIdentity drawReceipt unrefractedSurfaceCornersInCandidateSource qualification')
    d=facts['surfaceDrawObservations']
    family_fields(d, 'collectionStartedUptime limit droppedOldest previousFactsSequence lastSequence status rootMatchedCount exactOuterBoundsMatchCount observations nonAtomicWithCopy qualification unknown')
    family_require(d['limit']==256 and type(d['droppedOldest']) is int and d['droppedOldest']>=0 and d['nonAtomicWithCopy'] is True, 'Invalid bounded draw inventory')
    family_require(all(type(d[k]) is int and d[k]>=0 for k in ('collectionStartedUptime','limit','droppedOldest','previousFactsSequence','lastSequence','rootMatchedCount','exactOuterBoundsMatchCount')), 'Malformed draw counters')
    entries=d['observations']; family_require(isinstance(entries,list) and len(entries)<=256 and d['rootMatchedCount']==len(entries), 'Draw inventory count mismatch')
    expected_status='MISSING:' if not entries else 'CACHED:' if not any(e['sequence']>d['previousFactsSequence'] for e in entries) else 'NEW_TRAVERSALS:'
    family_require(isinstance(d['status'],str) and d['status'].startswith(expected_status), 'Misclassified missing/cached draws')
    sequences=[]
    for e in entries:
        family_fields(e, 'sequence newSincePreviousFacts uptime surfaceIdentity viewIdentity windowIdentity tokenIdentity attached focused viewMatrixIdentity visible drawExtent rootCorners windowCorners screenCorners computedSilhouetteBounds exactOuterLayoutBoundsMatch nativeDrawAssociation source home inkArgb inkOverridden lightInk coatingDark toneTrim toneWeight tintArgb panel frost suppliedHomeWeight blur whiteTint depth depthScale refraction surfaceShadowEnabled configuredShadowStrength configuredShadowSpread shadowGroupIdentity shadowInModal silhouettePresent')
        family_require(e['viewIdentity']==facts['viewIdentity'] and e['windowIdentity']==facts['windowIdentity'], 'Foreign draw observation')
        family_require(type(e['sequence']) is int and e['sequence']<=d['lastSequence'] and e['newSincePreviousFacts'] is (e['sequence']>d['previousFactsSequence']), 'Invalid draw ordering')
        for source_key in ('source','home'):
            value=e[source_key]
            if value!='NONE: no supplied source':
                family_fields(value, 'identity parentIdentity layerIdentity parentLayerIdentity recorded released parentRecorded parentReleased materialOnly dark extent layerExtent revision requestedReadback publishedReadback publishedDrawRequest publishedComposite snapshotIdentity frostIdentity lensIdentity sourceToConsumerAffine unrefractedConsumerCornersInSource')
        sequences.append(e['sequence'])
    family_require(sequences==sorted(set(sequences)), 'Repeated/out-of-order draw entries')
    family_require(d['exactOuterBoundsMatchCount']==sum(e['exactOuterLayoutBoundsMatch'] is True for e in entries), 'Outer match count differs')

def family_f32(x):
    return struct.unpack('f',struct.pack('f',x))[0]

def family_profile_keys(records):
    keys={}
    f=family_f32
    for rec in records:
        l,t,r,b=map(f,rec['before']['surfaceBoundsInWindow']); density=f(rec['before']['density'])
        width=f(r-l); depth=min(f(24*density),f(f(b-t)/2))
        for edge in ('top','bottom'):
            y0=math.ceil(t if edge=='top' else f(b-depth)); y1=math.floor(f(t+depth) if edge=='top' else b)
            for region,start,end in [('left',0,.15),('center',.40,.60),('right',.85,1)]:
                x0=math.ceil(f(l+f(width*f(start)))); x1=math.floor(f(l+f(width*f(end))))
                family_require(x1>x0 and y1>y0, 'Empty profile region')
                for y in range(y0,y1):
                    distance=f(f(f(y+.5)-t)/density) if edge=='top' else f(f(f(b-y)-.5)/density)
                    keys[(rec['label'],edge,region,y)]=(x0,x1,x1-x0,distance)
    return keys

def family_bundle(directory,api):
    manifest=family_json(directory/'manifest.json')
    family_fields(manifest,'status failure api declaredTotalCaptures requestedCopies fixedOrder records fixture configuredAppearance readiness limitations')
    family_require(manifest['status']=='diagnostic-recorded-NOT-band-acceptance' and manifest['failure'] is None, 'Noncomplete family diagnostic')
    family_require(type(manifest['api']) is int and manifest['api']==int(api) and manifest['declaredTotalCaptures']==manifest['requestedCopies']==3 and manifest['fixedOrder']==FAMILY_LABELS, 'Incorrect capture/API inventory')
    records=manifest['records']; family_require(isinstance(records,list) and len(records)==3 and [r['label'] for r in records]==FAMILY_LABELS, 'Wrong caller order')
    expected={'manifest.json','edge-profiles.csv',*(label+'-original-window.png' for label in FAMILY_LABELS)}
    family_require({p.name for p in directory.iterdir()}==expected, 'Missing/extra original bundle files')
    for rec in records:
        family_fields(rec,'label route status activation routeMarkerSemantics routeMarkerBoundsInRoot routeMarkerViewIdentity before beforeSemanticGeometry image bytes sha256 imageWidth imageHeight bitmapConfig hasAlpha premultiplied after afterSemanticGeometry prePostBoundsIdentical prePostHostCandidateSerializedIdentical prePostQualification')
        family_require(rec['status']=='original-retained' and rec['image']==rec['label']+'-original-window.png', 'Missing original caller capture')
        path=directory/rec['image']; raw=family_bytes(path)
        family_require(type(rec['bytes']) is int and rec['bytes']==len(raw) and rec['sha256']==family_hash(path), 'PNG hash/size mismatch')
        w,h,color=family_png(path)
        family_require(type(rec['imageWidth']) is int and type(rec['imageHeight']) is int and [w,h]==[rec['imageWidth'],rec['imageHeight']] and rec['bitmapConfig']=='ARGB_8888' and type(rec['hasAlpha']) is bool and type(rec['premultiplied']) is bool, 'PNG dimensions/alpha metadata mismatch')
        family_require(not rec['hasAlpha'] or color==6, 'Missing original PNG alpha channel')
        for phase in ('before','after'):
            family_facts(rec[phase],rec,rec['label'])
            geometry=rec[phase+'SemanticGeometry']
            names={'library-utility':['Scan music','Settings'],'playlist-plus':['Create playlist','Import playlist from file'],'sort-view':['Sort','View','Sort by']}[rec['label']]
            family_require(isinstance(geometry,list) and [g['name'] for g in geometry]==names, 'Missing semantic geometry')
            for g in geometry:
                family_fields(g,'name count boundsInRoot qualification')
                family_require(type(g['count']) is int and g['count']==len(g['boundsInRoot']), 'Semantic count mismatch')
        family_require(rec['routeMarkerViewIdentity']==rec['before']['viewIdentity'], 'Route marker belongs to another native root')
        family_require(rec['prePostBoundsIdentical'] is (rec['before']['surfaceBoundsInWindow']==rec['after']['surfaceBoundsInWindow']), 'Incorrect pre/post geometry equality')
        family_require(type(rec['prePostBoundsIdentical']) is bool and type(rec['prePostHostCandidateSerializedIdentical']) is bool, 'Missing pre/post qualification')
    expected_rows=family_profile_keys(records)
    reader=csv.DictReader(io.StringIO(family_bytes(directory/'edge-profiles.csv').decode('utf-8')))
    family_require(reader.fieldnames==FAMILY_HEADER,'Wrong profile header')
    seen=set()
    for row in reader:
        family_require(None not in row and None not in row.values(), 'Malformed CSV record')
        key=(row['caller'],row['edge'],row['region'],int(row['row_y']))
        family_require(key in expected_rows and key not in seen,'Missing/duplicate/foreign profile rows')
        seen.add(key); x0,x1,n,d=expected_rows[key]
        family_require((int(row['x_start']),int(row['x_end_exclusive']),int(row['pixels']))==(x0,x1,n),'Wrong profile geometry')
        distance=float(row['distance_dp'])
        family_require(math.isfinite(distance) and math.isclose(distance,d,rel_tol=1e-6,abs_tol=1e-5),'Wrong profile distance')
        for channel in ('r','g','b','a'):
            value=float(row['mean_'+channel]); family_require(math.isfinite(value) and 0<=value<=255,'Invalid profile channel value')
    family_require(seen==set(expected_rows),'Truncated profile coverage')
    return {name:family_hash(directory/name) for name in sorted(expected)}

def original_evidence(case, source, evidence, api):
    family_require(case=='family'+api and api in ('26','36'),'Wrong family case/API')
    roots=[source/'app/build/outputs/connected_android_test_additional_output',evidence/'device-output']
    bundles=[]
    for root in roots:
        if root.exists():
            for directory in sorted(root.glob('**/popup-family-edge-comparison/*')):
                family_require(directory.is_dir(), 'Unexpected family output file')
                manifest=directory/'manifest.json'
                family_require(re.fullmatch('api'+api+r'-[0-9]+',manifest.parent.name),'Foreign API/run bundle')
                family_require(manifest.resolve().is_relative_to(root.resolve()),'Escaped original evidence')
                bundles.append((manifest.parent,family_bundle(manifest.parent,api)))
    family_require(bundles,'Missing family original bundle')
    identity=(bundles[0][0].name,bundles[0][1])
    family_require(all((p.name,hashes)==identity for p,hashes in bundles),'Conflicting or mixed family bundles')
    result={}
    for directory,hashes in bundles:
        for name,digest in hashes.items():
            path=directory/name
            prefix='source/' if path.is_relative_to(source) else 'evidence/'
            root=source if prefix=='source/' else evidence
            result[prefix+str(path.relative_to(root))]=digest
    for name in ('logcat.txt','crash-buffer.txt','windows.txt','activities.txt'):
        result['evidence/'+name]=family_hash(evidence/name)
    return result



def reconcile(args):
    source, evidence = Path(args.source).resolve(), Path(args.evidence).resolve()
    tooling = Path(args.tooling).resolve()
    require(args.case in CASES, 'Unknown diagnostic case')
    sha, api, selectors, expected = CASES[args.case]
    require(re.fullmatch('[0-9a-f]{40}', sha), 'Unbound corrected source pin')
    require(args.runner_exit == 0, 'Native runner did not complete successfully')
    require(re.fullmatch('[0-9a-f]{40}', args.workflow_sha) and re.fullmatch('[0-9]+', args.run_id) and re.fullmatch('[1-9][0-9]*', args.attempt), 'Invalid run identity')
    require(git(source, 'rev-parse', 'HEAD') == sha, 'Source checkout differs from immutable pin')
    parent, tree = SOURCE_TREES['family']
    require(re.fullmatch('[0-9a-f]{40}', tree), 'Unbound popup family source tree')
    require(git(source, 'show', '-s', '--format=%P', 'HEAD') == parent and git(source, 'rev-parse', 'HEAD^{tree}') == tree, 'Corrected source parent/tree mismatch')
    require(not git(source, 'diff', 'HEAD', '--name-only'), 'Tracked source changed')
    context = read_json(evidence/'context.json')
    for key, value in dict(case=args.case, source_sha=sha, source_parent=parent, source_tree=tree, workflow_sha=args.workflow_sha, api=api, run_id=args.run_id, attempt=args.attempt, selectors=selectors).items():
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
    supervisor_argv = ['python3', str(tooling/'.github/test-evidence/supervise.py'), '--output', str(evidence/'live'), '--run-id', args.run_id, '--attempt', args.attempt, '--sha', sha, '--api', api, '--lane', 'popup-family-'+args.case, '--', *argv]
    require(context['supervisor_argv'] == supervisor_argv, 'Wrong supervisor invocation provenance')
    status = read_json(evidence/'status.json')
    require(status['native_exit'] == 0 and status['cleanup_exit'] == 0 and status['cancel_signal'] is None, 'Native execution/cleanup not successful')
    require(data(evidence/'runner-exit.txt').strip() == b'0' and status['runner_exit'] == 0 and status['phase'] == 'native-terminal', 'Runner exit receipt missing/nonzero')
    captures = {}
    for line in data(evidence/'capture-status.txt').decode().splitlines():
        name, value = line.split('=', 1)
        require(name not in captures and value.isdigit(), 'Ambiguous capture status')
        captures[name] = int(value)
    required_captures = {'logcat', 'crash-buffer', 'windows', 'activities', 'device-output-pull'}
    require(set(captures) == required_captures, 'Incomplete capture statuses')
    require(all(captures[k] == 0 for k in ('logcat', 'crash-buffer', 'windows', 'activities')), 'Native diagnostic capture degraded')
    certificates = data(evidence/'test-input-certificates.txt').decode()
    fingerprints = re.findall(r'^Signer #1 certificate SHA-256 digest: ([0-9a-f]{64})$', certificates, re.M)
    require(len(fingerprints) == 2 and fingerprints[0] == fingerprints[1], 'App/test signing identity mismatch')
    device = read_json(evidence/'device.json')
    require(str(device['api']) == api and re.fullmatch(r'emulator-[0-9]+', device['serial']), 'Wrong native device/API')
    inventory = read_json(evidence/'expected-methods.json')
    classes = sorted({value.split('#')[0] for value in selectors})
    require(inventory['selectors'] == classes and set(expected).issubset(inventory['runtime_methods']), 'Wrong source method inventory')
    require(len(inventory['classes']) == len(classes), 'Wrong source class count')
    for entry in inventory['classes']:
        path = source/entry['source']
        require(path.resolve().is_relative_to(source) and entry['class'] in classes, 'Unexpected source class')
        require(digest(path) == entry['sha256'], 'Source inventory hash mismatch')
        require(git(source, 'hash-object', str(path)) == git(source, 'rev-parse', sha+':'+entry['source']), 'Source file differs from pinned tree')
    require(set(inventory['runtime_methods']) == set(expected), 'Unexpected full-class source methods')
    apk_paths = ['app/build/outputs/apk/debug/app-debug.apk', 'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk']
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
    require(observed['lane'] == published['lane'] == 'popup-family-'+args.case, 'Wrong monitored lane')
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
    methods, xml = xml_methods(source/'app/build/outputs/androidTest-results', expected)
    originals = original_evidence(args.case, source, evidence, api)
    for name in ('logcat', 'crash-buffer', 'windows', 'activities'):
        originals['evidence/'+name+'.txt'] = digest(evidence/(name+'.txt'))
    require(data(evidence/'logcat.txt').strip(), 'Empty native logcat')
    return {'schema_version': 1, 'case': args.case, 'source_sha': sha, 'workflow_sha': args.workflow_sha, 'api': api, 'run_id': args.run_id, 'attempt': args.attempt, 'methods': methods, 'xml_sha256': xml, 'original_sha256': originals, 'source_inventory_sha256': digest(evidence/'expected-methods.json'), 'monitoring_complete': True, 'release_certified': False, 'optical_acceptance': False, 'causal_claim': False}


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
