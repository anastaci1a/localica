import test from 'node:test';
import assert from 'node:assert/strict';
import {checkPopupSource, ownsPopups, popupRules, popupOwners} from './popup-policy.mjs';
const screen='app/src/main/java/io/github/anastaci1a/localica/ui/Screen.kt';
const rules=source=>new Set(checkPopupSource(screen,source).map(x=>x.rule));
const fixtures={
 ScreenPopupHome:'fun Screen() { CompositionLocalProvider(LocalGlassPopupHome provides home) {} }',
 ScreenPopupMotion:'fun Screen() { GlassMenu(motionKind = PopupKind.Utility, law = { it.expansion }) }',
 ScreenPopupTrigger:'fun Screen() { val anchor = GlassAnchor(); anchor.face = { Text(label) } }',
 ScreenPopupDrawPath:'import androidx.compose.ui.window.Dialog\nfun Screen() { Dialog(onDismissRequest = {}) {} }',
 DuplicatePopupRole:'class ScreenPopupHome\nval LocalPrivate = staticCompositionLocalOf<GlassPopupHome?> { null }',
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
test('component entry points and ordinary drawing pass',()=>assert.equal(rules('fun Screen() { GlassPopupHome(home) { Box(Modifier.glassAnchor(anchor, face = { Text(label) })) {} }; PopupActionPill(anchor, playerExpansion = sheet.expansion) {}; Box(Modifier.graphicsLayer { alpha = x }) {} }').size,0));
test('native Popup cannot masquerade as the component through an alias',()=>assert.ok(rules('import androidx.compose.ui.window.Popup as Native\nfun f(){ Native {} }').has('ScreenPopupDrawPath')));
test('ownership is pinned per rule, never per package or whole file',()=>{
 const path=screen.replace('Screen.kt','GlassPopupHome.kt');
 assert.ok(ownsPopups(path,'ScreenPopupHome'));
 assert.equal(ownsPopups(path,'ScreenPopupTrigger'),false);
 assert.deepEqual(checkPopupSource(path,fixtures.ScreenPopupHome),[]);
 assert.ok(checkPopupSource(path,fixtures.ScreenPopupTrigger).length>0);
 for(const owner of ['GlassMorph','GlassPopupHome','GlassDialogs']) assert.equal(ownsPopups(screen.replace('Screen.kt',owner+'Copy.kt')),false);
});
test('a second trigger type outside the owner list fails',()=>assert.ok(rules('class AlternativePopupTrigger').has('DuplicatePopupRole')));

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
 assert.ok(checkPopupSource(path,'class SecondPopupHome').some(x=>x.rule==='DuplicatePopupRole'));
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
 assert.equal(rules('fun f(){ PopupActionPill(anchor, onMore = { menu = true }); glassAnchor(anchor, face = { CheckBox(checked = true) }); rememberPresence(key, shown = true, role = TriggerRole.Lyrics) }').size,0);
});
