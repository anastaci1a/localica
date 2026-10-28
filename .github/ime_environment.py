"""Capture and reject mismatching original API36 image/emulator configuration before native tests."""
import hashlib,json,os
from pathlib import Path
import subprocess,sys

def require(v,m):
    if not v:raise ValueError(m)
def properties(raw):
    values={}
    for line in raw.decode().splitlines():
        if '=' in line and not line.startswith('#'):
            k,v=line.split('=',1);values[k.strip()]=v.strip()
    return values
EXPECTED={'system-image':{'Pkg.Revision':'2','AndroidVersion.ApiLevel':'36','AndroidVersion.ExtensionLevel':'17','SystemImage.Abi':'x86_64','SystemImage.TagId':'default'},'emulator':{'Pkg.Revision':'37.2.12','Pkg.BuildId':'16428233'},'build-tools':{'Pkg.Revision':'35.0.0'}}
def validate_receipt(result):
    require(result['status']=='matched-original-recorded-revisions-and-launch-configuration','Environment identity unverified')
    require(result['expected']==EXPECTED and result['original_binary_hashes_available'] is False and result['full_environment_equivalence'] is False,'Environment scope/pins differ')
    require(set(result['files'])=={'system-image','emulator','build-tools','avd-config'},'Environment file inventory incomplete')
    suffixes={'system-image':'system-images/android-36/default/x86_64/source.properties','emulator':'emulator/source.properties','build-tools':'build-tools/35.0.0/source.properties','avd-config':'.android/avd/test.avd/config.ini'}
    for kind,entry in result['files'].items():
        raw=entry['text'].encode();values=properties(raw)
        require(entry['sha256']==hashlib.sha256(raw).hexdigest() and entry['properties']==values,'Environment raw identity mismatch')
        require(entry['path'].endswith('/'+suffixes[kind]),'Wrong environment identity path')
        for key,value in EXPECTED.get(kind,{}).items():require(values.get(key)==value,'Original '+kind+' '+key+' differs')
    require(result['files']['avd-config']['properties'].get('hw.cpu.ncore')=='2','Emulator cores differ')
    require(result['processes'] and isinstance(result['fingerprint'],str) and result['fingerprint'].strip(),'Missing emulator process/fingerprint')
    for process in result['processes']:
        args=process['argv'];require(type(process['pid']) is int and process['pid']>0,'Invalid emulator PID')
        require(Path(args[0]).name in ('emulator','qemu-system-x86_64','qemu-system-x86_64-headless'),'Not emulator argv')
        for key,value in [('-avd','test'),('-gpu','lavapipe'),('-memory','3072'),('-port','5554')]:require(args.count(key)==1 and args[args.index(key)+1]==value,'Emulator '+key+' differs')
        require('-no-snapshot' in args,'Snapshot reuse forbidden')
    return True

def verify(evidence):
    sdk=Path(os.environ['ANDROID_HOME']);root=Path(evidence)
    result=dict(status='rejected',expected=EXPECTED,files={},processes=[],original_binary_hashes_available=False,full_environment_equivalence=False)
    try:
        paths={'system-image':sdk/'system-images/android-36/default/x86_64/source.properties','emulator':sdk/'emulator/source.properties','build-tools':sdk/'build-tools/35.0.0/source.properties','avd-config':Path.home()/'.android/avd/test.avd/config.ini'}
        for kind,path in paths.items():
            require(path.is_file() and not path.is_symlink(),'Missing identity file '+str(path))
            raw=path.read_bytes();values=properties(raw)
            result['files'][kind]=dict(path=str(path),sha256=hashlib.sha256(raw).hexdigest(),text=raw.decode(),properties=values)
            for key,value in EXPECTED.get(kind,{}).items():require(values.get(key)==value,'Original '+kind+' '+key+' differs')
        require(result['files']['avd-config']['properties'].get('hw.cpu.ncore')=='2','Emulator cores differ')
        for path in Path('/proc').glob('[0-9]*/cmdline'):
            try:args=[os.fsdecode(a) for a in path.read_bytes().rstrip(b'\0').split(b'\0')]
            except OSError:continue
            if not args or Path(args[0]).name not in ('emulator','qemu-system-x86_64','qemu-system-x86_64-headless'):continue
            if '-avd' in args and args[args.index('-avd')+1]=='test':result['processes'].append(dict(pid=int(path.parent.name),argv=args))
        require(result['processes'],'Cannot verify owned emulator argv')
        for process in result['processes']:
            args=process['argv']
            for key,value in [('-gpu','lavapipe'),('-memory','3072'),('-port','5554')]:require(args.count(key)==1 and args[args.index(key)+1]==value,'Emulator '+key+' differs')
            require('-no-snapshot' in args,'Snapshot reuse forbidden')
        result['fingerprint']=subprocess.check_output(['adb','-s','emulator-5554','shell','getprop','ro.build.fingerprint'],text=True,timeout=10).strip()
        require(result['fingerprint'],'Missing current image fingerprint')
        result['status']='matched-original-recorded-revisions-and-launch-configuration'
        validate_receipt(result)
    except (OSError,ValueError,subprocess.SubprocessError) as e:
        result['error']=str(e);result['status']='rejected'
    with (root/'environment-identity.json').open('x') as f:json.dump(result,f,indent=2);f.write('\n')
    return 0 if result['status'].startswith('matched-') else 3
if __name__=='__main__':sys.exit(verify(sys.argv[1]))
