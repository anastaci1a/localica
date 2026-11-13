import test from 'node:test';
import fs from 'node:fs';
import assert from 'node:assert/strict';
import {checkPopupSource, ownsPopups, popupRules, popupOwners, auditPopupTree, popupRatchet, kotlinTokens, popupApiSurface, declaredFunctions, popupApiExclusions, sharedEntryPoints, sharedPopupFile} from './popup-policy.mjs';
const screen='app/src/main/java/io/github/anastaci1a/localica/ui/Screen.kt';
const rules=source=>new Set(checkPopupSource(screen,source).map(x=>x.rule));
test('calibration note 6: named roles are pinned to their actual callers',()=>{
 assert.ok(rules('import io.github.anastaci1a.localica.ui.components.TriggerRole as Role\nfun f(){ println(Role.Selection); println(Role.Lyrics); println(Role.Navigation) }').has('ScreenPopupMotion'));
});
test('calibration note 7: anchor node registration belongs only to GlassShadow',()=>{
 assert.ok(rules('fun f(anchor: GlassAnchor){ anchor.glassAnchor(materialRole()) }').has('ScreenPopupTrigger'));
});
test('calibration note 8: nested contact state and its mutators are protected',()=>{
 for(const expression of ['anchor.contact.modal = true','anchor.contact.state.impulseScale = 2f','anchor.contact.expansion.bind()'])
  assert.ok(rules('fun f(anchor: GlassAnchor){ '+expression+' }').has('ScreenPopupTrigger'),expression);
});
test('calibration C3: presenter provision resolves aliases',()=>{
 for(const local of ['LocalPopupBodyPresenter','Presenter']) {
  const source='import io.github.anastaci1a.localica.ui.components.LocalPopupBodyPresenter as '+local+'\nfun f(){ CompositionLocalProvider('+local+' provides {}) {} }';
  assert.ok(rules(source).has('ScreenPopupHome'));
  assert.equal(checkPopupSource(screen.replace('Screen.kt','components/Popup.kt'),source).length,0);
 }
});
test('calibration C4: home and flight mutation through anchors is forbidden',()=>{
 for(const expression of ['home.mounted = false','home.flights.clear()','home.children.add(home)','flight.chromeTrigger = false','flight.phase = { 0f }']) {
  assert.ok(rules('fun f(anchor: GlassAnchor){ val home=anchor.popupHome ?: return; val flight=home.flights.first(); '+expression+' }').has('ScreenPopupHome'),expression);
 }
});
test('calibration C5: constructor and writable property references are forbidden',()=>{
 for(const expression of ['val make=::GlassAnchor','val field=anchor::face; field.set({})'])
  assert.ok(rules('fun f(anchor: GlassAnchor){ '+expression+' }').has('ScreenPopupTrigger'),expression);
});
const fixtures={
 ScreenPopupHome:'fun Screen() { CompositionLocalProvider(LocalGlassPopupHome provides home) {} }',
 ScreenPopupMotion:'fun Screen() { GlassMenu(motionKind = PopupKind.Utility, law = { it.expansion }) }',
 ScreenPopupTrigger:'fun Screen() { val anchor = GlassAnchor(); anchor.face = { Text(label) } }',
 ScreenPopupDrawPath:'import androidx.compose.ui.window.Dialog\nfun Screen() { Dialog(onDismissRequest = {}) {} }',
 DuplicatePopupRole:'class ScreenPopupHome : io.github.anastaci1a.localica.ui.components.PopupScope\nval LocalPrivate = staticCompositionLocalOf<GlassPopupHome?> { null }',
};
for(const rule of popupRules)test(`${rule} has a firing negative fixture`,()=>assert.ok(rules(fixtures[rule]).has(rule)));
test('all rules fire in one release fixture',()=>assert.deepEqual(rules(Object.values(fixtures).join('\n')),new Set(popupRules)));
test('aliased and qualified forbidden calls are still forbidden',()=>{
 assert.ok(rules('import io.github.anastaci1a.localica.ui.GlassAnchor as Custom\nfun f()=Custom()').has('ScreenPopupTrigger'));
 assert.ok(rules('import io.github.anastaci1a.localica.ui.LocalGlassPopupHome as LocalHome\nfun f() { LocalHome provides home }').has('ScreenPopupHome'));
 assert.ok(rules('fun f(){ androidx.compose.ui.window.Popup {} }').has('ScreenPopupDrawPath'));
});
test('comments, literals and backticks do not bypass policy',()=>{
 assert.equal(rules('// Dialog()\nval example="LocalGlassPopupHome provides home"').size,0);
 assert.ok(rules('fun f() { `GlassAnchor`(/*nested /*comment*/ */ ) }').has('ScreenPopupTrigger'));
});
test('private graphics-layer popup fails',()=>assert.ok(rules('fun CustomPopup() { Box(Modifier.graphicsLayer { alpha = x }) {} }').has('ScreenPopupDrawPath')));
test('component entry points and ordinary drawing pass',()=>assert.equal(rules('fun Screen() { Box(Modifier.glassAnchor(anchor, face = { Text(label) })) {}; PopupActionPill(anchor) {}; Box(Modifier.graphicsLayer { alpha = x }) {} }').size,0));
test('native Popup cannot masquerade as the component through an alias',()=>assert.ok(rules('import androidx.compose.ui.window.Popup as Native\nfun f(){ Native {} }').has('ScreenPopupDrawPath')));
test('ownership is pinned per rule, never per package or whole file',()=>{
 const path=screen.replace('Screen.kt','GlassPopupHome.kt');
 assert.ok(ownsPopups(path,'ScreenPopupHome'));
 assert.equal(ownsPopups(path,'ScreenPopupTrigger'),false);
 assert.deepEqual(checkPopupSource(path,fixtures.ScreenPopupHome),[]);
 assert.ok(checkPopupSource(path,fixtures.ScreenPopupTrigger).length>0);
 for(const owner of ['GlassMorph','GlassPopupHome','GlassDialogs']) assert.equal(ownsPopups(screen.replace('Screen.kt',owner+'Copy.kt')),false);
});
test('a second trigger type outside the owner list fails',()=>assert.ok(rules('class AlternativePopupTrigger : io.github.anastaci1a.localica.ui.components.PopupScope').has('DuplicatePopupRole')));

test('shared Popup import cannot conceal a qualified native call',()=>assert.ok(rules('import io.github.anastaci1a.localica.ui.components.Popup\nfun f(){ androidx.compose.ui.window.Popup {} }').has('ScreenPopupDrawPath')));
test('only the fully resolved standard Popup is permitted',()=>{
 assert.equal(rules('import io.github.anastaci1a.localica.ui.components.Popup as Shared\nfun f(){ Shared {} }').size,0);
 assert.ok(rules('fun f(){ custom.components.Popup {} }').has('ScreenPopupDrawPath'));
});
test('per-call popup shadow/opacity wiring fails while ordinary opacity passes',()=>{
 assert.ok(rules('fun f(){ PopupActionPill(anchor, shadowOpacity = { phase }) }').has('ScreenPopupTrigger'));
 assert.ok(rules('fun f(){ GlassMenu(open, opacity = phase) {} }').has('ScreenPopupTrigger'));
 assert.equal(rules('fun f(){ SlidingTabs(shadowOpacity = { phase }); Canvas(opacity = phase) }').size,0);
});

test('new files in components and engine cannot create popup policy owners',()=>{
 for(const folder of ['components','engine']){
  const path=screen.replace('ui/Screen.kt','ui/'+folder+'/NewPopupPolicy.kt');
  assert.equal(ownsPopups(path),false);
  assert.ok(checkPopupSource(path,'val LocalPrivatePopupHome = compositionLocalOf<Any?> { null }; fun f(){ LocalPrivatePopupHome provides home; rememberFaceExpansion(open) { it.expansion } }').length>=2);
 }
});
test('implicit apply and with writers and named overrides fail',()=>{
 for(const source of ['fun f(){ anchor.apply { face = {} } }','fun f(){ with(anchor) { quiet = true } }','fun f(){ Modifier.glassAnchor(anchor, quiet = true) }','fun f(){ Modifier.glassAnchor(anchor, originPresentation = {}) }']) assert.ok(rules(source).size>0,source);
});

test('owner manifest is exact, unique and stable against new neighboring paths',()=>{
 assert.equal(new Set(popupOwners).size,popupOwners.length);
 assert.ok(popupOwners.every(p=>p.endsWith('.kt')&&!p.includes('*')));
 for(const p of popupOwners) assert.equal(ownsPopups(p.replace('.kt','Copy.kt')),false);
});

test('even an existing owner cannot add a second home or trigger implementation',()=>{
 const path=screen.replace('Screen.kt','GlassMorph.kt');
 assert.ok(checkPopupSource(path,'class SecondPopupHome : io.github.anastaci1a.localica.ui.components.PopupScope').some(x=>x.rule==='DuplicatePopupRole'));
 assert.equal(checkPopupSource(path,'class GlassAnchor').length,0);
});

test('a mutant in a non-owning components file fires every rule',()=>{
 const path=screen.replace('ui/Screen.kt','ui/components/ControlProgress.kt');
 for(const rule of popupRules) assert.ok(checkPopupSource(path,fixtures[rule]).some(x=>x.rule===rule),rule);
});
test('raw renamed boolean motion selectors fail',()=>{
 for(const [api,flag] of [['glassAnchor','plain'],['rememberFaceExpansion','selection'],['rememberPresence','lyrics']]) assert.ok(rules('fun f(){ '+api+'(state, '+flag+' = true) }').has('ScreenPopupMotion'));
});

test('an arbitrarily renamed raw Boolean flag fails; intent callbacks and nested content pass',()=>{
 assert.ok(rules('fun f(){ glassAnchor(anchor, magicMotionSwitch = true) }').has('ScreenPopupMotion'));
 assert.equal(rules('fun f(){ PopupActionPill(anchor, onMore = { menu = true }); glassAnchor(anchor, face = { CheckBox(checked = true) }); rememberPresence(key, shown = true) }').size,0);
});

test('only the pinned shell hosts may create shared homes',()=>{
 const source='fun f(){ GlassPopupHome(rememberGlassPopupHome(false)) {} }';
 assert.ok(rules(source).has('ScreenPopupHome'));
 for(const host of ['LocalicaScreen','PlayerSurface','QueueSurface']) assert.equal(checkPopupSource(screen.replace('Screen',host),source).length,0);
 assert.ok(checkPopupSource(screen.replace('ui/Screen.kt','ui/components/NewHost.kt'),source).some(x=>x.rule==='ScreenPopupHome'));
});
test('compound and prefix/postfix anchor mutations are rejected',()=>{
 for(const expression of ['anchor.futureField += 1','anchor.futureField -= 1','anchor.futureField *= 2','anchor.futureField /= 2','anchor.futureField %= 2','anchor.futureField++','++anchor.futureField','anchor.futureField--','--anchor.futureField']) assert.ok(rules('fun f(anchor: GlassAnchor) { '+expression+' }').has('ScreenPopupTrigger'),expression);
 assert.equal(rules('fun f(){ var value=0; value++; value+=2 }').size,0);
});

test('nested aliases and scope receivers retain anchor ownership',()=>{
 for(const body of ['val contact=anchor.contact; val state=contact.state; state.impulseScale += 1f','val expansion=anchor.contact.expansion; expansion.bind()','rememberGlassAnchor().also { it.source = source }','anchor.let { borrowed -> borrowed.contact.modal = true }','rememberGlassAnchor().apply { source = source }'])
  assert.ok(rules('fun f(anchor: GlassAnchor){ '+body+' }').has('ScreenPopupTrigger'),body);
});
test('nested callable references, with and nullable scope receivers stay protected',()=>{
 for(const body of ['val bind=anchor.contact.expansion::bind','with(anchor.contact) { modal = true }','anchor?.let { borrowed -> borrowed.contact.modal = true }'])
  assert.ok(rules('fun f(anchor: GlassAnchor){ '+body+' }').has('ScreenPopupTrigger'),body);
 for(const body of ['val factory=::rememberGlassPopupHome','val projection=::glassPopupProjection'])
  assert.ok(rules('fun f(){ '+body+' }').has('ScreenPopupHome'),body);
});
test('aliased data role constructors cannot borrow another surface policy',()=>{
 for(const role of ['SelectionItem','LyricsHeader','MaterialSurface','RetainedSurface'])
  assert.ok(rules('import io.github.anastaci1a.localica.ui.components.TriggerRole.'+role+' as Borrowed\nfun f(){ Borrowed(1) }').has('ScreenPopupMotion'),role);
});
test('pinned role callers and shadow registration are sanctioned',()=>{
 for(const [file,role] of [['FloatingSelectionPanel','Selection'],['PlayerSurface','Lyrics'],['LocalicaScreen','Navigation']]) assert.equal(checkPopupSource(screen.replace('Screen',file),'import io.github.anastaci1a.localica.ui.components.TriggerRole as Role\nfun f(){ println(Role.'+role+') }').length,0);
 assert.equal(checkPopupSource(screen.replace('Screen','GlassShadow'),'fun f(anchor: GlassAnchor){ anchor.glassAnchor(materialRole()) }').length,0);
});

test('every declared popup role case has exact manifest owners and no wildcard',()=>{
 const manifest=fs.readFileSync(new URL('../../lint/src/main/resources/popup-owners.txt',import.meta.url),'utf8').trim().split('\n').map(row=>row.split('='));
 const trigger=fs.readFileSync(new URL('../../app/src/main/java/io/github/anastaci1a/localica/ui/components/TriggerRole.kt',import.meta.url),'utf8');
 const cases=[...trigger.matchAll(/data (?:object|class) (\w+)/g)].map(m=>m[1]);
 assert.equal(cases.length,11);
 for(const c of cases){const key='TriggerRole.'+c;const rows=manifest.filter(([role])=>role===key);assert.ok(rows.length>0,key);assert.equal(new Set(rows.map(([,path])=>path)).size,rows.length,key);assert.ok(rows.every(([,path])=>path.startsWith('app/src/main/java/')&&path.endsWith('.kt')&&!path.includes('*')));assert.ok(checkPopupSource(screen,'import io.github.anastaci1a.localica.ui.components.TriggerRole as Role\nfun f(){ println(Role.'+c+') }').some(x=>x.rule==='ScreenPopupMotion'),key);}
 for(const [kind,cases] of [['HomeKind',['Surface','Quiet','Contextual','Detached','Page','Search']],['PopupVariant',['Menu','Choice','Dialog','Sheet','Page','Search']]])for(const c of cases)assert.ok(manifest.some(([key])=>key===kind+'.'+c));
});

test('blind B5 and 9b: native references, aliases, subclasses and all private presenters fail',()=>{
 for(const source of [
 'import android.app.Dialog as Window\nfun f(){ val make=::Window }',
 'import android.app.Dialog\ntypealias Window = Dialog\nfun f(){ val make=::Window }',
 'import android.app.Dialog\nclass Innocent : Dialog()\nfun f(){ Innocent() }',
 ...['PopupMenu','ListPopupWindow','DatePickerDialog','TooltipBox','ExposedDropdownMenu','SearchBar'].map(n=>'fun f(){ '+n+' {} }')
 ]) assert.ok(rules(source).has('ScreenPopupDrawPath'),source);
});
test('blind 9a: projection and motion callable references fail',()=>{
 for(const n of ['rememberPopupMotion','rememberExpandCollapse','drawPopupProjection'])
  assert.ok(rules('fun f(){ val borrow=::'+n+' }').has(n==='drawPopupProjection'?'ScreenPopupDrawPath':'ScreenPopupMotion'),n);
});
test('blind 9d: borrowed intent names and nullable policy values fail',()=>{
 assert.ok(rules('fun rememberPresence(shown: Boolean, enabled: Boolean?) = Unit\nfun f(){ rememberPresence(true, enabled = null) }').has('ScreenPopupMotion'));
});
test('blind 9f: role identity includes aliased and anonymous implementations',()=>{
 for(const source of ['import io.github.anastaci1a.localica.ui.components.PopupState as State\nclass Innocent : State',
 'import io.github.anastaci1a.localica.ui.components.PopupScope as Scope\nval other = object : Scope {}'])
  assert.ok(rules(source).has('DuplicatePopupRole'),source);
});

test('blind host pin: restoring the deleted utility home in LocalicaScreen is rejected',()=>{
 const source='import io.github.anastaci1a.localica.ui.rememberGlassPopupHome as home\nfun screen(){ val browse=home(); val player=home(); val queue=home(); GlassPopupHome(browse) {}; val utility=home(); GlassPopupHome(utility) {} }';
 const found=checkPopupSource(screen.replace('Screen.kt','LocalicaScreen.kt'),source).filter(x=>x.rule==='ScreenPopupHome');
 assert.equal(found.length,2);
});

test('popup names alone do not invent a role implementation',()=>assert.equal(rules('class PopupHomeStatistics').size,0));

test('a class literal is not a new implementation declaration',()=>assert.equal(rules('@OptIn(ExperimentalLayoutApi::class)\nfun f(anchor: GlassAnchor) {}').size,0));

// These source bodies are also independently executed by PopupGateRedTeamTest.
const redTeamFixtures=JSON.parse(fs.readFileSync(new URL('./popup-red-team-fixtures.json',import.meta.url),'utf8'));
for(const {id,path,source} of redTeamFixtures)test('checklist 44: '+id,()=>{
 assert.ok(checkPopupSource(path,source).length>0,id+' must reject this isolated bypass');
});
test('executable templates preserve offsets and ignore literal and escaped text',()=>{
 const source='val sample="literal Dialog() ${GlassAnchor()}"';
 const incidents=checkPopupSource(screen,source);
 assert.equal(incidents.length,1);
 assert.equal(incidents[0].rule,'ScreenPopupTrigger');
 assert.equal(rules('val text="\\${GlassAnchor()}"').size,0);
 assert.equal(rules('val text="""literal GlassAnchor()"""').size,0);
 assert.ok(rules('val text="${run { println("${GlassAnchor()}") }}"').has('ScreenPopupTrigger'));
});
test('ordinary view insertion and public content layout remain allowed',()=>{
 assert.equal(rules('fun f(group: android.view.ViewGroup, view: android.view.View, params: android.view.WindowManager.LayoutParams) { group.addView(view, params) }').size,0);
 assert.equal(rules('fun f() { HoverDrag(contentAlpha={ 1f }) {} }').size,0);
});
test('semantic and Node red-team fixture bodies remain identical',()=>{
 const semantic=fs.readFileSync(new URL('../../lint/src/test/java/io/github/anastaci1a/localica/lint/PopupGateRedTeamTest.kt',import.meta.url),'utf8');
 const decode=s=>JSON.parse(s.replace(/\\\$/g,'$'));
 const mirrored=[];
 for(const line of semantic.split('\n')) {
  const match=line.match(/fun (test\w+)\(\) = check\(("(?:\\.|[^"\\])*")(.*)\)/);
  if(!match)continue;
  const [,id,body,tail]=match;
  const strings=[...tail.matchAll(/"(?:\\.|[^"\\])*"/g)].map(m=>decode(m[0]));
  const named=/name\s*=/.test(tail), imports=named?'':strings[0]||'', name=named?strings.at(-1):strings[1]||'Subject';
  mirrored.push({id,path:'app/src/main/java/io/github/anastaci1a/localica/ui/'+name+'.kt',source:'package io.github.anastaci1a.localica.ui\nimport androidx.compose.runtime.remember\nimport io.github.anastaci1a.localica.ui.components.TriggerRole\n'+imports+'\n'+decode(body)});
 }
 assert.deepEqual(redTeamFixtures,mirrored);
});
test('unrelated generic mutable state and implicit view-group insertion remain allowed',()=>{
 assert.equal(rules('fun f(output: androidx.compose.runtime.MutableState<Float>) { output.value=2f }').size,0);
 assert.equal(rules('fun f(group: android.view.ViewGroup, view: android.view.View, params: android.view.WindowManager.LayoutParams) { with(group) { addView(view, params) }; group.apply { addView(view, params) } }').size,0);
});
test('a foreign module cannot borrow an owner path',()=>{
 const path='foreign/src/main/java/io/github/anastaci1a/localica/ui/GlassPopupHome.kt';
 assert.ok(checkPopupSource(path,'fun steal() { glassPopupProjection() }').some(i=>i.rule==='ScreenPopupHome'));
});
test('the material owner may bind a surface anchor and screens may read it',()=>{
 const path=screen.replace('Screen.kt','LiquidGlass.kt');
 assert.deepEqual(checkPopupSource(path,'fun LiquidGlassSurface(modifier: SurfaceModifier) { modifier.glassSurfaceAnchor(null, null) }'),[]);
 assert.equal(rules('fun f(modifier: SurfaceModifier) { modifier.glassSurfaceAnchor() }').size,0);
});
test('only the shell may provide popup speed or supply home chrome',()=>{
 const path=screen.replace('Screen.kt','LocalicaScreen.kt');
 assert.deepEqual(checkPopupSource(path,'fun f() { CompositionLocalProvider(LocalMotionSpeed provides 2f) {} }'),[]);
 assert.deepEqual(checkPopupSource(path,'fun f(home: GlassPopupHome) { GlassPopupHome(home, chrome={}) {} }'),[]);
 assert.equal(rules('fun f() { println(LocalMotionSpeed.current) }').size,0);
});
test('ordinary Toast text and unrelated view properties remain allowed',()=>{
 assert.equal(rules('fun f(ctx: Context) { android.widget.Toast.makeText(ctx, "Message", 0).show() }').size,0);
 assert.equal(rules('fun f(model: Model, view: View) { model.view=view; model.setView(view) }').size,0);
});
test('obsolete argument permissions cannot widen callers',()=>{
 assert.equal(ownsPopups(screen.replace('Screen.kt','PlayerSurface.kt'),'PopupArgument.originPreparation'),false);
 assert.equal(ownsPopups(screen.replace('Screen.kt','GlassMorph.kt'),'PopupArgument.sharedContent'),false);
 for(const name of ['GlassMorph','PlayerSurface'])assert.equal(ownsPopups(screen.replace('Screen.kt',name+'.kt'),'PopupArgument.contentAlpha'),false);
});

test('motion providers permit every operator only at the exact shared ingress',()=>{
 for(const [local,owner] of [['LocalUiSpec','LocalicaUiRoot'],['LocalReducedMotion','LocalicaScreen'],['LocalMotionSpeed','LocalicaScreen'],['LocalEngineContext','layout/LocalicaEngineRoot'],['LocalUiClock','engine/UiClock'],['LocalClockOwner','engine/UiClock'],['LocalPopupKind','GlassMorph'],['LocalRespectSystemAnimationScale','LocalicaScreen']]) {
  for(const op of ['provides','providesDefault','providesComputed'])for(const dot of [false,true]) {
   const expression=dot?local+'.'+op+'(value)':local+' '+op+' value';
   const source='fun f() { CompositionLocalProvider('+expression+') {} }';
   assert.deepEqual(checkPopupSource(screen.replace('Screen.kt',owner+'.kt'),source),[]);
   assert.ok(rules(source).has('ScreenPopupMotion'));
   assert.equal(rules('fun f() { println('+local+'.current) }').size,0);
  }
 }
});
test('the explicit Browser provider debt is counted, bounded and removable',()=>{
 const path=screen.replace('Screen.kt','BrowserScreen.kt');
 const expression='LocalReducedMotion provides loadingReducedMotion';
 const source='fun f() { CompositionLocalProvider('+expression+') {} }';
 const audit=auditPopupTree([{path,source}]);
 assert.deepEqual(audit.incidents,[]);
 assert.equal(audit.ratchetIncidents.length,1);
 assert.equal(audit.ratchetIncidents[0].rule,'ScreenPopupMotion');
 assert.equal(audit.ratchetIncidents[0].issue,895);
 assert.equal(popupRatchet.length,1);
 assert.equal(popupRatchet[0].limit,1);
 assert.equal(auditPopupTree([{path,source:'fun f() {}'}]).ratchetIncidents.length,0);
 assert.equal(checkPopupSource(path,'fun f() { CompositionLocalProvider('+expression+', '+expression+') {} }').length,1);
 assert.ok(checkPopupSource(path,source.replace('loadingReducedMotion','true')).length);
});
test('Navigation alone supplies pill shadow inputs',()=>{
 const source='fun f(anchor: GlassAnchor) { PopupActionPill(anchor, chromeVisibility={ 1f }, playerExpansion=sheet.expansion) }';
 assert.deepEqual(checkPopupSource(screen.replace('Screen.kt','LocalicaScreen.kt'),source),[]);
 assert.ok(rules(source).has('ScreenPopupMotion'));
});

test('only the exact sanctioned hosts invoke motion ingress',()=>{
 for(const owner of ['app/src/main/java/io/github/anastaci1a/localica/MainActivity.kt',screen.replace('Screen.kt','LocalicaScreen.kt')]) {
  assert.deepEqual(checkPopupSource(owner,'fun f() { io.github.anastaci1a.localica.ui.LocalicaUiRoot {} }'),[]);
  assert.deepEqual(checkPopupSource(owner,'import io.github.anastaci1a.localica.ui.LocalicaUiRoot as Root\nfun f() { Root {} }'),[]);
 }
 assert.deepEqual(checkPopupSource(screen.replace('Screen.kt','LocalicaUiRoot.kt'),'fun f() { io.github.anastaci1a.localica.ui.layout.ProvideLocalicaEngineContext(120f, 100f, spec, 1f) {} }'),[]);
 assert.equal(rules('import other.LocalicaUiRoot\nfun f() { LocalicaUiRoot {} }').size,0);
});

test('ordinary Object member names cannot become motion ingress',()=>{assert.equal(rules('fun f(value: Any) { value.toString(); value.hashCode() }').size,0);});

test('shared Navigation pill keeps its page shadow on the anchor-bearing modifier',()=>{
 const source=fs.readFileSync(new URL('../../app/src/main/java/io/github/anastaci1a/localica/ui/GlassMorph.kt',import.meta.url),'utf8');
 const body=kotlinTokens(source.slice(source.indexOf('internal fun PopupActionPill(')));
 const calls=body.flatMap((t,i)=>t.text==='pageGlassCaster'?[i]:[]);
 assert.equal(calls.length,1,'the shared pill has one page-shadow binding');
 const at=calls[0];
 assert.equal(body[at-1].text,'.');
 const receiver=body[at-2].text;
 assert.notEqual(receiver,'Modifier','a fresh Modifier has no anchor marker for pageGlassCaster to resolve');
 const declaration=body.findIndex((t,i)=>t.text==='val' && body[i+1]?.text===receiver && body[i+2]?.text==='=');
 assert.ok(declaration>=0,'the page-shadow receiver has a bound modifier declaration');
 assert.deepEqual(body.slice(declaration+3,declaration+9).map(t=>t.text),['modifier','.','glassAnchor','(','anchor',','],
  'the page-shadow receiver retains the modifier that installs this anchor');
});

test('popup return reference binds the captured home to native presenters',()=>{
 const source=fs.readFileSync(new URL('../../app/src/androidTest/java/io/github/anastaci1a/localica/PopupBrowseReturnReferenceHost.kt',import.meta.url),'utf8');
 const requireHome=body=>{
  const words=kotlinTokens(body).map(t=>t.text);
  const shared=words.some((word,i)=>word==='GlassPopupHome' && words[i+1]==='(' && words[i+2]==='home');
  const provided=words.some((word,i)=>word==='CompositionLocalProvider' && words[i+1]==='(' &&
   words[i+2]==='LocalGlassPopupHome' && words[i+3]==='provides' && words[i+4]==='home');
  assert.ok(shared || provided,'the captured home must be provided to its popup');
 };
 requireHome(source);
 const missing=source.replace('GlassPopupHome(home, chrome = chrome, content = content)',
  'Box(Modifier.fillMaxSize().glassPopupHome(home)) { content(); chrome() }');
 assert.notEqual(missing,source);
 assert.throws(()=>requireHome(missing),/captured home must be provided/);
});

function actualPopupReturnPolicy() {
 const source=fs.readFileSync(new URL('../../app/src/main/java/io/github/anastaci1a/localica/ui/GlassMorph.kt',import.meta.url),'utf8');
 const semantics=source.match(/val returningSemantics = ([^\n]+)\n/)?.[1];
 const inline=source.match(/val returningInline = ([\s\S]*?)\n    @Suppress/)[1].trim();
 const clear=source.slice(source.indexOf('val admittedDraw')).match(/\.then\(if \((.*?)\) Modifier\.clearAndSetSemantics/)[1];
 return Function('popupHome','requestedVisible','popupFlight','anchor',
  (semantics ? 'const returningSemantics = '+semantics+'; ' : '')+
  'const returningInline = '+inline+'; return {inline:returningInline, semantics:('+clear+')};');
}

test('home-less chrome closes preserve the published native semantics policy',()=>{
 const returning=actualPopupReturnPolicy();
 const cases=[
  [null,false,false,true,false,'a chrome trigger without a home remains in its native window'],
  [null,false,false,false,false,'an ordinary trigger without a home keeps native semantics'],
  [null,false,true,false,true,'an existing handoff retains the published return policy'],
  [{departing:false},false,false,true,true,'a hosted chrome trigger keeps the #867 inline return'],
  [{departing:false},true,false,true,false,'an open chrome popup remains native'],
  [{departing:true},true,false,false,true,'a departing page retains its ordinary popup'],
  [{departing:false},false,false,false,false,'an ordinary close waits for a real handoff'],
  [{departing:false},false,true,false,true,'an ordinary handed-off return remains inline'],
 ];
 for(const [home,visible,handedOff,chromeTrigger,expected,message] of cases)
  assert.equal(returning(home,visible,{handedOff},{chromeTrigger}).inline,expected,message);
});

test('hosted chrome preparation preserves the published accessibility timing',()=>{
 const returning=actualPopupReturnPolicy();
 for(const chromeTrigger of [false,true]) {
  assert.equal(returning({departing:false},false,{handedOff:false},{chromeTrigger}).semantics,false,
   'a close before handoff retains the native merged tree');
  assert.equal(returning({departing:false},false,{handedOff:true},{chromeTrigger}).semantics,true,
   'a completed closed handoff removes native semantics');
  assert.equal(returning({departing:false},true,{handedOff:true},{chromeTrigger}).semantics,false,
   'reopening restores native semantics before a new handoff');
  assert.equal(returning({departing:true},true,{handedOff:false},{chromeTrigger}).semantics,true,
   'a departing page keeps the published semantics policy');
  assert.equal(returning(null,false,{handedOff:false},{chromeTrigger}).semantics,false,
   'a home-less close without handoff never clears the native merged tree');
 }
});

function actualPopupDeparturePolicy(source) {
 const expression=source.match(/val departingSource = ([\s\S]*?)\n    \/\/ Navigation keeps/)[1].trim();
 const takeIf=(value,predicate)=>predicate()?value:null;
 const takeUnless=(value,predicate)=>value==null||predicate(value)?null:value;
 const leaf=text=>text.trim().replace(/(anchor\.source|popupHome\?\.backdrop|\(anchor\.chromeTrigger \? anchor\.source : popupHome\?\.backdrop\))\s*\.takeIf \{ ([^}]+) \}\?\.takeUnless\(::retired\)/g,
  (_,value,predicate)=>'takeUnless(takeIf('+value+',()=>('+predicate+')),retired)');
 const branch=expression.match(/^if \(anchor\.chromeTrigger\) \{([\s\S]*?)\} else \{([\s\S]*?)\}$/);
 const executable=branch?'anchor.chromeTrigger ? ('+leaf(branch[1])+') : ('+leaf(branch[2])+')':
  leaf(expression.replace('(if (anchor.chromeTrigger) anchor.source else popupHome?.backdrop)',
   '(anchor.chromeTrigger ? anchor.source : popupHome?.backdrop)'));
 return Function('anchor','popupHome','popupFlight','returningInline','retired','takeIf','takeUnless',
  'return '+executable).bind(null);
}

function actualPopupDeparture(source, chromeTrigger, returningInline, wasProjected, retired=false) {
 const browse={name:'browse',retired}, modal={name:'modal'}, home={backdrop:{name:'home'}};
 const takeIf=(value,predicate)=>predicate()?value:null;
 const takeUnless=(value,predicate)=>value==null||predicate(value)?null:value;
 const departure=actualPopupDeparturePolicy(source)({chromeTrigger,source:browse},home,{wasProjected},returningInline,
  value=>value.retired===true,takeIf,takeUnless);
 const sampling=source.match(/val samplingSource = ([^\n]+)/)[1];
 assert.equal(sampling,'departingSource.takeIf { homeSampleReady } ?: currentSource');
 return {departure,sampling:departure??modal};
}

const popupDepartureSource=()=>fs.readFileSync(new URL('../../app/src/main/java/io/github/anastaci1a/localica/ui/GlassMorph.kt',import.meta.url),'utf8');

test('chrome reopen releases the projected browse departure lease',()=>{
 const source=popupDepartureSource();
 for(const projected of [false,true]) {
  assert.equal(actualPopupDeparture(source,true,false,projected).departure,null,
   'an open chrome popup must release its browse departure lease even after projection');
 }
 assert.equal(actualPopupDeparture(source,true,true,true).departure.name,'browse');
 assert.equal(actualPopupDeparture(source,true,true,false).departure.name,'browse');
});

test('chrome reopen samples the current modal after an interrupted projected return',()=>{
 const source=popupDepartureSource();
 assert.match(source,/SideEffect \{ if \(!returningInline\) \{ popupFlight.prepared = false; popupFlight.handedOff = false \} \}/);
 assert.equal(actualPopupDeparture(source,true,false,true).sampling.name,'modal',
  'the reopened modal must regain its sampling consumer after the handoff resets');
 assert.equal(actualPopupDeparture(source,true,true,true,true).departure,null);
});

test('ordinary popup departure retains the published source latch byte for byte',()=>{
 const source=popupDepartureSource();
 for(const inline of [false,true]) for(const projected of [false,true]) {
  const actual=actualPopupDeparture(source,false,inline,projected).departure;
  assert.equal(actual?.name??null,inline||projected?'home':null);
 }
});

test('#854: only the motion spec and the motion owners name popup kinds',()=>{
 const spec='app/src/main/java/io/github/anastaci1a/localica/ui/spec/UiSpec.kt';
 for(const body of ['val m=mapOf(PopupKind.Menu to 1)','val all=PopupKind.entries','import io.github.anastaci1a.localica.ui.spec.PopupKind as K\nval k=K.BottomMenu','val k=io.github.anastaci1a.localica.ui.spec.PopupKind.Dialog']) {
  assert.deepEqual(checkPopupSource(spec,body),[],body);
  for(const caller of ['Screen','LocalicaScreen','QueueScreen','FloatingSelectionPanel','LocalicaUiRoot','InterfaceMotion','UiSpec','layout/UiSpec','engine/UiSpec','spec/UiSpecs'])
   assert.ok(checkPopupSource(screen.replace('Screen.kt',caller+'.kt'),body).some(i=>i.rule==='ScreenPopupMotion'),caller+': '+body);
 }
 assert.ok(rules('import io.github.anastaci1a.localica.ui.spec.PopupKind\nfun f() {}').has('ScreenPopupMotion'));
 // The pin admits kind names only: the spec stays a non-owner for every other motion check.
 for(const body of ['fun f() { rememberPopupMotion(key) }','fun f() { GlassMenu(open, anchor, {}, motionKind = k) {} }','fun f() { rememberFaceExpansion(open, law = { it.expansion }) }',
  'fun f() { CompositionLocalProvider(LocalPopupKind provides k) {} }','fun f() { CompositionLocalProvider(LocalRespectSystemAnimationScale provides false) {} }'])
  assert.ok(checkPopupSource(spec,body).some(i=>i.rule==='ScreenPopupMotion'),body);
});
test('#854/#862: the popup kind and respected-scale locals have one provider each',()=>{
 const glass=screen.replace('Screen.kt','GlassMorph.kt'),root=screen.replace('Screen.kt','LocalicaScreen.kt');
 const kind='fun f() { CompositionLocalProvider(LocalPopupKind provides k) {} }',respect='fun f() { CompositionLocalProvider(LocalRespectSystemAnimationScale provides false) {} }';
 assert.deepEqual(checkPopupSource(glass,kind),[]);assert.deepEqual(checkPopupSource(root,respect),[]);
 assert.ok(checkPopupSource(root,kind).some(i=>i.rule==='ScreenPopupMotion'));
 for(const caller of ['Screen','QueueScreen','BrowserScreen','PlayerSurface']) for(const body of [kind,respect,
  'import io.github.anastaci1a.localica.ui.engine.LocalPopupKind as Kind\nfun f() { CompositionLocalProvider(Kind provides k) {} }',
  'fun f() { val local=LocalRespectSystemAnimationScale; CompositionLocalProvider(local provides false) {} }',
  'fun f() { CompositionLocalProvider(io.github.anastaci1a.localica.ui.engine.LocalPopupKind.provides(k)) {} }'])
  assert.ok(checkPopupSource(screen.replace('Screen.kt',caller+'.kt'),body).some(i=>i.rule==='ScreenPopupMotion'),caller+': '+body);
 assert.equal(rules('fun f() { println(LocalPopupKind.current); println(LocalRespectSystemAnimationScale.current) }').size,0);
});
test('#854 review F4: an argument outside a pinned shared API surface is red, with the extended declaration in the tree',()=>{
 const glass=screen.replace('Screen.kt','GlassMorph.kt'),head='package io.github.anastaci1a.localica.ui\n';
 // Astra's witness: a scalar timing input added to the real shared declaration and used instead of LocalMotionSpeed.
 const shared=head+'internal fun GlassMorphPopup(visible: Boolean, anchor: GlassAnchor, onDismiss: () -> Unit, placement: GlassPlacement = GlassPlacement.Menu, darkOverride: Boolean? = null, fitContent: Boolean = true, originPreparation: GlassOriginPreparation? = null, searchDismissOnImeBack: Boolean = false, sharedContent: PopupSharedContent? = null, heightSizing: GlassDialogHeight = GlassDialogHeight.Content, animationSpeed: Float? = null, content: @Composable () -> Unit) { val speed = animationSpeed ?: LocalMotionSpeed.current; motion.updateSpeed(speed) }';
 for(const call of ['GlassMorphPopup(true, anchor, {}, animationSpeed = 0.5f) { }','GlassMorphPopup(true, anchor, {}, GlassPlacement.Menu, null, true, null, false, null, GlassDialogHeight.Content, 0.5f) { }',
  'GlassMenu(true, anchor, {}, speed = 0.5f) { }','PopupActionPill(anchor, duration = 120) { }','GlassPopupHome(home, timeScale = 2f) { }']) {
  const incidents=auditPopupTree([{path:glass,source:shared},{path:screen,source:head+'fun f(anchor: GlassAnchor, home: Any) { '+call+' }'}]).incidents;
  assert.ok(incidents.some(i=>i.rule==='ScreenPopupMotion' && i.path===screen && /pinned surface/.test(i.note)),call+': '+JSON.stringify(incidents));
 }
 // Honest callers stay green against the extended and the real declaration, including a positional comparison (a == b).
 const honest=head+'fun f(anchor: GlassAnchor, open: Int?, filter: Int) { GlassMorphPopup(true, anchor, {}, placement = GlassPlacement.Center, darkOverride = false) { }; GlassMenu(open == filter, anchor, { }) { } }';
 assert.deepEqual(auditPopupTree([{path:glass,source:shared},{path:screen,source:honest}]).incidents,[]);
 assert.deepEqual(checkPopupSource(screen,honest),[]);
});
test('#854 review F3: rememberEffectiveReducedMotion takes only the app setting; a re-added policy slot is red when a screen passes it',()=>{
 const owner=screen.replace('Screen.kt','InterfaceMotion.kt'),head='package io.github.anastaci1a.localica.ui\n';
 const readded=head+'@Composable internal fun rememberEffectiveReducedMotion(appReducedMotion: Boolean, respectSystemScale: Boolean = true): Boolean = appReducedMotion';
 for(const call of ['rememberEffectiveReducedMotion(r, respectSystemScale = false)','rememberEffectiveReducedMotion(r, false)']) {
  for(const tree of [[{path:owner,source:readded}],[]])
   assert.ok(auditPopupTree([...tree,{path:screen,source:head+'@Composable fun f(r: Boolean) { val reduced = '+call+' }'}]).incidents.some(i=>i.rule==='ScreenPopupMotion' && i.path===screen && /pinned surface/.test(i.note)),call);
 }
 for(const caller of ['BrowserScreen','TransportButton','SeekCancelTarget','FloatingControlMotion','LocalicaScreen'])
  assert.deepEqual(checkPopupSource(screen.replace('Screen.kt',caller+'.kt'),head+'@Composable fun f(reducedMotion: Boolean) { val reduced = rememberEffectiveReducedMotion(reducedMotion || LocalReducedMotion.current) }'),[],caller);
});
test('#854 review F4: every pinned shared API declaration equals its surface row',()=>{
 for(const [api,spec] of popupApiSurface) {
  const declared=declaredFunctions(spec.file,fs.readFileSync(new URL('../../'+spec.file,import.meta.url),'utf8')).filter(d=>d.qualified===spec.identity);
  // Overloads (GlassPopupHome) together declare exactly the row; a single declaration also keeps its order (positional binding).
  assert.ok(declared.length>=1,api);
  assert.deepEqual([...new Set(declared.flatMap(d=>d.parameters))].sort(),[...spec.parameters].sort(),api+' changed its formals: review the new argument and update popup-api.txt');
  if(declared.length===1)assert.deepEqual(declared[0].parameters,spec.parameters,api);
  assert.ok(spec.motion.every(m=>spec.parameters.includes(m)),api);
 }
});
test('#862 / #854 review F4: only the window motion scale owner provides a MotionDurationScale; reading it stays allowed',()=>{
 const owner=screen.replace('Screen.kt','InterfaceMotion.kt');
 for(const body of ['fun f() = object : MotionDurationScale { override val scaleFactor = 2f }',
  'class Slow : androidx.compose.ui.MotionDurationScale { override val scaleFactor get() = 2f }',
  'import androidx.compose.ui.MotionDurationScale as Scale\nval s = object : Scale { override val scaleFactor = 2f }',
  'typealias Scale = androidx.compose.ui.MotionDurationScale\nval s = object : Scale { override val scaleFactor = 2f }',
  'import androidx.compose.ui.*\nsuspend fun f() = withContext(object : MotionDurationScale { override val scaleFactor = 0f }) { }',
  'val key = MotionDurationScale.Key','val read = MotionDurationScale::scaleFactor','val all = listOf<MotionDurationScale>()']) {
  assert.ok(rules(body).has('ScreenPopupMotion'),body);
  assert.deepEqual(checkPopupSource(owner,body),[],body);
  // Owning other capabilities (providers, ingress, roles) grants nothing here.
  for(const caller of ['LocalicaScreen','LocalicaUiRoot','QueueScreen','BrowserScreen'])
   assert.ok(checkPopupSource(screen.replace('Screen.kt',caller+'.kt'),body).some(i=>i.rule==='ScreenPopupMotion'),caller+': '+body);
 }
 for(const body of ['import androidx.compose.ui.MotionDurationScale\nsuspend fun f() = currentCoroutineContext()[MotionDurationScale]?.scaleFactor ?: 1f',
  'import androidx.compose.ui.MotionDurationScale as M\nfun f(c: CoroutineContext) = c[M]?.scaleFactor'])
  assert.equal(rules(body).size,0,body);
});
test('#862: only MainActivity installs the window motion scale; its declaration is not an invocation',()=>{
 const main='app/src/main/java/io/github/anastaci1a/localica/MainActivity.kt';
 assert.deepEqual(checkPopupSource(main,'class A : ComponentActivity() { fun start() { installLocalicaWindowMotion(true) } }'),[]);
 for(const body of ['fun f(activity: ComponentActivity) { activity.installLocalicaWindowMotion(false) }','fun ComponentActivity.f() { installLocalicaWindowMotion(false) }',
  'import io.github.anastaci1a.localica.ui.installLocalicaWindowMotion as install\nfun ComponentActivity.f() { install(false) }','val ref = ComponentActivity::installLocalicaWindowMotion'])
  assert.ok(rules(body).has('ScreenPopupMotion'),body);
 assert.deepEqual(checkPopupSource(screen.replace('Screen.kt','InterfaceMotion.kt'),'internal fun ComponentActivity.installLocalicaWindowMotion(respect: Boolean): WindowMotionScale { TODO() }'),[]);
});
test('#854 round 2 G1: WindowMotionScale and the respect binder belong to InterfaceMotion.kt and the root',()=>{
 const owner=screen.replace('Screen.kt','InterfaceMotion.kt'),root=screen.replace('Screen.kt','LocalicaUiRoot.kt');
 for(const body of ['suspend fun f() = withContext(WindowMotionScale(0.5f, true)) { }','@Composable fun f() { val s = remember { WindowMotionScale(0.5f, true) } }',
  'fun f(scale: WindowMotionScale) { scale.platform = 0f }','fun f(scale: WindowMotionScale) { scale.respect = false }',
  'import androidx.compose.ui.MotionDurationScale\nfun f(c: CoroutineContext) { (c[MotionDurationScale] as WindowMotionScale).respect = false }',
  'import io.github.anastaci1a.localica.ui.WindowMotionScale as S\nval s = S(2f, true)','val make = ::WindowMotionScale']) {
  assert.ok(rules(body).has('ScreenPopupMotion'),body);
  assert.deepEqual(checkPopupSource(owner,body),[],body);
 }
 for(const body of ['@Composable fun f() { val context = rememberCoroutineScope().coroutineContext; SideEffect { context.bindRespectSystemAnimationScale(false) } }',
  'fun f(c: CoroutineContext) { c.bindRespectSystemAnimationScale(respect = false) }','suspend fun f() { currentCoroutineContext().bindRespectSystemAnimationScale(false) }',
  'import io.github.anastaci1a.localica.ui.bindRespectSystemAnimationScale as bind\nfun f(c: CoroutineContext) { c.bind(false) }','val ref = CoroutineContext::bindRespectSystemAnimationScale'])
  for(const caller of ['Screen','LocalicaScreen','BrowserScreen']) assert.ok(checkPopupSource(screen.replace('Screen.kt',caller+'.kt'),body).some(i=>i.rule==='ScreenPopupMotion'),caller+': '+body);
 // The root binds; its declaration is not an invocation; the readers and the root's platform read stay green.
 assert.deepEqual(checkPopupSource(root,'@Composable fun f(respect: Boolean) { val windowContext = rememberCoroutineScope().coroutineContext; val platformScale = windowContext.platformDurationScale; SideEffect { windowContext.bindRespectSystemAnimationScale(respect) } }'),[]);
 assert.deepEqual(checkPopupSource(owner,'internal fun CoroutineContext.bindRespectSystemAnimationScale(respect: Boolean) { (this[MotionDurationScale] as? WindowMotionScale)?.respect = respect }'),[]);
});
test('#854 round 2 G2: presence, face and shared-content factories are pinned; a shared-content handle exposes only its slots',()=>{
 const root='app/src/main/java/io/github/anastaci1a/localica/ui/',head='package io.github.anastaci1a.localica.ui\nimport androidx.compose.runtime.*\nimport io.github.anastaci1a.localica.ui.components.*\n';
 const live=p=>fs.readFileSync(new URL('../../'+root+p,import.meta.url),'utf8');
 const change=(s,a,b)=>{assert.ok(s.includes(a),a);return s.replace(a,b);};
 // The blind recheck's three realistic variants, each with its real shared file evolved in memory.
 const variants=[
  ['components/Presence.kt',s=>change(change(s,'shown: Boolean, role:','shown: Boolean, animationSpeed: Float = 1f, role:'),'val presence = remember(owner)','SideEffect { owner.updateSpeed(animationSpeed) }\n    val presence = remember(owner)'),
   '@Composable fun Screen(key: Any, shown: Boolean) { rememberPresence(key, shown, animationSpeed = 0.5f) }'],
  ['components/MorphFaces.kt',s=>change(change(s,'fun rememberFaceExpansion(expanded: Boolean, role:','fun rememberFaceExpansion(expanded: Boolean, animationSpeed: Float = 1f, role:'),'SideEffect { face.request(expanded) }','SideEffect { motion.expansion.updateSpeed(animationSpeed); face.request(expanded) }'),
   '@Composable fun Screen(expanded: Boolean) { rememberFaceExpansion(expanded, animationSpeed = 0.5f) }'],
  ['components/PopupSharedContent.kt',s=>s+'\ninternal fun PopupSharedContent.setAnimationSpeed(speed: Float) { motion.updateSpeed(speed) }\n',
   '@Composable fun Screen(key: Any) { val shared = rememberPopupSharedContent(key) {}; SideEffect { shared.setAnimationSpeed(0.5f) } }']];
 for(const [path,mutate,call] of variants) {
  const incidents=auditPopupTree([{path:root+path,source:mutate(live(path))},{path:screen,source:head+call}]).incidents;
  assert.ok(incidents.some(i=>i.rule==='ScreenPopupMotion' && i.path===screen),call+': '+JSON.stringify(incidents));
 }
 for(const body of ['fun f(shared: PopupSharedContent) { shared.motion.updateSpeed(0.5f) }','fun f(shared: PopupSharedContent?) { shared?.setAnimationSpeed(0.5f) }',
  'fun f(shared: PopupSharedContent) { with(shared) { setAnimationSpeed(0.5f) } }','fun f(shared: PopupSharedContent) { shared.apply { setAnimationSpeed(0.5f) } }',
  // Round 4 (F8-3): run and let reach the handle too.
  'fun f(shared: PopupSharedContent) { shared.run { setAnimationSpeed(0.5f) } }','fun f(shared: PopupSharedContent) { shared.let { it.setAnimationSpeed(0.5f) } }',
  'internal val LocalShared = staticCompositionLocalOf<PopupSharedContent?> { null }\n@Composable fun f() { LocalShared.current?.setAnimationSpeed(0.5f) }'])
  assert.ok(rules(head+body).has('ScreenPopupMotion'),body);
 // Honest: the queue selector's slot use, through the remembered handle and a composition local.
 assert.deepEqual(checkPopupSource(screen,head+'@Composable fun f(key: Any, copy: Boolean) { val nameMorph = rememberPopupSharedContent(key) {}; if (copy) nameMorph.CompactCopy() else nameMorph.Compact() }'),[]);
 assert.deepEqual(checkPopupSource(screen,head+'internal val LocalShared = staticCompositionLocalOf<PopupSharedContent?> { null }\n@Composable fun g(live: Boolean) { val sharedName = LocalShared.current; if (sharedName != null) { if (live) sharedName.Expanded() else sharedName.ExpandedCopy() } }'),[]);
});
test('#854 round 3 R2-1: X1-X4, a timing formal on rememberGlassAnchor, GlassDialog, ActionIcon or Modifier.glassAnchor, is red',()=>{
 const root='app/src/main/java/io/github/anastaci1a/localica/ui/',head='package io.github.anastaci1a.localica.ui\nimport androidx.compose.runtime.*\nimport androidx.compose.ui.Modifier\n';
 const live=p=>fs.readFileSync(new URL('../../'+root+p,import.meta.url),'utf8');
 const change=(s,a,b)=>{assert.ok(s.includes(a),a);return s.replace(a,b);};
 for(const [label,path,mutate,call] of [
  ['X1 rememberGlassAnchor','GlassMorph.kt',s=>change(s,'clearance: androidx.compose.ui.unit.Dp? = null): GlassAnchor {','clearance: androidx.compose.ui.unit.Dp? = null, animationSpeed: Float = 1f): GlassAnchor {'),'@Composable fun Screen() { val anchor = rememberGlassAnchor(animationSpeed = 0.5f) }'],
  ['X2 GlassDialog','GlassDialogs.kt',s=>change(s,'heightSizing: GlassDialogHeight = GlassDialogHeight.Content, content: @Composable () -> Unit) {','heightSizing: GlassDialogHeight = GlassDialogHeight.Content, animationSpeed: Float = 1f, content: @Composable () -> Unit) {'),'@Composable fun Screen() { GlassDialog({}, animationSpeed = 0.5f) {} }'],
  ['X3 ActionIcon','InterfaceComponents.kt',s=>change(s,'glyph: (@Composable (Modifier) -> Unit)? = null,','glyph: (@Composable (Modifier) -> Unit)? = null, animationSpeed: Float = 1f,'),'@Composable fun Screen(icon: AppIcon) { ActionIcon(icon, "Play", animationSpeed = 0.5f) {} }'],
  ['X4 Modifier.glassAnchor','GlassMorph.kt',s=>change(s,'    face: (@Composable () -> Unit)? = null): Modifier {','    face: (@Composable () -> Unit)? = null, animationSpeed: Float = 1f): Modifier {'),'@Composable fun Screen(anchor: GlassAnchor) { Box(Modifier.fillMaxSize().glassAnchor(anchor, animationSpeed = 0.5f)) }']]) {
  const incidents=auditPopupTree([{path:root+path,source:mutate(live(path))},{path:screen,source:head+call}]).incidents;
  assert.ok(incidents.some(i=>i.rule==='ScreenPopupMotion' && i.path===screen && /pinned surface/.test(i.note)),label+': '+JSON.stringify(incidents));
 }
 // Honest twins on the real declarations.
 assert.deepEqual(checkPopupSource(screen,head+'@Composable fun Screen(icon: AppIcon) { val anchor = rememberGlassAnchor(role = TriggerRole.Surface); GlassDialog({}, placement = GlassPlacement.Center) {}; ActionIcon(icon, "Play", anchor = anchor) {}; Box(Modifier.glassAnchor(anchor, face = null)) }').filter(i=>/pinned surface/.test(i.note)),[]);
});
test('#854 round 3 R2-1: every shared entry point other files call has a popup-api.txt row or an exclusion with a reason',()=>{
 const top=new URL('../..',import.meta.url).pathname;
 const listed=fs.readdirSync(top+'app/src/main/java',{recursive:true}).map(p=>'app/src/main/java/'+p).filter(p=>p.endsWith('.kt'));
 const files=listed.map(path=>({path,source:fs.readFileSync(new URL('../../'+path,import.meta.url),'utf8')}));
 const entries=files.filter(f=>sharedPopupFile(f.path)).flatMap(f=>sharedEntryPoints(f.path,f.source));
 const declared=new Set(entries.map(e=>e.name+'|'+e.file));
 const rows=new Set([...popupApiSurface].map(([api,spec])=>api+'|'+spec.file));
 for(const e of popupApiExclusions) {
  assert.ok(declared.has(e.name+'|'+e.file),'stale exclusion '+e.name+' '+e.file);
  assert.ok(e.reason && e.reason.length>10,'exclusion needs a reason: '+e.name);
  assert.ok(!rows.has(e.name+'|'+e.file),'both a row and an exclusion: '+e.name);
 }
 assert.deepEqual(auditPopupTree(files).incidents.filter(i=>/Unclassified/.test(i.note)),[]);
 // A new shared entry point fails until it is classified; its declaration and owner-side calls do not.
 const component='app/src/main/java/io/github/anastaci1a/localica/ui/components/PopupTiming.kt';
 const added=[{path:component,source:'package io.github.anastaci1a.localica.ui.components\n@Composable fun rememberPopupTiming(speed: Float): Float = speed\ninternal fun Modifier.popupSpeed(speed: Float) = this\nprivate fun hidden() = 1\nfun inside() { rememberPopupTiming(1f) }'},
  {path:screen,source:'package io.github.anastaci1a.localica.ui\nimport io.github.anastaci1a.localica.ui.components.*\n@Composable fun Screen() { val s = rememberPopupTiming(0.5f); Box(Modifier.popupSpeed(0.5f)); val r = ::rememberPopupTiming }'}];
 const red=auditPopupTree(added).incidents.filter(i=>/Unclassified/.test(i.note));
 assert.equal(red.length,3,JSON.stringify(red));assert.ok(red.every(i=>i.path===screen));
 // Round 4 (F8-2): a fully qualified call counts (GlassOriginPreparation.kt called the engine this way); a member call of the same
 // name on another receiver does not.
 const qualified=auditPopupTree([added[0],{path:screen,source:'package io.github.anastaci1a.localica.ui\n@Composable fun Screen(other: Timing) { val s = io.github.anastaci1a.localica.ui.components.rememberPopupTiming(0.5f); other.rememberPopupTiming(1f) }'}])
  .incidents.filter(i=>/Unclassified/.test(i.note));
 assert.equal(qualified.length,1,JSON.stringify(qualified));
 assert.deepEqual(sharedEntryPoints(component,added[0].source).map(e=>e.name+(e.extension?'+':'')),['rememberPopupTiming','popupSpeed+','inside']);
});

test('#885 shared sheet routing admits its owners and rejects other direct, aliased and qualified callers',()=>{
 const root='app/src/main/java/io/github/anastaci1a/localica/ui/';
 for(const [owner,api,call] of [
  ['LocalicaScreen.kt','Sheet','Sheet(state, onSettled = {}, returningChild = child) {}'],
  ['PlayerSurface.kt','sheetDrag','Modifier.sheetDrag(target, onDraggingChanged = {})'],
  ['QueueSurface.kt','verticalSurfaceDrag','Modifier.verticalSurfaceDrag({}, {}, {}, {})']]) {
  const head='package io.github.anastaci1a.localica.ui\nimport io.github.anastaci1a.localica.ui.components.'+api+'\n';
  assert.deepEqual(checkPopupSource(root+owner,head+'fun f() { '+call+' }'),[]);
  for(const [imports,body] of [[head,call],
   [head+'import io.github.anastaci1a.localica.ui.components.'+api+' as route\n',call.replace(api,'route')],
   [head,call.replace(api,api==='Sheet'?'io.github.anastaci1a.localica.ui.components.Sheet':api).replace('Modifier.','androidx.compose.ui.Modifier.')]])
   assert.ok(checkPopupSource(screen,imports+'fun f() { '+body+' }').some(i=>i.rule==='ScreenPopupMotion' && /pinned callers/.test(i.note)),api+': '+body);
 }
});
