import fs from 'node:fs';
/** Hard rule 11: popup implementation belongs to these owners, never to a screen. */
export const popupRules = ['ScreenPopupHome', 'ScreenPopupMotion', 'ScreenPopupTrigger', 'ScreenPopupDrawPath', 'DuplicatePopupRole'];
const ownership = fs.readFileSync(new URL('../../lint/src/main/resources/popup-owners.txt',import.meta.url),'utf8').trim().split('\n').map(row=>row.split('='));
export const popupOwners = [...new Set(ownership.map(([,path])=>path))];
const popupHosts = new Map(fs.readFileSync(new URL('../../lint/src/main/resources/popup-hosts.txt',import.meta.url),'utf8').trim().split('\n').map(row=>{const [path,counts]=row.split('=');return [path,counts.split(',').map(Number)];}));
const popupRoles = new Set(fs.readFileSync(new URL('../../lint/src/main/resources/popup-roles.txt',import.meta.url),'utf8').trim().split('\n'));
export function ownsPopups(file, rule) { return ownership.some(([r,path])=>path===file && (!rule || r===rule)); }
// Tokenize Kotlin so comments, ordinary/raw strings and whitespace cannot hide or fabricate a call.
// Offsets are retained for exact source receipts. Import aliases and qualified calls resolve to the same spelling.
export function kotlinTokens(source) {
    const tokens=[]; let i=0;
    function scan(untilBrace = false) {
      let braces=0;
      while(i<source.length) {
        if(untilBrace && source[i]==='}' && braces===0)return;
        if (/\s/.test(source[i])) {i++;continue;}
        if(source.startsWith('//',i)) {i=source.indexOf('\n',i);if(i<0)break;continue;}
        if(source.startsWith('/*',i)) {let depth=1;i+=2;while(i<source.length && depth){if(source.startsWith('/*',i)){depth++;i+=2;}else if(source.startsWith('*/',i)){depth--;i+=2;}else i++;}continue;}
        const start=i;
        if(source[i]==='"') {
            const raw=source.startsWith('"""',i), delimiter=raw?'"""':'"';i+=delimiter.length;
            tokens.push({text:'<string>',start});
            while(i<source.length && !source.startsWith(delimiter,i)) {
                if(!raw && source[i]==='\\'){i+=2;continue;}
                if(source[i]==='$' && source[i+1]==='{') {
                    tokens.push({text:'{',start:i+1});i+=2;scan(true);
                    if(source[i]==='}')tokens.push({text:'}',start:i++});
                } else if(source[i]==='$' && /[A-Za-z_]/.test(source[i+1]||'')) {
                    i++;const begin=i;while(i<source.length && /[\w$]/.test(source[i]))i++;
                    tokens.push({text:source.slice(begin,i),start:begin});
                } else i++;
            }
            i+=delimiter.length;continue;
        }
        if(source[i]==="'"){i++;while(i<source.length){if(source[i++]==='\\')i++;else if(source[i-1]==="'")break;}tokens.push({text:'<char>',start});continue;}
        if(source[i]==='`'){i++;const begin=i;while(i<source.length && source[i]!=='`')i++;tokens.push({text:source.slice(begin,i),start});i++;continue;}
        const word=source.slice(i).match(/^[A-Za-z_$][\w$]*/)?.[0];
        if(word){tokens.push({text:word,start});i+=word.length;}else {
            if(source[i]==='{')braces++;if(source[i]==='}')braces--;
            tokens.push({text:source[i++],start});
        }
      }
    }
    scan();
    return tokens;
}
function declaredFunctions(file, source) {
    const tokens=kotlinTokens(source), declarations=[];
    const packageAt=tokens.findIndex(t=>t.text==='package');let pkg='';
    for(let i=packageAt+1;packageAt>=0 && i<tokens.length;i++) {
        if(i>packageAt+1 && source.slice(tokens[i-1].start,tokens[i].start).includes('\n'))break;
        if(tokens[i].text===';')break;pkg+=tokens[i].text;
    }
    for(let i=0;i<tokens.length;i++)if(tokens[i].text==='fun') {
        let start=i+1;while(start<tokens.length && !['(','{','='].includes(tokens[start].text))start++;
        if(tokens[start]?.text!=='(')continue;
        const name=tokens[start-1].text, booleans=new Set(), parameters=[];let j=start+1,depth=1;
        while(j<tokens.length && depth) {
            if(tokens[j].text==='(')depth++;
            if(tokens[j].text===')')depth--;
            if(depth===1 && tokens[j].text===':') {
                parameters.push(tokens[j-1].text);
                if(tokens[j+1]?.text==='Boolean')booleans.add(tokens[j-1].text);
            }
            j++;
        }
        declarations.push({file,name,qualified:pkg?pkg+'.'+name:name,booleans,parameters});
    }
    return declarations;
}
export function checkPopupSource(file, source, declarations = []) {
    if(!file.startsWith('app/src/main/') || !file.endsWith('.kt') )return [];
    const tokens=kotlinTokens(source), aliases=new Map(), imports=new Map(), anchors=new Set(), importTokens=new Set();
    for(let i=0;i<tokens.length;i++)if(tokens[i].text==='import') {
        let j=i+1,last='', qualified='';while(j<tokens.length && !['as',';','import','package'].includes(tokens[j].text)) {
            if(j>i+1 && source.slice(tokens[j-1].start,tokens[j].start).includes('\n'))break;
            qualified+=tokens[j].text;if(tokens[j].text!=='.')last=tokens[j].text;j++;
        }
        const local=tokens[j]?.text==='as'?tokens[j+1]?.text:last;
        for(let k=i;k<j+(tokens[j]?.text==='as'?2:0);k++)importTokens.add(k);
        imports.set(local,qualified);if(local!==last)aliases.set(local,last);
    }
    // Follow type aliases and local subclass identities before classifying calls/references.
    const nativeTypes=new Set(['ComponentDialog','DialogFragment','Dialog','PopupWindow','PopupMenu','ListPopupWindow','AlertDialog']);
    const protectedTypes=new Set(['io.github.anastaci1a.localica.ui.GlassAnchor','io.github.anastaci1a.localica.ui.GlassPopupHome',
        'io.github.anastaci1a.localica.ui.components.HomeRef','io.github.anastaci1a.localica.ui.components.PopupState','io.github.anastaci1a.localica.ui.components.PopupScope']);
    const typeIdentity=new Map(imports);
    const packageMatch=source.match(/^\s*package\s+([\w.]+)/m)?.[1] || file.slice(file.indexOf('/java/')+6,file.lastIndexOf('/')).replaceAll('/','.');
    for(const identity of protectedTypes)if(packageMatch===identity.slice(0,identity.lastIndexOf('.')))typeIdentity.set(identity.split('.').at(-1),identity);
    for(let pass=0;pass<tokens.length;pass++) {
        let changed=false;
        for(let i=0;i<tokens.length;i++) {
            const isAlias=tokens[i].text==='typealias' && tokens[i+2]?.text==='=';
            const isClass=tokens[i].text==='class';
            if(!isAlias && !isClass)continue;
            let begin=i+3;
            if(isClass) {
                begin=i+2;let depth=0;
                while(begin<tokens.length){const t=tokens[begin].text;if(t==='(')depth++;if(t===')')depth--;if(depth===0 && t===':'){begin++;break;}if(depth===0 && ['{','=',';'].includes(t))break;begin++;}
                if(tokens[begin-1]?.text!==':')continue;
            }
            let end=begin;while(tokens[end+1]?.text==='.')end+=2;
            const target=tokens.slice(begin,end+1).map(t=>t.text).join('');
            const identity=typeIdentity.get(tokens[begin]?.text)||target;
            if(typeIdentity.get(tokens[i+1].text)!==identity){typeIdentity.set(tokens[i+1].text,identity);changed=true;}
        }
        if(!changed)break;
    }
    const name=i=>aliases.get(tokens[i]?.text)||tokens[i]?.text;
    function identityAt(i) {
        let begin=i;while(begin>=2 && tokens[begin-1]?.text==='.')begin-=2;
        const first=typeIdentity.get(tokens[begin]?.text)||tokens[begin]?.text;
        return first+tokens.slice(begin+1,i+1).map(t=>t.text).join('');
    }
    const nativeBuilder=i=>['android.app.AlertDialog.Builder','androidx.appcompat.app.AlertDialog.Builder'].includes(identityAt(i));
    const localDeclarations=declaredFunctions(file,source);
    const localFunctions=new Set(localDeclarations.map(d=>d.name));
    const booleanValues=new Set(tokens.flatMap((t,i)=>tokens[i+1]?.text===':' && tokens[i+2]?.text==='Boolean'?[t.text]:[]));
    const stockFiles={rememberPresence:'components/Presence.kt',rememberFaceExpansion:'components/MorphFaces.kt',PopupActionPill:'GlassMorph.kt',ActionIcon:'InterfaceComponents.kt'};
    const intentFormals={rememberPresence:new Set(['shown']),rememberFaceExpansion:new Set(['expanded']),PopupActionPill:new Set(['menuVisible']),ActionIcon:new Set(['enabled','glass','quietInputEnabled'])};
    const stockOwners={rememberPresence:'io.github.anastaci1a.localica.ui.components.rememberPresence',rememberFaceExpansion:'io.github.anastaci1a.localica.ui.components.rememberFaceExpansion',PopupActionPill:'io.github.anastaci1a.localica.ui.PopupActionPill',ActionIcon:'io.github.anastaci1a.localica.ui.ActionIcon'};
    function stockIntent(call, formal) {
        const resolved=declarations.filter(d=>d.qualified===stockOwners[call]);
        return !localFunctions.has(call) && (!resolved.length || resolved.length===1 && resolved[0].file==='app/src/main/java/io/github/anastaci1a/localica/ui/'+stockFiles[call]) && intentFormals[call]?.has(formal) &&
            (![...imports].some(([local,q])=>name(tokens.findIndex(t=>t.text===local))===call && q!==stockOwners[call]));
    }
    for(let i=0;i<tokens.length;i++) {
        if(tokens[i+1]?.text===':' && name(i+2)==='GlassAnchor') anchors.add(tokens[i].text);
        if(['val','var'].includes(tokens[i].text) && tokens[i+2]?.text==='=' && ['rememberGlassAnchor','GlassAnchor'].includes(name(i+3))) anchors.add(tokens[i+1].text);
    }
    const contactTypes=new Set(['GlassContact','GlassExpansion','GlassBodyState','GlassMomentum']);
    const handles=new Map([...anchors].map(a=>[a,'ScreenPopupTrigger']));
    for(let pass=0;pass<3;pass++)for(let i=0;i<tokens.length;i++) {
        if(tokens[i+1]?.text===':' && contactTypes.has(name(i+2)))handles.set(tokens[i].text,'GlassContactMutation');
        if(tokens[i+1]?.text===':' && ['GlassPopupHome','GlassPopupFlight'].includes(name(i+2)))handles.set(tokens[i].text,'ScreenPopupHome');
        if(['val','var'].includes(tokens[i].text) && tokens[i+2]?.text==='=') {
            const start=i+3;let end=start,depth=0;
            while(end<tokens.length) {
                const t=tokens[end].text;
                if(depth===0 && (t===';' || end>start && source.slice(tokens[end-1].start,tokens[end].start).includes('\n')))break;
                if(['(','{','['].includes(t))depth++;
                if([')','}',']'].includes(t)){if(depth===0)break;depth--;}
                end++;
            }
            const expression=tokens.slice(start,end).map(t=>t.text);
            // Only value-producing aliases carry ownership; mentioning a handle
            // in a callback must not classify that callback's unrelated result.
            const returned=expression[0]==='remember'?expression.slice(expression.indexOf('{')+1):expression;
            const root=returned[0]==='if'?returned.slice(returned.indexOf(')')+1).map(t=>handles.get(t)).find(Boolean):handles.get(returned[0]);
            if(root && expression.some(t=>['popupHome','flights','children'].includes(t))) handles.set(tokens[i+1].text,'ScreenPopupHome');
            else if(root)handles.set(tokens[i+1].text,expression.some(t=>['contact','state','momentum','expansion','output'].includes(t))?'GlassContactMutation':root);
        }
    }
    const lambdaHandles=[];
    function receiverRule(i) {
        let begin=i;
        while(begin>=2 && tokens[begin-1]?.text==='.') {
            begin-=2;
            while(begin>0 && ['?','!'].includes(tokens[begin]?.text))begin--;
        }
        const rule=handles.get(tokens[begin]?.text) || lambdaHandles.findLast(x=>x.start<i && i<x.end && x.name===tokens[begin]?.text)?.rule;
        if(!rule)return;
        const chain=tokens.slice(begin,i+1);
        return chain.some(t=>t.text==='popupHome')?'ScreenPopupHome':chain.some(t=>t.text==='contact')?'GlassContactMutation':rule;
    }
    // Scope-function receiver aliases retain the ownership of their source.
    for(let i=0;i<tokens.length;i++)if(['also','let','apply','run'].includes(name(i)) && tokens[i-1]?.text==='.' && tokens[i+1]?.text==='{') {
        let end=i+2,depth=1;while(end<tokens.length && depth){if(tokens[end].text==='{')depth++;if(tokens[end].text==='}')depth--;end++;}
        let rule=receiverRule(tokens[i-2]?.text==='?'?i-3:i-2);
        if(tokens[i-2]?.text===')') {
            let begin=i-3,nesting=1;for(;begin>=0 && nesting;begin--){if(tokens[begin].text===')')nesting++;if(tokens[begin].text==='(')nesting--;}
            if(['rememberGlassAnchor','GlassAnchor'].includes(name(begin)))rule='ScreenPopupTrigger';
        }
        if(rule)lambdaHandles.push({start:i+1,end,name:tokens[i+3]?.text==='-' && tokens[i+4]?.text==='>'?tokens[i+2].text:['apply','run'].includes(name(i))?null:'it',rule});
    }
    for(let i=0;i<tokens.length;i++)if(name(i)==='with' && tokens[i+1]?.text==='(') {
        let close=i+2,depth=1;while(close<tokens.length && depth){if(tokens[close].text==='(')depth++;if(tokens[close].text===')')depth--;close++;}
        if(tokens[close]?.text!=='{')continue;
        const rule=receiverRule(close-2);
        let end=close+1;depth=1;while(end<tokens.length && depth){if(tokens[end].text==='{')depth++;if(tokens[end].text==='}')depth--;end++;}
        if(rule)lambdaHandles.push({start:close,end,name:null,rule});
    }
    const windowHandles=new Set(), nativeHandles=new Set();
    for(let pass=0;pass<3;pass++)for(let i=0;i<tokens.length;i++) {
        if(tokens[i+1]?.text===':') {
            let end=i+2;while(tokens[end+1]?.text==='.')end+=2;
            const type=typeIdentity.get(tokens[i+2]?.text)||tokens.slice(i+2,end+1).map(t=>t.text).join('');
            if(type==='android.view.WindowManager' || type==='WindowManager')windowHandles.add(tokens[i].text);
            if(nativeTypes.has(type.split('.').at(-1)))nativeHandles.add(tokens[i].text);
        }
        if(['val','var'].includes(tokens[i].text) && tokens[i+2]?.text==='=') {
            if(windowHandles.has(tokens[i+3]?.text))windowHandles.add(tokens[i+1].text);
            if(nativeHandles.has(tokens[i+3]?.text))nativeHandles.add(tokens[i+1].text);
        }
    }
    const incidents=[],seen=new Set(),hostCalls=new Map();
    function emit(rule,i,note){if(ownsPopups(file,rule))return;if(rule==='GlassContactMutation')rule='ScreenPopupTrigger';const offset=tokens[i].start,key=rule+':'+offset;if(seen.has(key))return;seen.add(key);incidents.push({rule,path:file,line:source.slice(0,offset).split('\n').length,note});}
    const home=/^Local\w*Popup\w*Home\w*$/;
    const anchorCalls=new Set(['NavigationUtilityPill']);
    const privateCalls=new Set(['Popup','PopupWindow','Dialog','AlertDialog','BasicAlertDialog','DropdownMenu','ModalBottomSheet','PopupMenu','ListPopupWindow','DatePickerDialog','TooltipBox','ExposedDropdownMenu','ExposedDropdownMenuBox','SearchBar']);
    const rawHomeCalls=new Set(['glassPopupHome','glassPopupProjection']);
    const calls=[], scopes=[];let closedCall;
    const pinnedApis={
        GlassMenu:{owner:'GlassMorph',key:'PopupArgument.',parameters:['visible','anchor','onDismiss','originPreparation','content']},
        GlassMorphPopup:{owner:'GlassMorph',key:'PopupArgument.',parameters:['visible','anchor','onDismiss','placement','darkOverride','fitContent','originPreparation','searchDismissOnImeBack','sharedContent','heightSizing','content']},
        GlassPopupHome:{owner:'GlassPopupHome',key:'HomeArgument.',parameters:['home','chrome','container','modifier','content']},
        LyricsPanel:{owner:'LyricsPanel',key:'LyricsArgument.',parameters:['track','positionMs','reducedMotion','onSeek','modifier','sourcesRequest','sourcesAnchor','sampledAtMs','playing','speed','delayMs','durationMs','contentHeight','contentOffset','result','sourcesVisible','onSourcesDismiss','originPreparation','contentAlpha']},
        LyricsBackdrop:{owner:'LyricsBackdrop',key:'LyricsBackdropArgument.',parameters:['key','modifier','contentAlpha','content']},
    };
    function checkArguments(i, api) {
        const spec=pinnedApis[api];if(!spec || tokens[i+1]?.text!=='(' || tokens[i-1]?.text==='fun')return;
        const identity='io.github.anastaci1a.localica.ui.'+api;
        const imported=imports.get(tokens[i].text);
        if(imported && imported!==identity)return;
        const canonical='app/src/main/java/io/github/anastaci1a/localica/ui/'+spec.owner+'.kt';
        if(localDeclarations.some(d=>d.name===api && d.file!==canonical))return;
        const formals=declarations.filter(d=>d.qualified===identity && d.file===canonical);
        let depth=0, start=i+2, ordinal=0;
        for(let j=start;j<tokens.length;j++) {
            const t=tokens[j].text;
            if(depth===0 && (t===',' || t===')')) {
                if(j>start) {
                    const named=tokens[start+1]?.text==='=';
                    const parameter=named?tokens[start].text:(formals.length===1?formals[0].parameters:spec.parameters)?.[ordinal];
                    const motion=spec.key==='HomeArgument.'?['modifier','container']:spec.key==='PopupArgument.'?['originPreparation','sharedContent']:['originPreparation','contentAlpha'];
                    if(motion.includes(parameter) && !ownsPopups(file,spec.key+parameter))emit('ScreenPopupMotion',start,'Popup argument '+parameter+' is restricted to its pinned callers');
                    ordinal++;
                }
                if(t===')')break;start=j+1;continue;
            }
            if(['(','{','['].includes(t))depth++;
            if([')','}',']'].includes(t))depth--;
        }
    }
    for(let i=0;i<tokens.length;i++) {
        if(tokens[i].text==='(')calls.push(name(i-1));
        if(tokens[i].text===')')closedCall=calls.pop();
        if(tokens[i].text==='{')scopes.push(tokens[i-1]?.text===')'?closedCall:name(i-1));
        if(tokens[i].text==='}')scopes.pop();
        const n=name(i),next=tokens[i+1]?.text,previous=tokens[i-1]?.text;
        checkArguments(i,n);
        if(!importTokens.has(i)) {
            const imported=imports.get(tokens[i].text);
            const starRoles=[...imports.values()].filter(q=>/\.(TriggerRole|HomeKind|PopupVariant)\.\*$/.test(q));
            const star=starRoles.map(q=>q.split('.').slice(-2,-1)[0]+'.'+n).find(key=>ownership.some(([r])=>r===key));
            const qualified=imported?.startsWith('io.github.anastaci1a.localica.ui.components.')?imported.slice('io.github.anastaci1a.localica.ui.components.'.length):null;
            const key=qualified && /^(TriggerRole|HomeKind|PopupVariant)\.[^.]+$/.test(qualified)?qualified:
                previous==='.' && ['TriggerRole','HomeKind','PopupVariant'].includes(name(i-2))?name(i-2)+'.'+n:star;
            if(key && !ownsPopups(file,key))emit('ScreenPopupMotion',i,'Named popup roles are restricted to their pinned callers');
        }
        if(n==='triggerRole' && receiverRule(i))emit('ScreenPopupTrigger',i,'Only the anchor owner can read its role');
        if(n==='addView' && (previous==='.' && windowHandles.has(tokens[i-2]?.text) || previous===':' && tokens[i-2]?.text===':' && windowHandles.has(tokens[i-3]?.text)))emit('ScreenPopupDrawPath',i,'A screen cannot create a private window');
        if(['show','showNow','showAsDropDown','showAtLocation'].includes(n) && previous==='.' && nativeHandles.has(tokens[i-2]?.text))emit('ScreenPopupDrawPath',i,'A native presenter cannot bypass the shared owner');
        const classHandle=next===':' && tokens[i+2]?.text===':' && tokens[i+3]?.text==='class';
        if(classHandle && !importTokens.has(i)) {
            if(['GlassAnchor','GlassContact','GlassBodyState','GlassExpansion','GlassMomentum'].includes(n))emit('ScreenPopupTrigger',i,'A protected class handle cannot transfer ownership');
            if(['HomeRef','PopupState','PopupScope'].includes(n))emit('DuplicatePopupRole',i,'A protected class handle cannot transfer system ownership');
            if(['GlassPopupHome','GlassPopupFlight'].includes(n))emit('ScreenPopupHome',i,'A protected class handle cannot transfer ownership');
            if(privateCalls.has(n) || nativeTypes.has((typeIdentity.get(tokens[i].text)||n).split('.').at(-1)) || nativeBuilder(i) || n==='WindowManager')emit('ScreenPopupDrawPath',i,'A native presenter class handle is private');
        }
        const assignment=next==='=' && tokens[i+2]?.text!=='=';
        if(home.test(n||''))emit('ScreenPopupHome',i,'A screen cannot provide a popup home');
        if(n==='LocalPopupBodyPresenter' && (imports.get(tokens[i].text)==='io.github.anastaci1a.localica.ui.components.LocalPopupBodyPresenter' || previous==='.' || !imports.has(tokens[i].text)))emit('ScreenPopupHome',i,'Only the stock presenter owner may provide its local');
        if(['GlassPopupHome','rememberGlassPopupHome'].includes(n) && next==='(' && previous!=='fun') {
            const count=(hostCalls.get(n)||0)+1;hostCalls.set(n,count);
            if(count>(popupHosts.get(file)?.[n==='rememberGlassPopupHome'?0:1]||0))emit('ScreenPopupHome',i,'Only the counted application shell homes may be hosted');
        }
        if(rawHomeCalls.has(n) && next==='(')emit('ScreenPopupHome',i,'Use the shared component home scope');
        if(['motionKind','popupKind','popupLaw','motionLaw','quiet','originPresentation','contactPresentation'].includes(n) && assignment)emit('ScreenPopupMotion',i,'The popup component derives its motion');
        if(assignment && ['(',','].includes(previous) && (['true','false'].includes(tokens[i+2]?.text) || booleanValues.has(tokens[i+2]?.text) || [...localDeclarations,...declarations].some(d=>d.name===calls.at(-1) && d.booleans.has(n))) && !stockIntent(calls.at(-1), n) && ['glassAnchor','rememberPresence','rememberFaceExpansion','PopupActionPill','ActionIcon'].includes(calls.at(-1)))emit('ScreenPopupMotion',i,'Only named roles may configure popup policy');
        if(['plain','selection','lyrics'].includes(n) && assignment && calls.some(c=>['glassAnchor','rememberPresence','rememberFaceExpansion','PopupActionPill','ActionIcon'].includes(c)))emit('ScreenPopupMotion',i,'Use a named content role, never a raw flag');
        if(['PopupKind','PopupMotionKind'].includes(n) && next==='.')emit('ScreenPopupMotion',i,'Screens cannot select popup motion kinds');
        if(['shadowOpacity','opacity'].includes(n) && assignment && calls.some(c=>['PopupActionPill','GlassMenu','GlassMorphPopup','GlassDialog','Popup','glassAnchor'].includes(c)))emit('ScreenPopupTrigger',i,'Popup opacity and shadow wiring belong to the shared component');
        if(n==='law' && assignment)emit('ScreenPopupMotion',i,'Screens cannot override a motion law');
        if(['rememberPopupMotion','rememberPopupWindowMotion','rememberExpandCollapse'].includes(n) && (next==='(' || (previous===':' && tokens[i-2]?.text===':')))emit('ScreenPopupMotion',i,'Popup motion owners are internal to the shared system');
        if(n==='drawPopupProjection' && (next==='(' || (previous===':' && tokens[i-2]?.text===':')) && !ownsPopups(file,'PopupProjection'))emit('ScreenPopupDrawPath',i,'Projection belongs to its pinned shared implementation');
        if(['rememberFaceExpansion','rememberPresence'].includes(n) && next==='(') {
            let j=i+1,depth=0;do{if(tokens[j]?.text==='(')depth++;if(tokens[j]?.text===')')depth--;j++;}while(j<tokens.length && depth);
            if(tokens[j]?.text==='{')emit('ScreenPopupMotion',i,'Use a named component capability, without a screen law lambda');
        }
        if((anchorCalls.has(n) || n==='GlassAnchor') && next==='(')emit('ScreenPopupTrigger',i,'Use the component anchor binding');
        const fieldWrite=(assignment || (['+','-','*','/','%'].includes(next) && tokens[i+2]?.text==='=') || (['+','-'].includes(next) && tokens[i+2]?.text===next) || (['+','-'].includes(previous) && tokens[i-2]?.text===previous));
        const receiver = previous==='.' ? tokens[i-2]?.text : undefined;
        const prefixReceiver = tokens[i+1]?.text==='.' ? tokens[i].text : undefined;
        const protectedRule=receiverRule(i) || (fieldWrite && previous!=='.'?lambdaHandles.findLast(x=>x.start<i && i<x.end && x.name===null)?.rule:undefined);
        if(n==='glassAnchor' && next==='(' && previous==='.' && receiverRule(i-2) && !ownsPopups(file,'ShadowRegistration'))emit('ScreenPopupTrigger',i,'Only GlassShadow registers popup surface nodes');
        if(fieldWrite && protectedRule && (previous==='.' || lambdaHandles.some(x=>x.start<i && i<x.end && x.name===null)))emit(protectedRule,i,'Only the shared owner mutates popup handles');
        if(next==='(' && ['add','addAll','clear','remove','removeAll','removeAt','removeIf','retainAll','set','put','putAll','replace','replaceAll','sort','sortWith','sortBy','sortDescending','reverse','shuffle','fill','bind','unbind','reset','advance','blendFrom'].includes(n) && protectedRule)emit(protectedRule,i,'Only the shared owner mutates popup collections');
        if(previous===':' && tokens[i-2]?.text===':') {
            const rule=n==='GlassAnchor'?'ScreenPopupTrigger':['GlassPopupHome','GlassPopupFlight','rememberGlassPopupHome','glassPopupHome','glassPopupProjection'].includes(n)?'ScreenPopupHome':receiverRule(i-3);
            if(rule)emit(rule,i,'A callable reference cannot transfer popup ownership');
        }
        if(fieldWrite && anchors.has(receiver))emit('ScreenPopupTrigger',i,'Only the shared component writes anchor fields');
        if(anchors.has(prefixReceiver) && ['+','-'].includes(previous) && tokens[i-2]?.text===previous)emit('ScreenPopupTrigger',i,'Only the shared component writes anchor fields');
        if(['face','quiet','popupHome','originPresentation'].includes(n) && assignment && (previous==='.' || [...calls,...scopes].some(c=>['apply','with'].includes(c))))emit('ScreenPopupTrigger',i,'Screens cannot configure popup anchor internals');
        if((privateCalls.has(n) || nativeTypes.has(n) || nativeTypes.has((typeIdentity.get(tokens[i].text)||'').split('.').at(-1)) || nativeBuilder(i)) && !importTokens.has(i) && previous!=='fun' && (next==='(' || next==='{' || (previous===':' && tokens[i-2]?.text===':'))) {
            // ui.components.Popup is the standard public API; native and unresolved names fail closed.
            let begin=i;while(begin>=2 && tokens[begin-1].text==='.')begin-=2;
            const qualified=tokens.slice(begin,i+1).map(t=>t.text).join('');
            const resolved=qualified.includes('.')?qualified:imports.get(tokens[i].text);
            if(resolved!=='io.github.anastaci1a.localica.ui.components.Popup')emit('ScreenPopupDrawPath',i,'Use the shared popup presenter');
        }
        if(['class','interface','object','typealias'].includes(n) && !(previous===':' && tokens[i-2]?.text===':')) {
            const declared=name(i+1)||'';
            let j=i+1, depth=0;
            while(j<tokens.length && j<i+80) {
                if(tokens[j].text==='(')depth++;
                if(tokens[j].text===')')depth--;
                if(depth===0 && ['{','=',';'].includes(tokens[j].text))break;
                if(depth===0 && tokens[j].text===':') {
                    let k=j+1;
                    while(k<tokens.length && !['{',';','='].includes(tokens[k].text)) {
                        if(k>j+1 && source.slice(tokens[k-1].start,tokens[k].start).includes('\n'))break;
                        let end=k;while(tokens[end+1]?.text==='.')end+=2;
                        const qualified=tokens.slice(k,end+1).map(t=>t.text).join('');
                        const identity=typeIdentity.get(tokens[k].text)||qualified;
                        if(protectedTypes.has(identity) && !popupRoles.has(file+':'+declared))emit('DuplicatePopupRole',i,'Only the canonical system implementation may implement this resolved role');
                        k++;
                    }
                    break;
                }
                if(j>i+1 && depth===0 && source.slice(tokens[j-1].start,tokens[j].start).includes('\n'))break;
                j++;
            }
            if(protectedTypes.has(packageMatch+'.'+declared) && !['PopupState','PopupScope'].includes(declared) && !popupRoles.has(file+':'+declared))emit('DuplicatePopupRole',i+1,'Only system owners may implement a popup home or trigger role');
        }
        if(['staticCompositionLocalOf','compositionLocalOf'].includes(n) && next==='<') {
            const type=tokens.slice(i+2,i+10).map(t=>t.text).join('');
            if(/PopupHome|PopupBodyPresenter|PopupAnchor/.test(type))emit('ScreenPopupHome',i,'A screen cannot introduce another popup owner local');
        }
        // A private layer-based popup is forbidden even without a native window.
        if(n==='fun' && /Popup|Dialog|Menu/.test(name(i+1)||'')) {
            let j=i+2;while(j<tokens.length && tokens[j].text!=='{')j++;
            let end=j+1,depth=1;while(end<tokens.length && depth){if(tokens[end].text==='{')depth++;if(tokens[end].text==='}')depth--;end++;}
            const body=tokens.slice(j,end).map(t=>t.text);
            if(body.includes('graphicsLayer') && !body.some(t=>['GlassMorphPopup','GlassMenu','GlassDialog','Popup','PopupHomeContent'].includes(t)))emit('ScreenPopupDrawPath',i+1,'A popup cannot implement its own graphics layer presentation');
        }
    }
    return incidents;
}
export function checkPopupTree(files) {const declarations=files.flatMap(({path,source})=>declaredFunctions(path,source));return files.flatMap(({path,source})=>checkPopupSource(path,source,declarations));}
