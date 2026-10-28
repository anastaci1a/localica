"""Read-only fail-closed hosted emulator/image/AVD launch configuration receipts.
Matches recorded package revisions and requested launch options, not original binary/APK equivalence.
"""
import hashlib,json,os,re,subprocess,sys
from pathlib import Path

def require(v,m):
    if not v:raise ValueError(m)
def properties(raw):
    out={}
    for line in raw.decode().splitlines():
        line=line.strip()
        if line and not line.startswith('#') and '=' in line:
            k,v=line.split('=',1);k=k.strip();v=v.strip()
            require(k not in out or out[k]==v,'Conflicting duplicate property: '+k)
            out[k]=v
    return out
PROFILES={'26':dict(tag='google_apis',revision='16',gpu='swiftshader_indirect'),'36':dict(tag='default',revision='2',gpu='lavapipe')}
def expected(api):
    p=PROFILES[api]
    image={'Pkg.Revision':p['revision'],'AndroidVersion.ApiLevel':api,'SystemImage.Abi':'x86_64','SystemImage.TagId':p['tag']}
    if api=='36':image['AndroidVersion.ExtensionLevel']='17'
    return {'system-image':image,'emulator':{'Pkg.Revision':'37.2.12','Pkg.BuildId':'16428233'},'build-tools':{'Pkg.Revision':'35.0.0'}}
def validate_receipt(result,api):
    require(api in PROFILES,'Unknown fixed API')
    p=PROFILES[api];exp=expected(api)
    require(result['api']==api and result['status']=='matched-recorded-revisions-and-launch-configuration','Environment identity unverified')
    require(result['expected']==exp and result['original_binary_hashes_available'] is False and result['full_environment_equivalence'] is False,'Environment pins/scope differ')
    require(set(result['files'])=={'system-image','emulator','build-tools','avd-config'},'Environment file inventory incomplete')
    suffix={'system-image':'system-images/android-'+api+'/'+p['tag']+'/x86_64/source.properties','emulator':'emulator/source.properties','build-tools':'build-tools/35.0.0/source.properties','avd-config':'test.avd/config.ini'}
    for kind,entry in result['files'].items():
        raw=entry['text'].encode();values=properties(raw)
        require(entry['sha256']==hashlib.sha256(raw).hexdigest() and entry['properties']==values,'Environment raw identity differs')
        require(Path(entry['path']).is_absolute() and entry['path'].endswith('/'+suffix[kind]),'Wrong identity file path')
        for k,v in exp.get(kind,{}).items():require(values.get(k)==v,'Wrong '+kind+' '+k)
    config=result['files']['avd-config']['properties']
    require(config.get('hw.cpu.ncore')=='2' and config.get('hw.device.name')=='pixel_2','Wrong core/profile settings')
    require(config.get('image.sysdir.1','').rstrip('/').endswith('system-images/android-'+api+'/'+p['tag']+'/x86_64'),'AVD image differs')
    require(result['processes'] and isinstance(result['fingerprint'],str) and result['fingerprint'].strip(),'Missing emulator argv/fingerprint')
    seen=set()
    for process in result['processes']:
        args=process['argv'];pid=process['pid']
        require(type(pid) is int and pid>0 and pid not in seen,'Invalid/repeated emulator PID');seen.add(pid)
        require(isinstance(args,list) and args and all(isinstance(x,str) for x in args),'Invalid emulator argv')
        require(Path(args[0]).name in ('emulator','qemu-system-x86_64','qemu-system-x86_64-headless'),'Not emulator argv')
        for key,value in [('-avd','test'),('-gpu',p['gpu']),('-memory','3072'),('-port','5554')]:
            require(args.count(key)==1 and args.index(key)+1<len(args) and args[args.index(key)+1]==value,'Emulator '+key+' differs')
        require(args.count('-no-snapshot')==1,'Snapshot reuse forbidden')
    return True

def verify(evidence,api):
    require(api in PROFILES,'Unknown fixed API')
    root=Path(evidence);sdk=Path(os.environ['ANDROID_HOME']);avd=Path(os.environ.get('ANDROID_AVD_HOME',str(Path.home()/'.android/avd')))
    p=PROFILES[api];result=dict(api=api,status='rejected',expected=expected(api),files={},processes=[],original_binary_hashes_available=False,full_environment_equivalence=False)
    try:
        paths={'system-image':sdk/('system-images/android-'+api+'/'+p['tag']+'/x86_64/source.properties'),'emulator':sdk/'emulator/source.properties','build-tools':sdk/'build-tools/35.0.0/source.properties','avd-config':avd/'test.avd/config.ini'}
        for kind,path in paths.items():
            require(path.is_file() and not path.is_symlink(),'Missing identity file '+str(path))
            raw=path.read_bytes();result['files'][kind]=dict(path=str(path),text=raw.decode(),properties=properties(raw),sha256=hashlib.sha256(raw).hexdigest())
        for path in Path('/proc').glob('[0-9]*/cmdline'):
            try:args=[os.fsdecode(a) for a in path.read_bytes().rstrip(b'\0').split(b'\0')]
            except OSError:continue
            if not args or Path(args[0]).name not in ('emulator','qemu-system-x86_64','qemu-system-x86_64-headless'):continue
            if '-avd' in args and args.index('-avd')+1<len(args) and args[args.index('-avd')+1]=='test':result['processes'].append(dict(pid=int(path.parent.name),argv=args))
        result['fingerprint']=subprocess.check_output(['adb','-s','emulator-5554','shell','getprop','ro.build.fingerprint'],text=True,timeout=10).strip()
        result['status']='matched-recorded-revisions-and-launch-configuration'
        validate_receipt(result,api)
    except (OSError,ValueError,KeyError,TypeError,subprocess.SubprocessError) as error:
        result.update(status='rejected',error=str(error))
    with (root/'environment-identity.json').open('x') as out:json.dump(result,out,indent=2);out.write('\n')
    return 0 if result['status'].startswith('matched-') else 3
if __name__=='__main__':sys.exit(verify(sys.argv[1],sys.argv[2]))
