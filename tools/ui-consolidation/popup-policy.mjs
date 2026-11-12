import fs from 'node:fs';
/** Hard rule 11: popup implementation belongs to these owners, never to a screen. */
export const popupRules = ['ScreenPopupHome', 'ScreenPopupMotion', 'ScreenPopupTrigger', 'ScreenPopupDrawPath', 'DuplicatePopupRole'];
const ownership = fs.readFileSync(new URL('../../lint/src/main/resources/popup-owners.txt',import.meta.url),'utf8').trim().split('\n').map(row=>row.split('='));
export const popupOwners = [...new Set(ownership.map(([,path])=>path))];
const popupHosts = new Set(fs.readFileSync(new URL('../../lint/src/main/resources/popup-hosts.txt',import.meta.url),'utf8').trim().split('\n'));
const popupRoles = new Set(fs.readFileSync(new URL('../../lint/src/main/resources/popup-roles.txt',import.meta.url),'utf8').trim().split('\n'));
export function ownsPopups(file, rule) { return ownership.some(([r,path])=>path===file && (!rule || r===rule)); }
// Tokenize Kotlin so comments, ordinary/raw strings and whitespace cannot hide or fabricate a call.
// Offsets are retained for exact source receipts. Import aliases and qualified calls resolve to the same spelling.
export function kotlinTokens(source) {
    const tokens=[]; let i=0;
    while(i<source.length) {
        if (/\s/.test(source[i])) {i++;continue;}
        if(source.startsWith('//',i)) {i=source.indexOf('\n',i);if(i<0)break;continue;}
        if(source.startsWith('/*',i)) {let depth=1;i+=2;while(i<source.length && depth){if(source.startsWith('/*',i)){depth++;i+=2;}else if(source.startsWith('*/',i)){depth--;i+=2;}else i++;}continue;}
        const start=i;
        if(source.startsWith('"""',i)){const end=source.indexOf('"""',i+3);i=end<0?source.length:end+3;tokens.push({text:'<string>',start});continue;}
        if(source[i]==='"' || source[i]==="'"){const quote=source[i++];while(i<source.length){if(source[i++]==='\\')i++;else if(source[i-1]===quote)break;}tokens.push({text:'<string>',start});continue;}
        if(source[i]==='`'){i++;const begin=i;while(i<source.length && source[i]!=='`')i++;tokens.push({text:source.slice(begin,i),start});i++;continue;}
        const word=source.slice(i).match(/^[A-Za-z_$][\w$]*/)?.[0];
        if(word){tokens.push({text:word,start});i+=word.length;}else tokens.push({text:source[i++],start});
    }
    return tokens;
}
export function checkPopupSource(file, source) {
    if(!file.startsWith('app/src/main/') || !file.endsWith('.kt') )return [];
    const tokens=kotlinTokens(source), aliases=new Map(), imports=new Map(), anchors=new Set();
    for(let i=0;i<tokens.length;i++)if(tokens[i].text==='import') {
        let j=i+1,last='', qualified='';while(j<tokens.length && !['as',';','import','package'].includes(tokens[j].text)) {
            if(j>i+1 && source.slice(tokens[j-1].start,tokens[j].start).includes('\n'))break;
            qualified+=tokens[j].text;if(tokens[j].text!=='.')last=tokens[j].text;j++;
        }
        const local=tokens[j]?.text==='as'?tokens[j+1]?.text:last;
        imports.set(local,qualified);if(local!==last)aliases.set(local,last);
    }
    const name=i=>aliases.get(tokens[i]?.text)||tokens[i]?.text;
    for(let i=0;i<tokens.length;i++) {
        if(tokens[i+1]?.text===':' && name(i+2)==='GlassAnchor') anchors.add(tokens[i].text);
        if(['val','var'].includes(tokens[i].text) && tokens[i+2]?.text==='=' && ['rememberGlassAnchor','GlassAnchor'].includes(name(i+3))) anchors.add(tokens[i+1].text);
    }
    const incidents=[],seen=new Set();
    function emit(rule,i,note){if(ownsPopups(file,rule))return;const offset=tokens[i].start,key=rule+':'+offset;if(seen.has(key))return;seen.add(key);incidents.push({rule,path:file,line:source.slice(0,offset).split('\n').length,note});}
    const home=/^Local\w*Popup\w*Home\w*$/;
    const anchorCalls=new Set(['NavigationUtilityPill']);
    const privateCalls=new Set(['Popup','PopupWindow','Dialog','AlertDialog','BasicAlertDialog','DropdownMenu','ModalBottomSheet']);
    const rawHomeCalls=new Set(['glassPopupHome','glassPopupProjection']);
    const calls=[], scopes=[];let closedCall;
    for(let i=0;i<tokens.length;i++) {
        if(tokens[i].text==='(')calls.push(name(i-1));
        if(tokens[i].text===')')closedCall=calls.pop();
        if(tokens[i].text==='{')scopes.push(tokens[i-1]?.text===')'?closedCall:name(i-1));
        if(tokens[i].text==='}')scopes.pop();
        const n=name(i),next=tokens[i+1]?.text,previous=tokens[i-1]?.text;
        const assignment=next==='=' && tokens[i+2]?.text!=='=';
        if(home.test(n||''))emit('ScreenPopupHome',i,'A screen cannot provide a popup home');
        if(['GlassPopupHome','rememberGlassPopupHome'].includes(n) && next==='(' && previous!=='fun' && !popupHosts.has(file))emit('ScreenPopupHome',i,'Only the pinned application shells host popup homes');
        if(rawHomeCalls.has(n) && next==='(')emit('ScreenPopupHome',i,'Use the shared component home scope');
        if(['motionKind','popupKind','popupLaw','motionLaw','quiet','originPresentation','contactPresentation'].includes(n) && assignment)emit('ScreenPopupMotion',i,'The popup component derives its motion');
        if(assignment && ['(',','].includes(previous) && ['true','false'].includes(tokens[i+2]?.text) && !['visible','shown','expanded','menuVisible','enabled','glass','quietInputEnabled'].includes(n) && ['glassAnchor','rememberPresence','rememberFaceExpansion','PopupActionPill','ActionIcon'].includes(calls.at(-1)))emit('ScreenPopupMotion',i,'Only named roles may configure popup policy');
        if(['plain','selection','lyrics'].includes(n) && assignment && calls.some(c=>['glassAnchor','rememberPresence','rememberFaceExpansion','PopupActionPill','ActionIcon'].includes(c)))emit('ScreenPopupMotion',i,'Use a named content role, never a raw flag');
        if(['PopupKind','PopupMotionKind'].includes(n) && next==='.')emit('ScreenPopupMotion',i,'Screens cannot select popup motion kinds');
        if(['shadowOpacity','opacity'].includes(n) && assignment && calls.some(c=>['PopupActionPill','GlassMenu','GlassMorphPopup','GlassDialog','Popup','glassAnchor'].includes(c)))emit('ScreenPopupTrigger',i,'Popup opacity and shadow wiring belong to the shared component');
        if(n==='law' && assignment)emit('ScreenPopupMotion',i,'Screens cannot override a motion law');
        if(['rememberPopupMotion','rememberPopupWindowMotion','rememberExpandCollapse'].includes(n) && next==='(')emit('ScreenPopupMotion',i,'Popup motion owners are internal to the shared system');
        if(['rememberFaceExpansion','rememberPresence'].includes(n) && next==='(') {
            let j=i+1,depth=0;do{if(tokens[j]?.text==='(')depth++;if(tokens[j]?.text===')')depth--;j++;}while(j<tokens.length && depth);
            if(tokens[j]?.text==='{')emit('ScreenPopupMotion',i,'Use a named component capability, without a screen law lambda');
        }
        if((anchorCalls.has(n) || n==='GlassAnchor') && next==='(')emit('ScreenPopupTrigger',i,'Use the component anchor binding');
        const fieldWrite=(assignment || (['+','-','*','/','%'].includes(next) && tokens[i+2]?.text==='=') || (['+','-'].includes(next) && tokens[i+2]?.text===next) || (['+','-'].includes(previous) && tokens[i-2]?.text===previous));
        const receiver = previous==='.' ? tokens[i-2]?.text : undefined;
        const prefixReceiver = tokens[i+1]?.text==='.' ? tokens[i].text : undefined;
        if(fieldWrite && anchors.has(receiver))emit('ScreenPopupTrigger',i,'Only the shared component writes anchor fields');
        if(anchors.has(prefixReceiver) && ['+','-'].includes(previous) && tokens[i-2]?.text===previous)emit('ScreenPopupTrigger',i,'Only the shared component writes anchor fields');
        if(['face','quiet','popupHome','originPresentation'].includes(n) && assignment && (previous==='.' || [...calls,...scopes].some(c=>['apply','with'].includes(c))))emit('ScreenPopupTrigger',i,'Screens cannot configure popup anchor internals');
        if(privateCalls.has(n) && previous!=='fun' && (next==='(' || next==='{')) {
            // ui.components.Popup is the standard public API; native and unresolved names fail closed.
            let begin=i;while(begin>=2 && tokens[begin-1].text==='.')begin-=2;
            const qualified=tokens.slice(begin,i+1).map(t=>t.text).join('');
            const resolved=qualified.includes('.')?qualified:imports.get(tokens[i].text);
            if(resolved!=='io.github.anastaci1a.localica.ui.components.Popup')emit('ScreenPopupDrawPath',i,'Use the shared popup presenter');
        }
        if(['class','interface','object','typealias'].includes(n)) {
            const declared=name(i+1)||'';
            if((/Popup.*(Home|Anchor|Trigger)|(?:Home|Anchor|Trigger).*Popup|^(GlassAnchor|HomeRef)$/.test(declared)) && !popupRoles.has(file+':'+declared))emit('DuplicatePopupRole',i+1,'Only system owners may implement a popup home or trigger role');
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
export function checkPopupTree(files) {return files.flatMap(({path,source})=>checkPopupSource(path,source));}
