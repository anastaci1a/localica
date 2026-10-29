#!/usr/bin/env python3
"""Two correctness-only native trials of the immutable, uninstrumented endpoint fix."""
import argparse
import importlib.util
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys

SOURCE_SHA = 'afebeba6d459e97743e6e2dd0bb14f91510db2ca'
SOURCE_TREE = 'ad0a4c9f29232bf2916198f915ea6e4eb2fb2f16'
TEST_SOURCE = 'app/src/androidTest/java/io/github/anastaci1a/localica/PopupShadowContinuityTest.kt'
TEST_SHA256 = '6dcf7b60e81985224095777db59f4174b2433e0d7132f5fbb0cc9c8c71e8012d'
SUPPORT_SHA = 'e3ac4f31ed506b55f39e9fa806010106ea0965bd'
CLASS = 'io.github.anastaci1a.localica.PopupShadowContinuityTest'
EXPECTED = {CLASS+'#'+method for method in (
    'utilityShadowHasOneUnclippedFieldThroughoutRepeatedReversals',
    'nestedShadowHasOneUnclippedFieldThroughoutRepeatedReversals',
    'capsuleReferenceKeepsItsZeroWidthCenterRangeOrdered')}
HELPERS = ['.github/prepare-device-tests.sh', '.github/test-evidence/source_methods.py',
           '.github/test-evidence/observe.py', '.github/test-evidence/supervise.py',
           '.github/test-evidence/publish.mjs']


def require(condition, message):
    if not condition:
        raise ValueError(message)


def git(root, *args):
    return subprocess.check_output(['git', '-C', str(root), *args], text=True).strip()


def load_support(root):
    require(git(root, 'rev-parse', 'HEAD') == SUPPORT_SHA, 'Wrong reviewed support SHA')
    require(git(root, 'status', '--porcelain', '--untracked-files=no') == '', 'Dirty support checkout')
    path = root/'.github/suite-cost/run_paired.py'
    require(not path.is_symlink(), 'Symlinked support module')
    spec = importlib.util.spec_from_file_location('reviewed_paired', path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def no_source_probes(source):
    paths = list((source/'app/src').rglob('*'))
    require(paths, 'Missing app sources')
    for path in paths:
        require('NativeSuiteCost' not in path.name, 'Diagnostic source file present')
        if path.is_file() and path.suffix in ('.kt', '.java'):
            require(b'NativeSuiteCost' not in path.read_bytes(), 'Diagnostic wrapper present')


def check_logs(root):
    files = sorted(root.rglob('logcat-*.txt'))
    expected = {'logcat-'+method.replace('#','-')+'.txt' for method in EXPECTED}
    require(len(files) == 3 and {p.name for p in files} == expected, 'Incomplete/duplicate method logcats')
    for path in files:
        require(not path.is_symlink(), 'Symlinked native log')
        require(b'NativeSuiteCost' not in path.read_bytes(), 'Diagnostic tag present in native logs')


def expected_pngs(api):
    common = ['first-open', 'replacement-window', 'restored-draw-ack']
    common += [f'{phase}-{i}' for phase in ('closing','reopening') for i in range(6)]
    return {f'{kind}-api{api}/{name}{suffix}.png'
            for kind in ('utility','nested')
            for name in common + (['first-trigger-owned-frame'] if kind == 'utility' else [])
            for suffix in ('','-field')}


def check_pngs(root, api, digest):
    paths = sorted(root.rglob('*.png'))
    require(len(paths) == 62, 'Require all 62 original successful PNGs')
    keys = [p.parent.name+'/'+p.name for p in paths]
    require(len(set(keys)) == 62 and set(keys) == expected_pngs(api), 'Missing/extra/duplicate PNG identity')
    receipts = []
    for path, key in zip(paths, keys):
        require(not path.is_symlink() and path.parent.parent.name == 'popup-shadow-continuity', 'Unexpected PNG location')
        raw = path.read_bytes()
        require(len(raw) >= 33 and raw[:8] == b'\x89PNG\r\n\x1a\n' and raw[12:16] == b'IHDR'
                and int.from_bytes(raw[16:20], 'big') > 0 and int.from_bytes(raw[20:24], 'big') > 0,
                'Invalid PNG header/dimensions')
        receipts.append({'identity':key, 'path':str(path), 'sha256':digest(path), 'bytes':len(raw)})
    return receipts


def run(args):
    source, support, output = args.source.resolve(), args.reviewed_harness.resolve(), args.output.resolve()
    for a,b in ((source,support),(source,output),(support,output)):
        require(a != b and a not in b.parents and b not in a.parents, 'Independent non-nested directories required')
    require(not output.exists(), 'Stale evidence refused')
    require(args.api in ('26','36'), 'Unsupported API')
    require(os.environ.get('GITHUB_ACTIONS') == 'true' and os.environ.get('RUNNER_ENVIRONMENT') == 'github-hosted', 'Hosted runner required')
    for key in ('GITHUB_RUN_ID','GITHUB_RUN_ATTEMPT'):
        require(re.fullmatch(r'[0-9]+',os.environ.get(key,'')), 'Missing run identity')
    serial=os.environ.get('ANDROID_SERIAL','emulator-5554')
    require(re.fullmatch(r'emulator-[0-9]+',serial), 'Emulator serial required')
    def adb(*argv):
        return subprocess.check_output(['adb','-s',serial,*argv], text=True).strip()
    require(adb('shell','getprop','ro.kernel.qemu') == '1' and adb('shell','getprop','ro.build.version.sdk') == args.api, 'Wrong emulator/API')
    h=load_support(support)
    runner=h.Run(source,output)
    runner.clean(SOURCE_SHA)
    require(git(source,'rev-parse','HEAD^{tree}') == SOURCE_TREE, 'Wrong source tree')
    no_source_probes(source)
    require(h.digest(source/TEST_SOURCE) == TEST_SHA256, 'Wrong clean product test bytes')
    for path in h.OUTPUTS:
        require(not (source/path).exists() and not (source/path).is_symlink(), 'Stale native outputs')
    output.mkdir(parents=True)
    helper_hashes={p:h.digest(source/p) for p in HELPERS}
    h.write_json(output/'identity.json', {'source_sha':SOURCE_SHA,'source_tree':SOURCE_TREE,'support_sha':SUPPORT_SHA,
                 'support_module_sha256':h.digest(support/'.github/suite-cost/run_paired.py'), 'helpers':helper_hashes,
                 'api':args.api, 'correctness_only':True,'measurement_trials':0,'release_certified':False})
    apk_history={}; rows=[]
    for name in ('C1','C2'):
        trial=output/name;trial.mkdir();status=0
        h.write_json(trial/'identity.json', {'trial':name,'sha':SOURCE_SHA,'api':args.api,
                     'run_id':os.environ['GITHUB_RUN_ID'],'attempt':os.environ['GITHUB_RUN_ATTEMPT']})
        try:
            runner.clean(SOURCE_SHA);no_source_probes(source)
            require({p:h.digest(source/p) for p in HELPERS} == helper_hashes, 'Helper drift')
            for path in h.OUTPUTS:
                require(not (source/path).exists() and not (source/path).is_symlink(), 'Stale trial outputs')
            status,elapsed=runner.command(['./gradlew','assembleDebug','assembleDebugAndroidTest','--no-daemon','--max-workers=2'],log=trial/'compile.log')
            h.write_json(trial/'build.json',{'exit':status,'wall_seconds':elapsed})
            require(status == 0,'Compile failed');runner.clean(SOURCE_SHA)
            runner.command([sys.executable,'.github/test-evidence/source_methods.py','--classes',CLASS,'--output',str(trial/'expected-methods.json')])
            inventory=json.loads((trial/'expected-methods.json').read_text())
            require(inventory['selectors']==[CLASS] and inventory['method_count']==3 and len(inventory['runtime_methods'])==3
                    and set(inventory['runtime_methods']) == EXPECTED, 'Wrong exact native inventory')
            apks=h.check_input_apks(source,trial,SOURCE_SHA,apk_history)
            folder=trial/'apks';folder.mkdir()
            for path in h.APKS:shutil.copyfile(source/path,folder/Path(path).name)
            resets=[]
            for package in ('io.github.anastaci1a.localica.debug','io.github.anastaci1a.localica.debug.test'):
                present='package:'+package in adb('shell','pm','list','packages','--user','0',package).splitlines()
                row={'package':package,'present':present,'action':'absent'}
                if present:
                    response=adb('shell','pm','clear','--user','0',package)
                    row.update(action='clear',response=response)
                    h.write_json(trial/(package+'.clear.json'),row)
                    require(response=='Success','App reset failed')
                resets.append(row)
            h.write_json(trial/'owned-app-reset.json',resets)
            argv=['./gradlew','connectedDebugAndroidTest','--no-daemon','--max-workers=2','-Pandroid.testInstrumentationRunnerArguments.class='+CLASS]
            lane='endpoint-clean-'+name
            env=dict(os.environ,GITHUB_SHA=SOURCE_SHA,TEST_API=args.api)
            invocation=[sys.executable,'.github/test-evidence/supervise.py','--output',str(trial/'live'),
                        '--run-id',env['GITHUB_RUN_ID'],'--attempt',env['GITHUB_RUN_ATTEMPT'],'--sha',SOURCE_SHA,
                        '--api',args.api,'--lane',lane,'--',*argv]
            h.write_json(trial/'invocation.json',{'supervisor_argv':invocation,'native_argv':argv,'run_id':env['GITHUB_RUN_ID'],'attempt':env['GITHUB_RUN_ATTEMPT']})
            status,elapsed=runner.command(invocation,log=trial/'supervised-console.log',env=env,supervisor=True)
            h.write_json(trial/'execution.json',{'exit':status,'wall_seconds':elapsed,'correctness_only':True})
            runner.archive(trial)
            after={p:h.digest(source/p) for p in h.APKS};h.write_json(trial/'output-apks.json',after)
            if after != apks:h.preserve_apk_mismatch(source,trial,after,apks,'APK changed during native run','output')
            require(status==0,'Native/monitor failed');runner.clean(SOURCE_SHA)
            monitoring=h.check_monitor(trial/'live',SOURCE_SHA,args.api,lane,argv)
            result=h.parse_xml(trial/'raw/app/build/outputs/androidTest-results',EXPECTED)
            h.write_json(trial/'native-xml-verified.json',result)
            check_logs(trial/'raw/app/build/outputs/androidTest-results')
            for log in list((trial/'raw').rglob('*.txt')) + [trial/'supervised-console.log']:
                require(b'NativeSuiteCost' not in log.read_bytes(), 'Diagnostic tag in retained native output')
            pngs=check_pngs(trial/'raw/app/build/outputs/connected_android_test_additional_output',args.api,h.digest)
            h.write_json(trial/'verified.json',{'native_xml':result,'pngs':pngs,'monitoring':monitoring,'correctness_only':True})
            rows.append({'trial':name,'passed':3,'skipped':0,'success_pngs':62})
        except BaseException as error:
            try:runner.archive(trial)
            except BaseException as e:(trial/'archive-error.txt').write_text(str(e))
            try:runner.failure_context(trial)
            except BaseException as e:(trial/'failure-context-error.txt').write_text(str(e))
            h.write_json(trial/'failed.json',{'error':str(error),'type':type(error).__name__,'invocation_exit':status,'correctness_only':True})
            return status or (128+runner.cancelled if runner.cancelled else 2)
    h.write_json(output/'correctness.json',{'source_sha':SOURCE_SHA,'api':args.api,'trials':rows,'all_passed':True,
                 'measurement_trials':0,'savings_conclusion_permitted':False,'release_certified':False})
    return 0


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    for name in ('source','reviewed-harness','output'):parser.add_argument('--'+name,type=Path,required=True)
    parser.add_argument('--api',required=True)
    try:return run(parser.parse_args())
    except (ValueError,OSError,KeyError,subprocess.CalledProcessError) as error:
        print('Clean validation rejected: '+str(error),file=sys.stderr);return 2


if __name__=='__main__':sys.exit(main())
