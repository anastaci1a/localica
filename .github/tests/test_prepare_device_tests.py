"""Offline contracts for the current owned API26 disable/restore guard; no real adb."""
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
PACKAGE = "com.google.android.googlequicksearchbox"

MOCK = r'''#!/usr/bin/env python3
import json, os, pathlib, sys
root=pathlib.Path(os.environ['MOCK_ROOT'])
p=root/'state.json';s=json.loads(p.read_text());a=sys.argv[1:]
with (root/'commands.jsonl').open('a') as log:log.write(json.dumps([pathlib.Path(sys.argv[0]).name]+a)+'\n')
def done(code=0, out=''):
    p.write_text(json.dumps(s));print(out,end='');sys.exit(code)
if pathlib.Path(sys.argv[0]).name=='gradlew':
    s['gradle']+=1
    if s['drift']=='state':s['enabled']=1
    if s['drift']=='token':s['token']='foreign'
    if s['drift']=='boot':s['boot']='changed'
    done(s['gradleExit'])
if a==['devices']:done(out='List of devices attached\nemulator-5554\tdevice\n')
if a[:2]!=['-s','emulator-5554']:done(90,'Unexpected device selection')
a=a[2:]
if a==['get-serialno']:done(out='emulator-5554\n')
if a==['emu','avd','name']:done(out='owned-mock-avd\nOK\n')
if a[:2]==['shell','getprop']:
    if a[2]=='ro.kernel.qemu':done(out=s['qemu']+'\n')
    if a[2]=='ro.build.version.sdk':done(s['sdkExit'],s['api']+'\n')
if a==['shell','cat','/proc/sys/kernel/random/boot_id']:done(out=s['boot']+'\n')
if a==['shell','cat','/data/local/tmp/localica-api26-gsa-guard/token']:done(out=s['token']+'\n')
if a==['shell','pm','list','packages','--user','0','com.google.android.googlequicksearchbox']:done(out=s['packages'])
if a==['shell','dumpsys','package','com.google.android.googlequicksearchbox']:
    done(out='userId=10044\ncodePath=/system/priv-app/Velvet\nversionCode=1\n User 0: installed=true enabled='+str(s['enabled'])+'\n')
if len(a)==2 and a[0]=='shell':
    if a[1].startswith("umask 077; mkdir '/data/local/tmp/localica-api26-gsa-guard'"):
        if s['token']:done(1)
        s['token']=a[1].split(' > ')[0].split("'")[-2];done()
    if a[1].startswith('[ "$(cat '):
        if not s['token'] or s['token']=='foreign':done(91)
        s['token']='';done()
if len(a)==7 and a[:2]==['shell','pm'] and a[3:]==['--user','0','com.google.android.googlequicksearchbox']:
    done(92,'Unexpected argument count')
if len(a)==6 and a[:2]==['shell','pm'] and a[3:]==['--user','0','com.google.android.googlequicksearchbox']:
    command=a[2]
    states={'default-state':0,'enable':1,'disable':2,'disable-user':3,'disable-until-used':4}
    if command not in states:done(93)
    if s['mutations']==0:
        if command!='disable-user':done(94)
        if s['disableExit']:done(s['disableExit'])
    elif s['restoreExit']:done(s['restoreExit'])
    s['mutations']+=1;s['enabled']=states[command];done()
done(95,'Unexpected mock adb command: '+repr(a))
'''


class PrepareDeviceTestsTest(unittest.TestCase):
    def setUp(self):
        tmp=tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.root=Path(tmp.name)
        (self.root/'.github').symlink_to(ROOT/'.github',target_is_directory=True)
        for name in ('adb','gradlew'):
            p=self.root/name;p.write_text(MOCK);p.chmod(0o755)
        self.state=dict(api='26',qemu='1',sdkExit=0,packages=f'package:{PACKAGE}\n',enabled=0,
                        token='',boot='mock-boot',mutations=0,gradle=0,gradleExit=0,disableExit=0,restoreExit=0,drift='')
        self.env=dict(os.environ,PATH=str(self.root)+os.pathsep+os.environ['PATH'],MOCK_ROOT=str(self.root),
                      GITHUB_ACTIONS='true',RUNNER_ENVIRONMENT='github-hosted',TEST_API='26',GITHUB_RUN_ID='123',
                      ANDROID_SERIAL='emulator-5554',RISK_CLASSES='')

    def run_script(self,script='prepare-device-tests.sh',**environment):
        (self.root/'state.json').write_text(json.dumps(self.state))
        command=['bash',str(ROOT/'.github'/script)] if script=='run-device-tests.sh' else ['bash','-c','source .github/prepare-device-tests.sh']
        result=subprocess.run(command,cwd=self.root,env=dict(self.env,**environment),capture_output=True,text=True,timeout=15)
        self.state=json.loads((self.root/'state.json').read_text())
        return result

    def commands(self):
        p=self.root/'commands.jsonl'
        return [json.loads(line) for line in p.read_text().splitlines()] if p.exists() else []

    def assert_exit(self,expected,**env):
        r=self.run_script(**env);self.assertEqual(expected,r.returncode,r.stdout+'\n'+r.stderr)

    def test_local_run_never_contacts_any_device(self):
        self.assert_exit(0,GITHUB_ACTIONS='false');self.assertEqual([],self.commands())

    def test_self_hosted_run_never_contacts_any_device(self):
        self.assert_exit(0,RUNNER_ENVIRONMENT='self-hosted');self.assertEqual([],self.commands())

    def test_physical_device_is_rejected_without_mutation(self):
        self.state['qemu']='';self.assert_exit(2);self.assertEqual(0,self.state['mutations'])

    def test_api36_is_untouched(self):
        self.state['api']='36';self.assert_exit(0);self.assertEqual(0,self.state['mutations'])

    def test_missing_api_is_rejected_without_mutation(self):
        self.state['api']='';self.assert_exit(2);self.assertEqual(0,self.state['mutations'])

    def test_failed_property_read_is_not_silently_ignored(self):
        self.state['sdkExit']=17;self.assert_exit(17);self.assertEqual(0,self.state['mutations'])

    def test_absent_package_is_untouched(self):
        self.state['packages']='';self.assert_exit(0);self.assertEqual(0,self.state['mutations'])

    def test_similarly_named_package_is_untouched(self):
        self.state['packages']=f'package:{PACKAGE}.unrelated\n';self.assert_exit(0);self.assertEqual(0,self.state['mutations'])

    def test_only_exact_package_is_disabled_and_restored(self):
        self.assert_exit(0);self.assertEqual(2,self.state['mutations']);self.assertEqual(0,self.state['enabled']);self.assertEqual('',self.state['token'])
        mutations=[a for a in self.commands() if a[:4]==['adb','-s','emulator-5554','shell'] and len(a)>4 and a[4]=='pm' and a[5]!='list']
        self.assertEqual([['adb','-s','emulator-5554','shell','pm',c,'--user','0',PACKAGE] for c in ('disable-user','default-state')],mutations)

    def test_all_prior_states_are_restored_exactly(self):
        for prior in range(5):
            with self.subTest(prior=prior):
                self.state['enabled']=prior;self.state['mutations']=0;self.assert_exit(0)
                self.assertEqual(prior,self.state['enabled']);self.assertEqual('',self.state['token'])

    def test_setup_failure_prevents_starting_instrumentation(self):
        self.state['disableExit']=23;self.assert_exit(23,script='run-device-tests.sh');self.assertEqual(0,self.state['gradle'])

    def test_full_suite_still_runs_without_any_selector(self):
        self.assert_exit(0,script='run-device-tests.sh');self.assertEqual(1,self.state['gradle'])
        self.assertIn(['gradlew','connectedDebugAndroidTest','--no-daemon','--max-workers=2'],self.commands())
        self.assertEqual(0,self.state['enabled']);self.assertEqual('',self.state['token'])

    def test_gradle_failure_is_preserved_after_restore(self):
        self.state['gradleExit']=42;self.assert_exit(42,script='run-device-tests.sh');self.assertEqual(0,self.state['enabled']);self.assertEqual('',self.state['token'])

    def test_cleanup_failure_turns_green_into_failure_but_keeps_existing_failure(self):
        self.state['restoreExit']=17;self.assert_exit(3,script='run-device-tests.sh')
        self.state.update(token='',enabled=0,mutations=0,gradleExit=42);self.assert_exit(42,script='run-device-tests.sh')

    def test_stale_lease_is_never_overwritten(self):
        self.state['token']='foreign';self.assert_exit(1);self.assertEqual('foreign',self.state['token']);self.assertEqual(0,self.state['mutations'])

    def test_foreign_state_token_or_boot_is_not_restored(self):
        for drift in ('state','token','boot'):
            with self.subTest(drift=drift):
                self.state.update(token='',enabled=0,mutations=0,drift=drift,boot='mock-boot')
                self.assert_exit(3,script='run-device-tests.sh');self.assertEqual(1,self.state['mutations']);self.assertTrue(self.state['token'])

    def test_invalid_selector_does_not_mutate_environment(self):
        self.assert_exit(2,script='run-device-tests.sh',RISK_CLASSES='invalid class');self.assertEqual([],self.commands())


if __name__=='__main__':
    unittest.main()
