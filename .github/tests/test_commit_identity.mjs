import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import {fileURLToPath} from 'node:url';
import {execFileSync,spawnSync} from 'node:child_process';
const fixture=fs.mkdtempSync(path.join(os.tmpdir(),'identity-guard-'));
const guard=fileURLToPath(new URL('../check-commit-identity.mjs',import.meta.url));
try {
const git=(...args)=>execFileSync('git',['-C',fixture,...args],{encoding:'utf8'}).trim();
git('init','-q');git('config','user.name','anastaci1a');git('config','user.email','48846277+anastaci1a@users.noreply.github.com');
const tree=git('mktree');
function commit(message,author='anastaci1a <48846277+anastaci1a@users.noreply.github.com>',committer=author,parents=[]){
 const [name,email]=/^(.*) <([^>]+)>$/.exec(author).slice(1),[cn,ce]=/^(.*) <([^>]+)>$/.exec(committer).slice(1);
 return execFileSync('git',['-C',fixture,'commit-tree',tree,...parents.flatMap(p=>['-p',p])],{encoding:'utf8',input:message+'\n',env:{...process.env,GIT_AUTHOR_NAME:name,GIT_AUTHOR_EMAIL:email,GIT_COMMITTER_NAME:cn,GIT_COMMITTER_EMAIL:ce,GIT_AUTHOR_DATE:'2026-11-10T06:00:00Z',GIT_COMMITTER_DATE:'2026-11-10T06:00:00Z'}}).trim();
}
const base=commit('Base'),operator='anastaci1a <48846277+anastaci1a@users.noreply.github.com>',results=[];
function test(name,head,shouldPass,event){
 const env={...process.env};let args=[guard,'--range',base,head];
 if(event){const file=path.join(fixture,'event.json');fs.writeFileSync(file,JSON.stringify(event));Object.assign(env,{GITHUB_EVENT_NAME:event.eventName||(event.deleted?'push':'pull_request'),GITHUB_EVENT_PATH:file,GITHUB_SHA:head});args=[guard];}
 const r=spawnSync('node',args,{cwd:fixture,env,encoding:'utf8'}),passed=(r.status===0)===shouldPass;results.push({name,passed,exit:r.status,output:(r.stdout+r.stderr).trim()});if(!passed)throw Error(name+' '+r.stderr);
}
const good=commit('Valid',operator,operator,[base]);
test('approved identities',good,true);
test('bad author',commit('Invalid author','unrelated <invalid@example.invalid>',operator,[base]),false);
test('bad committer',commit('Invalid committer',operator,'unrelated <invalid@example.invalid>',[base]),false);
test('bad name with approved email',commit('Invalid name','Codex <48846277+anastaci1a@users.noreply.github.com>',operator,[base]),false);
const forbiddenTrailers=[
 'Co-Authored-By: Claude <noreply@anthropic.com>',
 'co-authored-by: Codex <codex@openai.com>',
 'Co-authored-by: '+operator,
 'Claude-Session: example',
 'Codex-Session: example',
 '🤖 Generated with example',
 'Generated with Codex',
 'Generated with Claude Code',
 '\tCo-AUTHORED-BY \t: '+operator,
 '\tCoDeX-SeSsIoN \t: example',
 '\tClAuDe-SeSsIoN \t: example',
 '\tGeNeRaTeD\t WiTh\tCoDeX',
 '\t🤖\tgenerated with example',
 '🤖\uFE0F Generated with Claude Code',
];
for(const trailer of forbiddenTrailers){
 test('commit rejects '+trailer,commit('Message\n\n'+trailer,operator,operator,[base]),false);
 test('PR body rejects '+trailer,good,false,{pull_request:{base:{sha:base},head:{sha:good},body:'Review notes\n\n'+trailer}});
}
const prose='The implementation was reviewed using Codex and Claude Code.\nThis paragraph mentions Generated with Codex as ordinary prose.';
test('ordinary tool prose in commit',commit(prose,operator,operator,[base]),true);
test('ordinary tool prose in PR body',good,true,{pull_request:{base:{sha:base},head:{sha:good},body:prose}});
const invalid=commit('Invalid middle','unrelated <invalid@example.invalid>',operator,[base]);test('invalid ancestor before good tip',commit('Good tip',operator,operator,[invalid]),false);
const merge=commit('Merge',operator,operator,[good,invalid]);test('invalid merged side history',merge,false);
test('PR body trailer',good,false,{pull_request:{base:{sha:base},head:{sha:good},body:'Co-authored-by: Claude <noreply@anthropic.com>'}});
test('valid PR body',good,true,{pull_request:{base:{sha:base},head:{sha:good},body:'Review the identity guard.'}});
test('branch deletion event',good,true,{deleted:true,before:good,after:'0'.repeat(40)});
// Main contains an operator-only trailer verified before the prospective policy.
const historical=commit('Verified historical commit\n\nCo-authored-by: '+operator,operator,operator,[base]);
git('update-ref','refs/remotes/origin/main',historical);
const feature=commit('Clean new commit',operator,operator,[historical]);
test('manual dispatch grandfathers verified main',feature,true,{eventName:'workflow_dispatch'});
test('manual dispatch on main checks zero new commits',historical,true,{eventName:'workflow_dispatch'});
test('new branch push grandfathers verified main',feature,true,{eventName:'push',before:'0'.repeat(40),after:feature});
for(const trailer of forbiddenTrailers){
 const head=commit('New attribution\n\n'+trailer,operator,operator,[historical]);
 test('manual dispatch rejects new '+trailer,head,false,{eventName:'workflow_dispatch'});
 test('new branch push rejects new '+trailer,head,false,{eventName:'push',before:'0'.repeat(40),after:head});
}
git('update-ref','-d','refs/remotes/origin/main',historical);
test('manual dispatch without verified main fails closed',feature,false,{eventName:'workflow_dispatch'});
git('update-ref','refs/remotes/origin/main',historical);
const input=`refs/heads/test ${invalid} refs/heads/test ${base}\n`,r=spawnSync('node',[guard,'--pre-push','origin'],{cwd:fixture,input,encoding:'utf8'});results.push({name:'pre-push stdin',passed:r.status===1,exit:r.status,output:r.stderr.trim()});
console.log(`${results.filter(r=>r.passed).length}/${results.length} passed`);
if(results.some(r=>!r.passed))process.exitCode=1;

} finally {
 fs.rmSync(fixture,{recursive:true});
}
