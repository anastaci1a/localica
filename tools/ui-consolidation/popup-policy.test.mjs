import test from 'node:test';
import fs from 'node:fs';
import assert from 'node:assert/strict';
import {checkPopupSource, ownsPopups, popupRules, popupOwners, auditPopupTree, popupRatchet} from './popup-policy.mjs';
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
 for(const [local,owner] of [['LocalUiSpec','LocalicaUiRoot'],['LocalReducedMotion','LocalicaScreen'],['LocalMotionSpeed','LocalicaScreen']]) {
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
