"""Retain minimal, unmodified Java runtime evidence; require major 17, not patch equivalence."""
import hashlib,json,os,re,shutil,subprocess,sys
from pathlib import Path
PHASES=('build','native')
def require(value,message):
    if not value:raise ValueError(message)
def sha(raw):return hashlib.sha256(raw).hexdigest()
def read(path):
    require(path.is_file() and not any(p.is_symlink() for p in [path,*path.parents]),'Missing/symlink Java evidence: '+str(path))
    return path.read_bytes()
def version(stdout,stderr):
    values=[]
    for raw in (stdout,stderr):
        lines=raw.decode('utf-8',errors='strict').splitlines()
        for i,line in enumerate(lines):
            match=re.fullmatch(r'(?:openjdk|java) version "([^"\r\n]+)"(?: .*)?',line)
            if match:
                require(i==0,'Version must be the first output line');values.append(match.group(1))
    require(len(values)==1,'Missing/ambiguous java -version identity')
    actual=values[0]
    require(re.fullmatch(r'17(?:[.][0-9]+)*(?:[-+][0-9A-Za-z._-]+)?',actual) is not None,'Actual Java runtime must be major 17')
    return actual

def validate_receipt(root,phase):
    require(phase in PHASES,'Unknown Java evidence phase');root=Path(root)
    def unique(pairs):
        out={}
        for key,value in pairs:require(key not in out,'Duplicate Java receipt key');out[key]=value
        return out
    value=json.loads(read(root/('java-'+phase+'.json')),object_pairs_hook=unique)
    require(type(value['schema_version']) is int and value['schema_version']==1 and value['phase']==phase and value['status']=='major-17-verified','Unverified Java runtime')
    require(value['required_major']==17 and type(value['required_major']) is int and value['actual_major']==17 and type(value['actual_major']) is int,'Wrong Java major contract')
    require(value['command_exit']==0 and type(value['command_exit']) is int,'Java command failed')
    executable=value['executable'];require(isinstance(executable,str) and Path(executable).is_absolute() and '..' not in Path(executable).parts,'Invalid resolved Java executable')
    require(re.fullmatch('[0-9a-f]{64}',value['executable_sha256']) is not None,'Missing Java binary identity')
    home=value['java_home']
    require(isinstance(home,str) and Path(home).is_absolute() and '..' not in Path(home).parts,'Invalid canonical JAVA_HOME')
    require(value['java_home_launcher']==str(Path(home)/'bin/java') and value['resolved_java_home_launcher']==executable,'JAVA_HOME/PATH Java identity differs')
    require(value['command']==[executable,'-version'],'Wrong Java capture command')
    require(value['exact_patch_required'] is False and value['cross_api_runtime_equivalence'] is False,'Unqualified Java equivalence claim')
    out=read(root/('java-'+phase+'.stdout.txt'));err=read(root/('java-'+phase+'.stderr.txt'))
    require(value['stdout_file']=='java-'+phase+'.stdout.txt' and value['stderr_file']=='java-'+phase+'.stderr.txt','Wrong Java original filenames')
    require(value['stdout_sha256']==sha(out) and value['stderr_sha256']==sha(err),'Java raw capture hash mismatch')
    require(value['actual_version']==version(out,err),'Java parsed version differs from originals')
    return value

def capture(root,phase):
    require(phase in PHASES,'Unknown Java evidence phase');root=Path(root)
    require(root.is_dir() and not any(p.is_symlink() for p in [root,*root.parents]),'Missing/symlink Java evidence root')
    paths={kind:root/('java-'+phase+'.'+suffix) for kind,suffix in [('receipt','json'),('stdout','stdout.txt'),('stderr','stderr.txt')]}
    require(not any(p.exists() or p.is_symlink() for p in paths.values()),'Refusing existing Java evidence')
    stdout=stderr=b''
    result=dict(schema_version=1,phase=phase,status='rejected',required_major=17,actual_major=None,actual_version=None,executable=None,executable_sha256=None,java_home=None,java_home_launcher=None,resolved_java_home_launcher=None,command=None,command_exit=None,
        stdout_file=paths['stdout'].name,stderr_file=paths['stderr'].name,exact_patch_required=False,cross_api_runtime_equivalence=False)
    try:
        found=shutil.which('java');require(found is not None,'java executable not found')
        executable=Path(found).resolve(strict=True);require(executable.is_file(),'Java executable is not a regular file')
        home_value=os.environ.get('JAVA_HOME');require(isinstance(home_value,str) and bool(home_value.strip()),'JAVA_HOME is required')
        home=Path(home_value).resolve(strict=True);require(home.is_dir(),'JAVA_HOME is not a directory')
        configured=home/'bin/java';resolved=configured.resolve(strict=True)
        require(resolved==executable,'JAVA_HOME/bin/java differs from resolved PATH java')
        result.update(java_home=str(home),java_home_launcher=str(configured),resolved_java_home_launcher=str(resolved))
        result.update(executable=str(executable),executable_sha256=sha(executable.read_bytes()),command=[str(executable),'-version'])
        completed=subprocess.run(result['command'],stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=10,check=False)
        stdout,stderr=completed.stdout,completed.stderr;result['command_exit']=completed.returncode
        require(completed.returncode==0,'java -version command failed')
        require(sha(executable.read_bytes())==result['executable_sha256'],'Java binary changed during capture')
        result.update(actual_version=version(stdout,stderr),actual_major=17,status='major-17-verified')
    except subprocess.TimeoutExpired as error:
        stdout=error.stdout or b'';stderr=error.stderr or b'';result['error']='java -version timed out'
    except (OSError,ValueError,UnicodeError,subprocess.SubprocessError) as error:
        result['error']=type(error).__name__+': '+str(error)
    result.update(stdout_sha256=sha(stdout),stderr_sha256=sha(stderr))
    for kind,raw in [('stdout',stdout),('stderr',stderr)]:
        with paths[kind].open('xb') as output:output.write(raw)
    with paths['receipt'].open('x') as output:json.dump(result,output,indent=2);output.write('\n')
    if result['status']=='major-17-verified':validate_receipt(root,phase);return 0
    return 3
if __name__=='__main__':
    try:
        require(len(sys.argv)==3,'usage: tageditor_java.py EVIDENCE build|native')
        sys.exit(capture(sys.argv[1],sys.argv[2]))
    except (OSError,ValueError) as error:
        print('Java evidence rejected: '+str(error),file=sys.stderr);sys.exit(3)
