import test from 'node:test';
import fs from 'node:fs';
import assert from 'node:assert/strict';
import {checkPopupSource, ownsPopups, popupRules, popupOwners} from './popup-policy.mjs';
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
test('component entry points and ordinary drawing pass',()=>assert.equal(rules('fun Screen() { Box(Modifier.glassAnchor(anchor, face = { Text(label) })) {}; PopupActionPill(anchor, playerExpansion = sheet.expansion) {}; Box(Modifier.graphicsLayer { alpha = x }) {} }').size,0));
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
