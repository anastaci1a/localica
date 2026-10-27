"""Offline contract checks. No emulator, Gradle build or network required."""
import json
import os
from pathlib import Path
import shutil
import signal
import subprocess
import tempfile
import time
import unittest

ROOT = Path(__file__).resolve().parents[2]
OBSERVER = Path(os.environ.get('LOCALICA_OBSERVER_TEST_SOURCE', ROOT/'.github/test-evidence/observe.py'))
REQUIRED = 'io.github.anastaci1a.localica.SearchImeMotionTest'
FOCUS = 'io.github.anastaci1a.localica.ListSearchTest'
CONTRAST = 'io.github.anastaci1a.localica.FrostedSurfaceTest'
AFFECTED = 'io.github.anastaci1a.localica.QueueRecoveryTest'
SHA = 'f'*40


class RunnerContract(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        for d in ['.github/test-evidence', 'bin', 'app/src/androidTest/java/io/github/anastaci1a/localica', 'app/build/outputs/apk/debug', 'app/build/outputs/apk/androidTest/debug']:
            (self.root/d).mkdir(parents=True, exist_ok=True)
        shutil.copyfile(ROOT/'.github/run-prototype-tests.sh', self.root/'runner.sh')
        (self.root/'.github/prototype-risk-classes.txt').write_text(REQUIRED+'\n'+FOCUS+'\n'+CONTRAST+'\n'+AFFECTED+'\n')
        for cls in [REQUIRED,FOCUS,CONTRAST,AFFECTED]:
            (self.root/('app/src/androidTest/java/'+cls.replace('.','/')+'.kt')).write_text('package io.github.anastaci1a.localica\nclass '+cls.rsplit('.',1)[1]+' { @org.junit.Test fun regression() {} }\n')
        for apk in ['app/build/outputs/apk/debug/app-debug.apk','app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk']:
            (self.root/apk).write_bytes(b'apk')
        if not OBSERVER.is_file():
            self.fail('Integrate the reviewed observer or provide LOCALICA_OBSERVER_TEST_SOURCE for read-only dependency testing')
        (self.root/'.github/test-evidence/observe.py').symlink_to(OBSERVER.resolve())
        (self.root/'.github/test-evidence/supervise.py').symlink_to((ROOT/'.github/test-evidence/supervise.py').resolve())
        (self.root/'.github/test-evidence/source_methods.py').symlink_to((ROOT/'.github/test-evidence/source_methods.py').resolve())
        self.executable('bin/node', '''#!/usr/bin/env python3
import json,pathlib,sys,time
a=sys.argv
e=pathlib.Path(a[a.index('--evidence-dir')+1]);s=pathlib.Path(a[a.index('--state-dir')+1]);s.mkdir()
while not (e/'manifest.json').exists(): time.sleep(.01)
while json.loads((e/'manifest.json').read_text())['status']=='running':time.sleep(.01)
m=json.loads((e/'manifest.json').read_text())
(s/'complete.json').write_text(json.dumps(dict(m,status='monitoring_complete',observed_failures=m['observed_failure_lines'])))
''')
        (self.root/'.github/prepare-device-tests.sh').write_text('''printf 'setup\\n' >> lifecycle
trap 'status=$?; printf "restore\\n" >> lifecycle; if [[ ${CLEANUP_FAIL:-0} == 1 && $status == 0 ]]; then status=3; fi; exit "$status"' EXIT
trap 'exit 143' TERM
trap 'exit 130' INT
''')
        self.executable('bin/adb', '#!/bin/sh\nprintf \"%s\\n\" \"${ACTUAL_API:-$TEST_API}\"\n')
        self.executable('bin/git', '#!/bin/sh\nprintf "%s\\n" "$GITHUB_SHA"\n')
        self.executable('gradlew', '''#!/usr/bin/env python3
import json,os,pathlib,signal,sys,time,xml.etree.ElementTree as E
pathlib.Path('argv.json').write_text(json.dumps(sys.argv))
mode=os.environ.get('MODE','pass')
classes=sys.argv[-1].split('=',1)[1].split(',')
if mode=='cancel':
    def stop(sig,frame):
        pathlib.Path('child-stopped').touch()
        sys.exit(128+sig)
    signal.signal(signal.SIGTERM,stop)
    signal.signal(signal.SIGINT,stop)
    pathlib.Path('child-ready').touch()
    while True: time.sleep(.05)
if mode=='exit7': sys.exit(7)
if mode=='silent': sys.exit(0)
if mode=='observed': print(classes[0]+' > regression[emulator-5554 - 16] FAILED',flush=True)
root=pathlib.Path('app/build/outputs/androidTest-results/connected/debug');root.mkdir(parents=True,exist_ok=True)
for i,cls in enumerate(classes):
    if mode=='missing-class' and i==1: continue
    suite=E.Element('testsuite',tests='1')
    case=E.SubElement(suite,'testcase',classname=cls,name='regression')
    if mode=='xml-failure' and i==0:E.SubElement(case,'failure')
    if mode=='all-skipped' and i==0:E.SubElement(case,'skipped')
    if os.environ.get('SKIP_CLASS')==cls:
        method=os.environ['SKIP_METHOD']
        skipped=E.SubElement(suite,'testcase',classname=cls,name=method)
        skip=E.SubElement(skipped,'skipped')
        if os.environ.get('SKIP_XML_REASON'):skip.set('message',os.environ['SKIP_XML_REASON'])
        suite.set('tests','2')
        if os.environ.get('SKIP_LOG','yes')=='yes':
            folder=root/'emulator-5554 - fixture';folder.mkdir(exist_ok=True)
            marker=method+'('+cls+')'
            reason=os.environ.get('SKIP_REASON','')
            lines=['10-27 12:00:00.000 123 456 I TestRunner: started: '+marker,
                '10-27 12:00:00.001 123 456 E TestRunner: assumption failed: '+marker]
            if reason:lines.append('10-27 12:00:00.002 123 456 E TestRunner: org.junit.AssumptionViolatedException: '+reason)
            (folder/('logcat-'+cls+'-'+method+'.txt')).write_text(chr(10).join(lines)+chr(10))
    if mode=='bad-count':suite.set('tests','2')
    E.ElementTree(suite).write(root/('TEST-'+str(i)+'.xml'))
''')
        self.env = dict(os.environ, PATH=str(self.root/'bin')+os.pathsep+os.environ['PATH'], AFFECTED_CLASSES=AFFECTED, TEST_API='36', GITHUB_SHA=SHA, GITHUB_RUN_ID='1', GITHUB_RUN_ATTEMPT='1', GITHUB_ACTIONS='true', RUNNER_ENVIRONMENT='github-hosted')

    def executable(self,path,text):
        target=self.root/path;target.write_text(text);target.chmod(0o755)

    def run_case(self, expected=0, **env):
        result=subprocess.run(['bash','runner.sh'],cwd=self.root,env=dict(self.env,**env),capture_output=True,text=True,timeout=12)
        self.assertEqual(expected,result.returncode,result.stdout+'\n'+result.stderr)
        return result

    def test_duplicate_selectors_deduplicate(self):
        self.run_case(AFFECTED_CLASSES=AFFECTED+','+AFFECTED)
        args=json.loads((self.root/'argv.json').read_text())
        self.assertEqual(4,len(args[-1].split('=',1)[1].split(',')))

    def test_focused_only_and_restoration(self):
        self.run_case()
        args=json.loads((self.root/'argv.json').read_text())
        self.assertEqual(1,args.count('connectedDebugAndroidTest'))
        self.assertEqual(set(args[-1].split('=',1)[1].split(',')),{REQUIRED,FOCUS,CONTRAST,AFFECTED})
        self.assertEqual('setup\nrestore\n',(self.root/'lifecycle').read_text())
        evidence=json.loads((self.root/'prototype-evidence/reconciled-tests.json').read_text())
        self.assertEqual({REQUIRED,FOCUS,CONTRAST,AFFECTED},set(evidence['counts']))
        self.assertFalse(evidence['release_certified'])

    def test_publisher_failure_holds_success(self):
        self.executable('bin/node', '#!/bin/sh\nexit 17\n')
        self.run_case(3)
        receipt=json.loads((self.root/'prototype-evidence/live/supervision.json').read_text())
        self.assertEqual(0,receipt['observer_exit'])
        self.assertEqual(17,receipt['publisher_exit'])
        self.assertFalse(receipt['monitoring_complete'])

    def test_test_failure_wins_over_publisher_failure(self):
        self.executable('bin/node', '#!/bin/sh\nexit 17\n')
        self.run_case(7,MODE='exit7')

    def test_invalid_publisher_completion_holds_success(self):
        self.executable('bin/node', '''#!/usr/bin/env python3
import pathlib,sys
p=pathlib.Path(sys.argv[sys.argv.index('--state-dir')+1]);p.mkdir();(p/'complete.json').write_text('{}')
''')
        self.run_case(3)

    def test_omitted_source_method_blocks_green_xml(self):
        path=self.root/('app/src/androidTest/java/'+AFFECTED.replace('.','/')+'.kt')
        path.write_text(path.read_text().rsplit('}',1)[0]+' @org.junit.Test fun omitted() {} }')
        self.run_case(1)

    def test_empty(self): self.run_case(2,AFFECTED_CLASSES='')
    def test_mandatory_alone_is_not_affected_coverage(self): self.run_case(2,AFFECTED_CLASSES=REQUIRED)
    def test_injection(self):
        self.run_case(2,AFFECTED_CLASSES=AFFECTED+';touch PWNED')
        self.assertFalse((self.root/'PWNED').exists())
    def test_unknown(self): self.run_case(2,AFFECTED_CLASSES='io.github.anastaci1a.localica.UnknownTest')
    def test_twenty_distinct_affected_classes_keep_all_mandatory_regressions(self):
        classes = ['io.github.anastaci1a.localica.Additional%dTest' % i for i in range(20)]
        with (self.root/'.github/prototype-risk-classes.txt').open('a') as inventory:
            inventory.write('\n'.join(classes)+'\n')
        for cls in classes:
            (self.root/('app/src/androidTest/java/'+cls.replace('.','/')+'.kt')).write_text(
                'package io.github.anastaci1a.localica\nclass '+cls.rsplit('.',1)[1]+
                ' { @org.junit.Test fun regression() {} }\n')
        self.run_case(AFFECTED_CLASSES=','.join(classes))
        evidence=json.loads((self.root/'prototype-evidence/reconciled-tests.json').read_text())
        self.assertEqual(set(classes)|{REQUIRED,FOCUS,CONTRAST},set(evidence['counts']))

    def distinct_affected_sources(self, count):
        classes = ['io.github.anastaci1a.localica.Coverage%dTest' % i for i in range(count)]
        with (self.root/'.github/prototype-risk-classes.txt').open('a') as inventory:
            inventory.write('\n'.join(classes)+'\n')
        for cls in classes:
            (self.root/('app/src/androidTest/java/'+cls.replace('.','/')+'.kt')).write_text(
                'package io.github.anastaci1a.localica\nclass '+cls.rsplit('.',1)[1]+
                ' { @org.junit.Test fun regression() {} }\n')
        return classes

    def test_twenty_one_distinct_affected_classes_keep_all_mandatory_regressions(self):
        classes = self.distinct_affected_sources(21)
        self.run_case(AFFECTED_CLASSES=','.join(classes))
        evidence=json.loads((self.root/'prototype-evidence/reconciled-tests.json').read_text())
        self.assertEqual(set(classes)|{REQUIRED,FOCUS,CONTRAST},set(evidence['counts']))
        args=json.loads((self.root/'argv.json').read_text())
        self.assertEqual(set(classes)|{REQUIRED,FOCUS,CONTRAST},set(args[-1].split('=',1)[1].split(',')))

    def test_forty_eight_distinct_affected_classes_are_rejected_before_instrumentation(self):
        classes = self.distinct_affected_sources(48)
        self.run_case(2,AFFECTED_CLASSES=','.join(classes))
        self.assertFalse((self.root/'argv.json').exists())

    def test_forty_seven_distinct_affected_classes_keep_all_mandatory_regressions(self):
        classes = self.distinct_affected_sources(47)
        self.run_case(AFFECTED_CLASSES=','.join(classes))
        evidence=json.loads((self.root/'prototype-evidence/reconciled-tests.json').read_text())
        self.assertEqual(set(classes)|{REQUIRED,FOCUS,CONTRAST},set(evidence['counts']))
        args=json.loads((self.root/'argv.json').read_text())
        self.assertEqual(set(classes)|{REQUIRED,FOCUS,CONTRAST},set(args[-1].split('=',1)[1].split(',')))

    def skip_source(self, cls, method):
        with (self.root/'.github/prototype-risk-classes.txt').open('a') as stream:
            stream.write(cls+'\n')
        (self.root/('app/src/androidTest/java/'+cls.replace('.','/')+'.kt')).write_text(
            'package io.github.anastaci1a.localica\nclass '+cls.rsplit('.',1)[1]+
            ' { @org.junit.Test fun regression() {} @org.junit.Test fun '+method+'() {} }\n')
        return dict(AFFECTED_CLASSES=cls, SKIP_CLASS=cls, SKIP_METHOD=method)

    def test_profile_skip_requires_exact_identity_reason_and_keeps_evidence(self):
        cls='io.github.anastaci1a.localica.DialogChromeLayoutTest'
        method='native360x640Font2RetainsSharedChromeAndEveryRepeatControl'
        reason='Requires sufficient display space and actual Android font scale 2'
        self.run_case(**self.skip_source(cls,method),SKIP_REASON=reason)
        evidence=json.loads((self.root/'prototype-evidence/reconciled-tests.json').read_text())
        self.assertEqual([cls+'#'+method],[x['id'] for x in evidence['qualified_skips']])
        self.assertEqual(reason,evidence['qualified_skips'][0]['reason'])
        self.assertEqual('36',str(evidence['qualified_skips'][0]['api']))
        self.assertIn('logcat-',evidence['qualified_skips'][0]['evidence'])

    def accepted_profile(self, cls, method, reason, api):
        self.run_case(**self.skip_source('io.github.anastaci1a.localica.'+cls,method),TEST_API=api,SKIP_REASON=reason)
        evidence=json.loads((self.root/'prototype-evidence/reconciled-tests.json').read_text())
        self.assertEqual(1,len(evidence['qualified_skips']))
        self.assertEqual(reason,evidence['qualified_skips'][0]['reason'])

    def test_repeat_exact360_observed_profile_reason(self):
        self.accepted_profile('RepeatOptionsEnvelopeTest','native360x640Font2SectionsExposeEveryControlAndStageDrafts',
            'Exact native window profile requires sufficient display space and actual Android font scale 2','26')

    def test_repeat_exact400_observed_profile_reason(self):
        self.accepted_profile('RepeatOptionsEnvelopeTest','native400x800Font2SectionsExposeEveryControlAndStageDrafts',
            'Exact native window profile requires sufficient display space and actual Android font scale 2','36')

    def test_repeat_phone360_observed_profile_reason(self):
        self.accepted_profile('RepeatOptionsEnvelopeTest','native360x640PhoneFont2SectionsFitSafeInsetsWithoutWindowResize',
            'Requires the lead-configured 360x640 phone/font2 focused run','26')

    def test_repeat_phone400_observed_profile_reason(self):
        self.accepted_profile('RepeatOptionsEnvelopeTest','native400x800PhoneFont2SectionsFitSafeInsetsWithoutWindowResize',
            'Requires the lead-configured 400x800 phone/font2 focused run','36')

    def test_chrome400_observed_profile_reason(self):
        self.accepted_profile('DialogChromeLayoutTest','native400x800Font2RetainsSharedChromeAndEveryRepeatControl',
            'Requires sufficient display space and actual Android font scale 2','26')

    def test_tag_editor_observed_profile_reason(self):
        self.accepted_profile('TagEditorChromeLayoutTest','native360x640Font2KeepsSharedChromeAndScrollableFullSizeFields',
            'Requires sufficient display space and the requested actual system font scale','36')

    def test_phone_profile_cannot_borrow_the_other_dimension_reason(self):
        self.run_case(1,**self.skip_source('io.github.anastaci1a.localica.RepeatOptionsEnvelopeTest',
            'native360x640PhoneFont2SectionsFitSafeInsetsWithoutWindowResize'),
            SKIP_REASON='Requires the lead-configured 400x800 phone/font2 focused run')

    def test_xml_reason_cannot_contradict_original_method_log(self):
        self.run_case(1,**self.skip_source('io.github.anastaci1a.localica.DialogChromeLayoutTest',
            'native360x640Font2RetainsSharedChromeAndEveryRepeatControl'),
            SKIP_REASON='Requires sufficient display space and actual Android font scale 2',SKIP_XML_REASON='Contradiction')

    def test_profile_skip_wrong_method_is_rejected(self):
        self.run_case(1,**self.skip_source('io.github.anastaci1a.localica.DialogChromeLayoutTest','wrongMethod'),
            SKIP_REASON='Requires sufficient display space and actual Android font scale 2')

    def test_profile_skip_wrong_reason_is_rejected(self):
        self.run_case(1,**self.skip_source('io.github.anastaci1a.localica.DialogChromeLayoutTest',
            'native360x640Font2RetainsSharedChromeAndEveryRepeatControl'),SKIP_REASON='Different reason')

    def test_profile_skip_missing_observed_reason_is_rejected(self):
        self.run_case(1,**self.skip_source('io.github.anastaci1a.localica.DialogChromeLayoutTest',
            'native360x640Font2RetainsSharedChromeAndEveryRepeatControl'),SKIP_REASON='')

    def test_empty_xml_skip_without_method_log_is_not_guessed(self):
        self.run_case(1,**self.skip_source('io.github.anastaci1a.localica.DialogChromeLayoutTest',
            'native360x640Font2RetainsSharedChromeAndEveryRepeatControl'),SKIP_LOG='no')

    def test_api26_native_blur_skip_is_qualified_by_observed_default_junit_reason(self):
        cls='io.github.anastaci1a.localica.PlayerBackgroundPolarityTest'
        method='nativeBlurRetainsVerifiedInkAsDistinctUnavailableThenRecovers'
        self.run_case(**self.skip_source(cls,method),TEST_API='26',SKIP_REASON='got: <false>, expected: is <true>')
        evidence=json.loads((self.root/'prototype-evidence/reconciled-tests.json').read_text())
        self.assertEqual('got: <false>, expected: is <true>',evidence['qualified_skips'][0]['reason'])

    def test_api36_native_blur_must_run(self):
        self.run_case(1,**self.skip_source('io.github.anastaci1a.localica.PlayerBackgroundPolarityTest',
            'nativeBlurRetainsVerifiedInkAsDistinctUnavailableThenRecovers'),SKIP_REASON='got: <false>, expected: is <true>')

    def test_unrelated_class_cannot_borrow_profile_reason(self):
        self.run_case(1,**self.skip_source('io.github.anastaci1a.localica.OtherProfileTest',
            'native360x640Font2RetainsSharedChromeAndEveryRepeatControl'),
            SKIP_REASON='Requires sufficient display space and actual Android font scale 2')

    def test_optimized_python_cannot_disable_reconciliation(self):
        self.run_case(1,PYTHONOPTIMIZE='1')

    def test_too_many(self): self.run_case(2,AFFECTED_CLASSES=','.join([AFFECTED]*48))
    def test_too_long(self): self.run_case(2,AFFECTED_CLASSES='x'*4097)
    def test_missing_source(self):
        (self.root/('app/src/androidTest/java/'+AFFECTED.replace('.','/')+'.kt')).unlink()
        self.run_case(2)
    def test_missing_observer(self):
        (self.root/'.github/test-evidence/observe.py').unlink();self.run_case(2)
    def test_non_hosted(self): self.run_case(2,RUNNER_ENVIRONMENT='self-hosted')
    def test_actual_sdk_must_match_declared_api(self): self.run_case(2,ACTUAL_API='26')
    def test_bad_api(self): self.run_case(2,TEST_API='35')
    def test_bad_sha(self): self.run_case(2,GITHUB_SHA='main')
    def test_stale_evidence(self):
        (self.root/'prototype-evidence').mkdir();self.run_case(2)
    def test_stale_xml(self):
        path=self.root/'app/build/outputs/androidTest-results';path.mkdir();(path/'old.xml').touch();self.run_case(2)
    def test_original_failure_preserved(self):
        self.run_case(7,MODE='exit7');self.assertEqual('setup\nrestore\n',(self.root/'lifecycle').read_text())
    def test_cleanup_failure_blocks_success(self): self.run_case(3,CLEANUP_FAIL='1')
    def test_observed_failure_blocks_zero_exit(self): self.run_case(1,MODE='observed')
    def test_missing_xml_blocks_zero_exit(self): self.run_case(1,MODE='silent')
    def test_xml_failure_blocks_zero_exit(self): self.run_case(1,MODE='xml-failure')
    def test_missing_required_class(self): self.run_case(1,MODE='missing-class')
    def test_skipped_required_class(self): self.run_case(1,MODE='all-skipped')
    def test_bad_xml_count(self): self.run_case(1,MODE='bad-count')
    def test_cancel_restores_after_child_stops(self):
        child=subprocess.Popen(['bash','runner.sh'],cwd=self.root,env=dict(self.env,MODE='cancel'),stdout=subprocess.PIPE,stderr=subprocess.PIPE)
        try:
            deadline=time.monotonic()+5
            while not (self.root/'child-ready').exists() and time.monotonic()<deadline: time.sleep(.02)
            self.assertTrue((self.root/'child-ready').exists())
            child.send_signal(signal.SIGTERM)
            out,err=child.communicate(timeout=8)
            self.assertEqual(143,child.returncode,(out,err))
            self.assertTrue((self.root/'child-stopped').exists())
            self.assertEqual('setup\nrestore\n',(self.root/'lifecycle').read_text())
        finally:
            if child.poll() is None:child.kill();child.communicate()


class WorkflowContract(unittest.TestCase):
    def decision(self, failed=False, missing=False, wrong_sha=False, contradictory=False, malformed=False, partial_methods=False, skipped_required_method=False):
        import yaml, hashlib
        workflow=yaml.safe_load((ROOT/'.github/workflows/prototype.yml').read_text())
        script=workflow['jobs']['evidence']['steps'][1]['run']
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder); evidence=root/'evidence';evidence.mkdir()
            canonical=evidence/'prototype-build-internal-unvalidated';canonical.mkdir()
            (canonical/'app-debug.apk').write_bytes(b'canonical')
            (canonical/'provenance.json').write_text(json.dumps({'sha':'a'*40 if wrong_sha else SHA,'apk_sha256':hashlib.sha256(b'canonical').hexdigest(),'run_id':'1','run_attempt':'1','signing_certificate':'verified-test-certificate'}))
            checks={k:{'result':'success'} for k in workflow['jobs']['evidence']['needs']}
            if failed:checks['focused_36']['result']='failure'
            if not missing:
                for api in (26,36):
                    focused=evidence/f'prototype-focused-api-{api}'/'prototype-evidence';focused.mkdir(parents=True)
                    inventory={'selectors':[REQUIRED,FOCUS,CONTRAST,AFFECTED],'runtime_methods':[c+'#regression' for c in (REQUIRED,FOCUS,CONTRAST,AFFECTED)]}
                    inventory_bytes=json.dumps(inventory).encode()
                    (focused/'expected-methods.json').write_bytes(inventory_bytes)
                    (focused/'reconciled-tests.json').write_text(json.dumps({'source_inventory_sha256':hashlib.sha256(inventory_bytes).hexdigest(),'required_selectors':[REQUIRED,FOCUS,CONTRAST],'methods':{c+'#regression':'passed' for c in (REQUIRED,FOCUS,CONTRAST,AFFECTED)},'sha':SHA,'api':str(api),'selectors':[REQUIRED,FOCUS,CONTRAST,AFFECTED],'counts':{c:{'passed':1,'failed':0,'errors':0,'skipped':0} for c in (REQUIRED,FOCUS,CONTRAST,AFFECTED)}}))
                    if partial_methods:
                        data=json.loads((focused/'reconciled-tests.json').read_text())
                        data['methods'].pop(REQUIRED+'#regression')
                        (focused/'reconciled-tests.json').write_text(json.dumps(data))
                    if skipped_required_method:
                        inventory['runtime_methods'].append(REQUIRED+'#skippedRequired')
                        inventory_bytes=json.dumps(inventory).encode()
                        (focused/'expected-methods.json').write_bytes(inventory_bytes)
                        data=json.loads((focused/'reconciled-tests.json').read_text())
                        data['source_inventory_sha256']=hashlib.sha256(inventory_bytes).hexdigest()
                        data['methods'][REQUIRED+'#skippedRequired']='skipped'
                        data['counts'][REQUIRED]['skipped']=1
                        (focused/'reconciled-tests.json').write_text(json.dumps(data))
                    if contradictory:
                        data=json.loads((focused/'reconciled-tests.json').read_text());data['counts'][REQUIRED]['failed']=1
                        (focused/'reconciled-tests.json').write_text(json.dumps(data))
                    if malformed:(focused/'reconciled-tests.json').write_text('{broken')
                    (focused/'test-input-apks.sha256').write_text('independently-built-test-inputs')
                    compatibility=evidence/f'prototype-compatibility-api-{api}';compatibility.mkdir()
                    (compatibility/'provenance.json').write_text(json.dumps({'source_sha':SHA}))
            env=dict(os.environ,CHECKS=json.dumps(checks),KNOWN_ISSUES='Not a release',AFFECTED_CLASSES=AFFECTED,COVERAGE_REASON='Queue regression',GITHUB_SHA=SHA,GITHUB_REF='refs/heads/test',GITHUB_RUN_ID='1',GITHUB_RUN_ATTEMPT='1',GITHUB_STEP_SUMMARY=str(root/'summary'))
            result=subprocess.run(['bash','-c',script],cwd=root,env=env,capture_output=True,text=True)
            out=root/'prototype-delivery'
            manifest=json.loads((out/'manifest.json').read_text()) if (out/'manifest.json').exists() else None
            return result,manifest,list(out.glob('*.apk'))

    def test_delivery_requires_all_five_green_and_exact_evidence(self):
        result,manifest,apks=self.decision()
        self.assertEqual(0,result.returncode,result.stderr)
        self.assertTrue(manifest['ready_for_prototype_delivery'])
        self.assertFalse(manifest['release_certified'])
        self.assertEqual(1,len(apks))
        self.assertEqual('not-run',manifest['full_native_api_36'])

    def test_failed_check_produces_manifest_without_delivery_apk(self):
        result,manifest,apks=self.decision(failed=True)
        self.assertEqual(0,result.returncode,result.stderr)
        self.assertFalse(manifest['ready_for_prototype_delivery'])
        self.assertEqual('failed',manifest['checks']['focused_36'])
        self.assertEqual([],apks)

    def test_missing_receipts_block_green_statuses(self):
        result,manifest,apks=self.decision(missing=True)
        self.assertNotEqual(0,result.returncode)
        self.assertFalse(manifest['ready_for_prototype_delivery'])
        self.assertEqual([],apks)

    def test_contradictory_receipts_block_green_statuses(self):
        result,manifest,apks=self.decision(contradictory=True)
        self.assertNotEqual(0,result.returncode)
        self.assertFalse(manifest['ready_for_prototype_delivery'])
        self.assertTrue(manifest['validation_errors'])
        self.assertEqual([],apks)

    def test_malformed_receipts_still_produce_held_manifest(self):
        result,manifest,apks=self.decision(malformed=True)
        self.assertNotEqual(0,result.returncode)
        self.assertFalse(manifest['ready_for_prototype_delivery'])
        self.assertTrue(manifest['validation_errors'])
        self.assertEqual([],apks)

    def test_class_counts_cannot_hide_an_omitted_method(self):
        result,manifest,apks=self.decision(partial_methods=True)
        self.assertNotEqual(0,result.returncode)
        self.assertFalse(manifest['ready_for_prototype_delivery'])
        self.assertEqual([],apks)

    def test_a_passing_method_cannot_hide_a_skipped_required_regression(self):
        result,manifest,apks=self.decision(skipped_required_method=True)
        self.assertNotEqual(0,result.returncode)
        self.assertFalse(manifest['ready_for_prototype_delivery'])
        self.assertEqual([],apks)

    def test_wrong_sha_blocks_delivery(self):
        result,manifest,apks=self.decision(wrong_sha=True)
        self.assertNotEqual(0,result.returncode)
        self.assertFalse(manifest['ready_for_prototype_delivery'])
        self.assertTrue(manifest['validation_errors'])
        self.assertEqual([],apks)

    def test_compatibility_archive_keeps_the_original_report_root(self):
        import yaml
        workflow=yaml.safe_load((ROOT/'.github/workflows/prototype.yml').read_text())
        for api in (26,36):
            steps=workflow['jobs'][f'compatibility_{api}']['steps']
            upload=next(s for s in steps if s.get('with',{}).get('name')==f'prototype-compatibility-api-{api}')
            self.assertEqual('${{ runner.temp }}/shuffle-compatibility/reports/',upload['with']['path'])
            preserve=next(s for s in steps if s.get('name')=='Preserve compatibility runner identity')
            self.assertEqual('always()',preserve['if'])
            self.assertIn('reports/prototype-runner-environment.txt',preserve['run'])

    def test_opt_in_only_and_five_required_jobs(self):
        import yaml
        workflow=yaml.safe_load((ROOT/'.github/workflows/prototype.yml').read_text())
        self.assertEqual({'workflow_dispatch','workflow_call'},set(workflow['on']))
        self.assertEqual({'affected_classes','coverage_reason','known_issues'},set(workflow['on']['workflow_call']['inputs']))
        self.assertEqual({'ANDROID_DEBUG_KEYSTORE'},set(workflow['on']['workflow_call']['secrets']))
        self.assertEqual({'contents':'read'},workflow['permissions'])
        jobs=workflow['jobs']
        self.assertEqual({'build','focused_26','focused_36','compatibility_26','compatibility_36'},set(jobs['evidence']['needs']))
        self.assertEqual('${{ always() }}',jobs['evidence']['if'])
        for name in ('focused_26','focused_36'):
            script='\n'.join(s.get('with',{}).get('script','') for s in jobs[name]['steps'])
            self.assertIn('bash .github/run-prototype-tests.sh',script)
            self.assertNotIn('run-device-tests.sh',script)
        for name in ('compatibility_26','compatibility_36'):
            self.assertTrue(any('bash .github/compatibility/run.sh' in s.get('with',{}).get('script','') for s in jobs[name]['steps']))
        self.assertNotIn('release',jobs)
        for job in jobs.values():
            for step in job['steps']:
                if 'run' in step:
                    subprocess.run(['bash','-n'],input=step['run'],text=True,check=True)
                if 'uses' in step:
                    self.assertRegex(step['uses'],r'@[0-9a-f]{40}$')



class InsertedWorkflowContract(unittest.TestCase):
    def test_exact_approved_classes_retain_prior_gate_and_exclude_diagnostics(self):
        import re
        text=(ROOT/'.github/workflows/prototype-dev72-inserted.yml').read_text()
        line=re.findall(r'^      affected_classes: (.+)$',text,re.MULTILINE)
        self.assertEqual(1,len(line))
        actual=line[0].split(',')
        expected=set(["io.github.anastaci1a.localica.ActionStripDrawingTest","io.github.anastaci1a.localica.AdvancedRepeatTest","io.github.anastaci1a.localica.ArtistAlbumFilterRangeTest","io.github.anastaci1a.localica.ArtistAlbumSelectionTest","io.github.anastaci1a.localica.ArtistCollectionActionsTest","io.github.anastaci1a.localica.ArtistSelectionPlacementTest","io.github.anastaci1a.localica.ArtworkCropLoadingTest","io.github.anastaci1a.localica.ArtworkOccurrenceTransitionTest","io.github.anastaci1a.localica.ArtworkSwipeTest","io.github.anastaci1a.localica.BrowseOrderViewportTest","io.github.anastaci1a.localica.ButtonGestureTest","io.github.anastaci1a.localica.CollectionControlsBackdropTest","io.github.anastaci1a.localica.CollectionDetailTest","io.github.anastaci1a.localica.CollectionHeaderPinchFrameTest","io.github.anastaci1a.localica.CompactCollectionArtworkTest","io.github.anastaci1a.localica.DialogChromeLayoutTest","io.github.anastaci1a.localica.GlassActionRowTest","io.github.anastaci1a.localica.GlassDialogLateReopenTest","io.github.anastaci1a.localica.GlassDialogReopenTest","io.github.anastaci1a.localica.GlassDialogsTest","io.github.anastaci1a.localica.GlassMorphTest","io.github.anastaci1a.localica.GlassShadowTest","io.github.anastaci1a.localica.LibraryDatabaseTest","io.github.anastaci1a.localica.LibraryPlaybackReadTest","io.github.anastaci1a.localica.ListReturnControlsTest","io.github.anastaci1a.localica.ListTopFadeTest","io.github.anastaci1a.localica.LoadingMaterialOpacityTest","io.github.anastaci1a.localica.LyricsHeaderActionsTest","io.github.anastaci1a.localica.LyricsPlayerReopenTest","io.github.anastaci1a.localica.LyricsReturnBackdropTest","io.github.anastaci1a.localica.LyricsViewportMotionTest","io.github.anastaci1a.localica.PinchReadbackLifetimeTest","io.github.anastaci1a.localica.PinchReleaseHandoffTest","io.github.anastaci1a.localica.PlaybackIntentTest","io.github.anastaci1a.localica.PlayerBackgroundPolarityTest","io.github.anastaci1a.localica.PlayerExpansionMotionTest","io.github.anastaci1a.localica.PlaylistCoverActionTest","io.github.anastaci1a.localica.PlaylistDetailPresentationTest","io.github.anastaci1a.localica.PreparedPlayerVisualCacheTest","io.github.anastaci1a.localica.QueueFollowingTest","io.github.anastaci1a.localica.QueueOrderActionAdmissionTest","io.github.anastaci1a.localica.QueueSaveOriginTest","io.github.anastaci1a.localica.RepeatOptionsEnvelopeTest","io.github.anastaci1a.localica.RetainedLayerReadbackTest","io.github.anastaci1a.localica.SongInformationTest","io.github.anastaci1a.localica.TagEditorChromeLayoutTest","io.github.anastaci1a.localica.TagEditorThemeInheritanceTest"])
        self.assertEqual(47,len(actual))
        self.assertEqual(expected,set(actual))
        inventory=(ROOT/'.github/prototype-risk-classes.txt').read_text().splitlines()
        self.assertTrue(expected.issubset(inventory))
        self.assertFalse({REQUIRED,FOCUS,CONTRAST}.intersection(actual))
        self.assertLessEqual(len(','.join(sorted(expected|{REQUIRED,FOCUS,CONTRAST}))),4096)
        self.assertNotIn('PopupFamilyPresentationTest',line[0])
        self.assertNotIn('CollectionHeaderInputRetentionTest',line[0])
        self.assertIn('uses: ./.github/workflows/prototype.yml',text)
        self.assertIn('branches: [test/dev72-inserted-prototype-20261027]',text)
        self.assertIn('release clearance',text)
        self.assertIn('dependent coverage review',text)

    def test_historical_dev71_workflow_remains_byte_identical(self):
        import hashlib
        self.assertEqual('a6b884a815d2703532aa8679678cc2d7e4b67a1a322a7e66f0a29b6e17e3b542',hashlib.sha256((ROOT/'.github/workflows/prototype-dev71-apk1.yml').read_bytes()).hexdigest())


if __name__=='__main__':unittest.main()
