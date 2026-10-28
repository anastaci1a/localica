"""Hosted read-only replay of fixed original evidence. Never a native or prototype delivery verdict."""
import argparse,hashlib,json,pathlib,re,shutil,subprocess,sys,tempfile

def require(value,message):
    if not value:raise ValueError(message)
def read(path):
    path=pathlib.Path(path);require(path.is_file() and not any(p.is_symlink() for p in [path,*path.parents]),'Missing/symlink input: '+str(path));return path.read_bytes()
def data(path):
    def unique(pairs):
        obj={}
        for key,value in pairs:require(key not in obj,'Duplicate JSON key');obj[key]=value
        return obj
    return json.loads(read(path),object_pairs_hook=unique)
def sha(raw):return hashlib.sha256(raw).hexdigest()
def git(root,*args):return subprocess.check_output(['git','-C',str(root),*args],text=True).strip()
def verify_sources(baseline,candidate,pins):
    for name,root in [('baseline',baseline),('candidate',candidate)]:
        require(git(root,'rev-parse','HEAD')==pins[name]['sha'] and git(root,'rev-parse','HEAD^{tree}')==pins[name]['tree'],'Wrong '+name+' commit/tree')
        require(not git(root,'status','--porcelain','--untracked-files=normal'),'Dirty '+name+' checkout')
        for path,digest in pins[name]['files'].items():require(sha(read(root/path))==digest,'Wrong exact '+name+' file '+path)
    require(git(candidate,'show','-s','--format=%P','HEAD')==pins['baseline']['sha'],'Wrong candidate parent')
    names=git(candidate,'diff-tree','--no-commit-id','--name-status','-r',pins['baseline']['sha'],pins['candidate']['sha']).splitlines()
    require(names==['M\t.github/run-prototype-tests.sh','M\t.github/tests/test_run_prototype_tests.py'],'Candidate changed product/native test/workflow or other files')

def verify_original(root,receipt,pin,api,source):
    require(receipt['id']==pin['id'] and receipt['name']==pin['name'] and receipt['digest']==pin['digest'] and receipt['expired'] is False,'Wrong/expired original artifact identity')
    require(receipt['workflow_run']['id']==40620520114 and receipt['workflow_run']['head_sha']==source,'Wrong original run/source')
    for name,digest in pin['files'].items():
        path=root/name;require(path.resolve().is_relative_to(root.resolve()),'Original escaped root');require(sha(read(path))==digest,'Original changed: '+name)
    context=read(root/'prototype-evidence/context.txt').decode();require('candidate_sha='+source+'\n' in context and 'api='+api+'\n' in context and 'actual_api='+api+'\n' in context,'Wrong original execution context')
    observed=data(root/'prototype-evidence/live/observed/manifest.json');require(observed['sha']==source and str(observed['api'])==api,'Wrong observed original identity')

def embedded(root):
    raw=read(root/'.github/run-prototype-tests.sh').decode();blocks=re.findall(r"<<'PYXML'\n(.*?)\nPYXML",raw,re.S);require(len(blocks)==1,'Missing/ambiguous reconciler');return blocks[0]
def replay(code,original,pins):
    with tempfile.TemporaryDirectory() as tmp:
        root=pathlib.Path(tmp)
        # Copy only pre-verified source evidence. Never mutate downloaded originals.
        for name in pins['files']:
            target=root/name;target.parent.mkdir(parents=True,exist_ok=True);target.write_bytes(read(original/name))
        result_path=root/'prototype-evidence/reconciled-tests.json'
        if result_path.exists():result_path.unlink() # A disposable copy of a previous receipt, not an original.
        selectors=read(root/'prototype-evidence/selectors.txt').decode().strip()
        required='io.github.anastaci1a.localica.SearchImeMotionTest,io.github.anastaci1a.localica.ListSearchTest,io.github.anastaci1a.localica.FrostedSurfaceTest'
        result=subprocess.run([sys.executable,'-c',code,selectors,required],cwd=root,capture_output=True,text=True,timeout=120)
        return result,data(result_path) if result_path.exists() else None

def run(args):
    root=pathlib.Path(args.evidence).resolve();root.mkdir(parents=True,exist_ok=True)
    baseline,candidate=pathlib.Path(args.baseline).resolve(),pathlib.Path(args.candidate).resolve()
    pins=data(args.pins);verify_sources(baseline,candidate,pins)
    summary=dict(kind='hosted-offline-accounting-validation',source=pins['baseline']['sha'],corrected_runner_commit=pins['candidate']['sha'],original_run=40620520114,original_attempt=1,native_execution=False,original_hosted_status_changed=False,prototype_delivery_ready=False,release_certified=False,apis={})
    target_ids={'io.github.anastaci1a.localica.SelectionLensTest#translatingASelectionReusesItsDistanceField','io.github.anastaci1a.localica.SelectionLensTest#translatingAnIrregularSelectionKeepsItsMapButChangingItsHoleRebuildsIt'}
    for api in ('26','36'):
        original=pathlib.Path(getattr(args,'api'+api)).resolve();pin=pins['artifacts'][api]
        verify_original(original,data(root/('api'+api+'-artifact.json')),pin,api,pins['baseline']['sha'])
        old,before=replay(embedded(baseline),original,pin);new,after=replay(embedded(candidate),original,pin)
        for label,result in [('baseline',old),('candidate',new)]:
            for stream in ('stdout','stderr'):
                with (root/(label+'-api'+api+'.'+stream+'.txt')).open('x') as out:out.write(getattr(result,stream))
        require(new.returncode==0 and after is not None,'Corrected original replay rejected on API'+api+': '+new.stderr)
        observed=data(original/'prototype-evidence/live/observed/manifest.json')
        expected=data(original/'prototype-evidence/expected-methods.json')
        require(set(after['methods'])==set(expected['runtime_methods']) and len(after['methods'])==343,'Changed exact method coverage')
        counts={k:list(after['methods'].values()).count(k) for k in ('passed','skipped','failed','errors')}
        require(counts=={'passed':333 if api=='26' else 336,'skipped':10 if api=='26' else 7,'failed':0,'errors':0},'Unexpected original outcomes')
        require(all(after['methods'][identity]==('skipped' if api=='26' else 'passed') for identity in target_ids),'Changed target outcomes')
        if api=='26':
            require(old.returncode!=0 and before is None and 'Unqualified skipped identity/API: io.github.anastaci1a.localica.SelectionLensTest#translatingASelectionReusesItsDistanceField API26' in old.stderr,'Original API26 rejection not reproduced')
            require({q['id'] for q in after['qualified_skips'] if q['id'] in target_ids}==target_ids,'Missing exact SDK qualifications')
        else:
            require(old.returncode==0 and before is not None,'Baseline API36 replay failed')
            require(before==after,'API36 accounting changed')
        with (root/('derived-api'+api+'-reconciled-tests.json')).open('x') as out:json.dump(after,out,indent=2);out.write('\n')
        summary['apis'][api]=dict(counts=counts,unique_methods=len(after['methods']),qualified_skips=len(after['qualified_skips']),baseline_exit=old.returncode,candidate_exit=new.returncode,artifact_id=pin['id'],original_inputs_sha256=pin['files'])
        verify_original(original,data(root/('api'+api+'-artifact.json')),pin,api,pins['baseline']['sha'])
    with (root/'validation-summary.json').open('x') as out:json.dump(summary,out,indent=2);out.write('\n')
    return summary

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    for name in ('baseline','candidate','pins','api26','api36','evidence'):parser.add_argument('--'+name,required=True)
    args=parser.parse_args()
    try:print(json.dumps(run(args),indent=2));return 0
    except (OSError,ValueError,KeyError,TypeError,subprocess.SubprocessError) as error:
        print('Replay rejected: '+type(error).__name__+': '+str(error),file=sys.stderr);return 3
if __name__=='__main__':sys.exit(main())
