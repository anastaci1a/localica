package io.github.anastaci1a.localica.lint
import com.android.tools.lint.checks.infrastructure.LintDetectorTest
import com.android.tools.lint.checks.infrastructure.TestFiles.kotlin
import com.android.tools.lint.checks.infrastructure.TestMode
import com.android.tools.lint.detector.api.Detector
class PopupGateRedTeamTest : LintDetectorTest() {
 override fun getDetector(): Detector = PopupPolicyDetector()
 override fun getIssues() = LocalicaIssueRegistry().issues.filter { it.implementation.detectorClass == PopupPolicyDetector::class.java }
 private val stubs = listOf(
  kotlin("src/main/java/io/github/anastaci1a/localica/ui/GlassMorph.kt", """package io.github.anastaci1a.localica.ui
open class GlassAnchor { var face: (() -> Unit)? = null; val triggerRole: Any? = null; val contact = GlassContact(); val popupHome: GlassPopupHome? = GlassPopupHome() }
class GlassContact { var modal=false; val state=GlassBodyState(); val expansion=GlassExpansion() }
class GlassBodyState { var impulseScale=1f; val output=MutableOutput(); val momentum=GlassMomentum() }
class MutableOutput { var value=1f }
class SurfaceModifier
fun SurfaceModifier.glassSurfaceAnchor(source: Any?, cornerRadius: Any?): GlassAnchor? = null
fun SurfaceModifier.glassSurfaceAnchor(): GlassAnchor? = null
fun PopupActionPill(anchor: GlassAnchor, source: Any? = null, modifier: Any? = null, noneAtlasInput: Any? = null, chromeVisibility: () -> Float = { 1f }, playerExpansion: Any? = null) {}
class GlassShape
class GlassExpansion { fun bind() {} }
class GlassMomentum { var damping=1f; fun reset() {} }

fun rememberGlassPopupHome(visible: Boolean = false) = GlassPopupHome()
class PopupSharedContent
class GlassOriginPreparation
fun GlassMenu(visible: Boolean, anchor: GlassAnchor, onDismiss: () -> Unit, content: () -> Unit) {}
fun GlassMorphPopup(visible: Boolean, anchor: GlassAnchor, onDismiss: () -> Unit, placement: Int = 0, darkOverride: Boolean? = null, fitContent: Boolean = false, originPreparation: GlassOriginPreparation? = null, searchDismissOnImeBack: Boolean = false, sharedContent: PopupSharedContent? = null, content: () -> Unit) { content() }
""").indented(),
  kotlin("src/main/java/io/github/anastaci1a/localica/ui/GlassPopupHome.kt", """package io.github.anastaci1a.localica.ui
class GlassPopupHome { var mounted=true; val flights=mutableListOf<GlassPopupFlight>(); val children=mutableListOf<GlassPopupHome>() }
class GlassPopupFlight { var phase: () -> Float = { 0f } }
fun GlassPopupHome(home: GlassPopupHome, chrome: (() -> Unit)? = null, container: (() -> Unit) -> Unit = { it() }, modifier: Any? = null, content: () -> Unit) {}
""").indented(),
  kotlin("src/main/java/io/github/anastaci1a/localica/ui/components/TriggerRole.kt", """package io.github.anastaci1a.localica.ui.components
sealed interface TriggerRole { data object Selection: TriggerRole }
""").indented(),
  kotlin("src/main/java/androidx/compose/runtime/Remember.kt", """package androidx.compose.runtime
class ProvidedValue<T>
open class ProvidableCompositionLocal<T> { val current: T get() = error("stub"); infix fun provides(value: T) = ProvidedValue<T>(); infix fun providesDefault(value: T) = ProvidedValue<T>(); infix fun providesComputed(value: () -> T) = ProvidedValue<T>() }
fun CompositionLocalProvider(vararg values: ProvidedValue<*>, content: () -> Unit) { content() }
interface MutableState<T> { var value: T }
fun <T> remember(calculation: () -> T): T = calculation()""").indented(),
  kotlin("src/main/java/android/app/Windows.kt", """package android.app
open class Dialog
open class DialogFragment { fun show() {}; fun showNow() {} }
open class AlertDialog : android.app.Dialog() { class Builder }""").indented(),
  kotlin("src/main/java/androidx/activity/Windows.kt", """package androidx.activity
open class ComponentDialog """).indented(),
  kotlin("src/main/java/androidx/fragment/app/Windows.kt", """package androidx.fragment.app
open class DialogFragment { fun show() {}; fun showNow() {} } """).indented(),
  kotlin("src/main/java/android/widget/Windows.kt", """package android.widget
open class PopupWindow
open class ListPopupWindow
open class PopupMenu """).indented(),
  kotlin("src/main/java/androidx/compose/material3/Windows.kt", """package androidx.compose.material3
fun AlertDialog() {}
fun BasicAlertDialog() {}
fun DatePickerDialog() {}
fun TooltipBox() {}
fun ExposedDropdownMenuBox() {}
fun ExposedDropdownMenu() {}
fun SearchBar() {}""").indented(),
  kotlin("src/main/java/android/view/WindowManager.kt", """package android.view
open class View
interface ViewManager { fun addView(view: View, params: WindowManager.LayoutParams) }
interface WindowManager : ViewManager { class LayoutParams }
open class ViewGroup : ViewManager { override fun addView(view: View, params: WindowManager.LayoutParams) {} }""").indented()
 )
 private val wrapperStubs = listOf(
  java("src/main/java/android/widget/Toast.java", """package android.widget;
public class Toast { public Toast() {} public android.view.View getView() { return null; } public void setView(android.view.View v) {} public void show() {} }"""),
  kotlin("src/main/java/io/github/anastaci1a/localica/ui/InterfaceMotion.kt", """package io.github.anastaci1a.localica.ui
val LocalMotionSpeed = androidx.compose.runtime.ProvidableCompositionLocal<Float>()
val LocalReducedMotion = androidx.compose.runtime.ProvidableCompositionLocal<Boolean>()"""),
  kotlin("src/main/java/io/github/anastaci1a/localica/ui/LyricsPanel.kt", """package io.github.anastaci1a.localica.ui
fun LyricsPanel(track: Any? = null, positionMs: Long = 0, reducedMotion: Boolean = false, onSeek: (Long) -> Unit = {}, modifier: Any? = null, sourcesRequest: Int = 0, sourcesAnchor: GlassAnchor? = null, sampledAtMs: Long = 0, playing: Boolean = false, speed: Float = 1f, delayMs: Int = 0, durationMs: Long? = null, contentHeight: Any? = null, contentOffset: Any? = null, result: Any? = null, sourcesVisible: Boolean? = null, onSourcesDismiss: (() -> Unit)? = null, originPreparation: GlassOriginPreparation? = null, contentAlpha: () -> Float = { 1f }) {}"""),
  kotlin("src/main/java/io/github/anastaci1a/localica/ui/LyricsBackdrop.kt", """package io.github.anastaci1a.localica.ui
fun LyricsBackdrop(key: Any?, modifier: Any? = null, contentAlpha: () -> Float = { 1f }, content: () -> Unit) {}"""),
  kotlin("src/main/java/androidx/appcompat/app/AlertDialog.kt", """package androidx.appcompat.app
class AlertDialog : android.app.Dialog() { class Builder }"""),
  kotlin("src/main/java/io/github/anastaci1a/localica/ui/spec/UiSpec.kt", """package io.github.anastaci1a.localica.ui.spec
val LocalUiSpec = androidx.compose.runtime.ProvidableCompositionLocal<Int>()"""),
  kotlin("src/main/java/io/github/anastaci1a/localica/ui/LocalicaUiRoot.kt", """package io.github.anastaci1a.localica.ui
class LocalicaUiRootState(settings: io.github.anastaci1a.localica.navigation.InterfaceSettings)
fun LocalicaUiRoot(state: LocalicaUiRootState? = null, content: () -> Unit) {}"""),
  kotlin("src/main/java/io/github/anastaci1a/localica/ui/layout/LocalicaEngineRoot.kt", """package io.github.anastaci1a.localica.ui.layout
fun ProvideLocalicaEngineContext(refreshHz: Float, referenceTravel: Float, resolvedSpec: Any?, durationScale: Float, content: () -> Unit) {}"""),
  kotlin("src/main/java/io/github/anastaci1a/localica/navigation/InterfaceSettings.kt", """package io.github.anastaci1a.localica.navigation
data class InterfaceSettings(val reducedMotion: Boolean = false)"""),
  kotlin("src/main/java/io/github/anastaci1a/localica/ui/engine/ClockLocals.kt", """package io.github.anastaci1a.localica.ui.engine
data class EngineContext(val durationScale: Float = 1f)
class UiClock
val LocalEngineContext = androidx.compose.runtime.ProvidableCompositionLocal<EngineContext>()
val LocalUiClock = androidx.compose.runtime.ProvidableCompositionLocal<UiClock>()
val LocalClockOwner = androidx.compose.runtime.ProvidableCompositionLocal<UiClock?>()""")
 )
 private fun check(body: String, imports: String = "", name: String = "Subject") {
  val source="package io.github.anastaci1a.localica.ui\nimport androidx.compose.runtime.remember\nimport io.github.anastaci1a.localica.ui.components.TriggerRole\n".replace("\\n", "\n") + imports + "\n".replace("\\n", "\n") + body
  val file=kotlin("src/main/java/io/github/anastaci1a/localica/ui/" + name + ".kt", source)
  lint().projects(project().name("app").files(*(stubs + wrapperStubs + file).toTypedArray())).issues(*getIssues().toTypedArray())
   .allowMissingSdk().testModes(TestMode.DEFAULT).run()
   .expectMatches("(?s).*" + name + "[.]kt:[0-9]+: Error: .*" + Regex.escape("[") + "(?:ScreenPopup[A-Za-z]+|DuplicatePopupRole).*")
 }
 fun testfunction_reference() = check("val factory=::rememberGlassPopupHome", "")
 fun testproperty_reference_and_setter() = check("fun f(anchor: GlassAnchor){ val field=anchor::face; field.set({}) }", "")
 fun testconstructor_reference() = check("val factory=::GlassAnchor", "")
 fun testclass_reference() = check("val type=GlassAnchor::class; val make=type.constructors.first(); val anchor=make.call()", "")
 fun testimport_alias() = check("fun f(){ Make() }", "import io.github.anastaci1a.localica.ui.GlassAnchor as Make\n")
 fun testremember_alias() = check("fun f(anchor: GlassAnchor){ val copied=remember { anchor }; copied.face={} }", "")
 fun testconditional_alias() = check("fun f(anchor: GlassAnchor, other: GlassAnchor, flag: Boolean){ val copied=if(flag) anchor else other; copied.face={} }", "")
 fun testhelper_parameter() = check("fun patch(anchor: GlassAnchor){ anchor.face={} }", "")
 fun teststar_import() = check("fun f(){ GlassAnchor() }", "import io.github.anastaci1a.localica.ui.*\n")
 fun testexecutable_string_template() = check("fun f(){ println(\"\${GlassAnchor()}\") }", "")
 fun testnested_contact_write() = check("fun f(anchor: GlassAnchor){ anchor.contact.state.impulseScale=2f }", "")
 fun testhome_field_write() = check("fun f(anchor: GlassAnchor){ anchor.popupHome!!.mounted=false }", "")
 fun testflight_field_write() = check("fun f(anchor: GlassAnchor){ val flight=anchor.popupHome!!.flights.first(); flight.phase={0f} }", "")
 fun testmutable_home_collection() = check("fun f(anchor: GlassAnchor){ anchor.popupHome!!.flights.clear() }", "")
 fun testpublic_role_getter() = check("val borrowed get()=TriggerRole.Selection", "import io.github.anastaci1a.localica.ui.components.TriggerRole\n")
 fun testandroid_app_Dialog() = check("fun f(){ android.app.Dialog() }", "")
 fun testandroidx_activity_ComponentDialog() = check("fun f(){ androidx.activity.ComponentDialog() }", "")
 fun testandroid_app_DialogFragment() = check("fun f(){ android.app.DialogFragment() }", "")
 fun testandroidx_fragment_app_DialogFragment() = check("fun f(){ androidx.fragment.app.DialogFragment() }", "")
 fun testandroid_widget_PopupWindow() = check("fun f(){ android.widget.PopupWindow() }", "")
 fun testandroid_widget_ListPopupWindow() = check("fun f(){ android.widget.ListPopupWindow() }", "")
 fun testandroid_widget_PopupMenu() = check("fun f(){ android.widget.PopupMenu() }", "")
 fun testandroid_app_AlertDialog() = check("fun f(){ android.app.AlertDialog() }", "")
 fun testandroidx_compose_material3_AlertDialog() = check("fun f(){ androidx.compose.material3.AlertDialog() }", "")
 fun testandroidx_compose_material3_BasicAlertDialog() = check("fun f(){ androidx.compose.material3.BasicAlertDialog() }", "")
 fun testandroidx_compose_material3_DatePickerDialog() = check("fun f(){ androidx.compose.material3.DatePickerDialog() }", "")
 fun testandroidx_compose_material3_TooltipBox() = check("fun f(){ androidx.compose.material3.TooltipBox() }", "")
 fun testandroidx_compose_material3_ExposedDropdownMenuBox() = check("fun f(){ androidx.compose.material3.ExposedDropdownMenuBox() }", "")
 fun testandroidx_compose_material3_SearchBar() = check("fun f(){ androidx.compose.material3.SearchBar() }", "")
 fun testDialog_subclass() = check("class Mine : android.app.Dialog()\nfun f(){ Mine() }", "")
 fun testWindowManager_addView() = check("fun f(wm: android.view.WindowManager, view: android.view.View, params: android.view.WindowManager.LayoutParams){ wm.addView(view, params) }", "")
 fun testshared_content_slot() = check("fun f(anchor: GlassAnchor, shared: PopupSharedContent){ GlassMorphPopup(true, anchor, {}, sharedContent=shared) {} }", "")
 fun testorigin_preparation_slot() = check("fun f(anchor: GlassAnchor, preparation: GlassOriginPreparation){ GlassMorphPopup(true, anchor, {}, originPreparation=preparation) {} }", "")
 fun testopen_container_slot() = check("fun f(container: (() -> Unit) -> Unit){ container { android.app.Dialog() } }", "")
 fun testExposedDropdownMenu() = check("fun f(){ androidx.compose.material3.ExposedDropdownMenu() }", "")
 fun testfile_level_host_pin() = check("fun f(){ rememberGlassPopupHome(); rememberGlassPopupHome(); rememberGlassPopupHome(); rememberGlassPopupHome() }", "", "LocalicaScreen")

 fun testG1_helper_contact() = check("fun f(contact: GlassContact) { contact.modal=true }")
 fun testG1_remember_contact() = check("fun f(anchor: GlassAnchor) { val contact=remember { anchor.contact }; contact.modal=true }")
 fun testG1_conditional_contact() = check("fun f(a: GlassContact, b: GlassContact, flag: Boolean) { val contact=if(flag) a else b; contact.modal=true }")
 fun testG1_body_state() = check("fun f(state: GlassBodyState) { state.impulseScale=2f }")
 fun testG1_output_alias() = check("fun f(state: GlassBodyState) { val output=state.output; output.value=2f }")
 fun testG1_momentum_helper() = check("fun f(momentum: GlassMomentum) { momentum.reset() }")
 fun testG1_expansion_helper() = check("fun f(expansion: GlassExpansion) { expansion.bind() }")
 fun testG2_framework_show() = check("fun f(fragment: android.app.DialogFragment) { fragment.show() }")
 fun testG2_androidx_show() = check("fun f(fragment: androidx.fragment.app.DialogFragment) { fragment.showNow() }")
 fun testG2_framework_subclass() = check("class Mine : android.app.DialogFragment()")
 fun testG2_androidx_subclass() = check("class Mine : androidx.fragment.app.DialogFragment()")
 fun testG2_fragment_reference() = check("val make=::Fragment", "import android.app.DialogFragment as Fragment")
 fun testG2_component_subclass() = check("class Mine : androidx.activity.ComponentDialog()")
 fun testG3_positional_origin() = check("fun f(anchor: GlassAnchor, preparation: GlassOriginPreparation) { GlassMorphPopup(true, anchor, {}, 0, null, false, preparation) {} }")
 fun testG3_alias_shared() = check("fun f(anchor: GlassAnchor, shared: PopupSharedContent) { Morph(true, anchor, {}, sharedContent=shared) {} }", "import io.github.anastaci1a.localica.ui.GlassMorphPopup as Morph")
 fun testG4_modifier_slot() = check("fun f(home: GlassPopupHome) { GlassPopupHome(home, modifier=Any()) {} }", name="LocalicaScreen")
 fun testG4_container_slot() = check("fun f(home: GlassPopupHome) { GlassPopupHome(home, container={}) {} }", name="LocalicaScreen")
 fun testG5_role_getter() = check("fun f(other: GlassAnchor) { println(other.triggerRole) }")
 fun testG5_role_reference() = check("fun f(other: GlassAnchor) { val borrowed=other::triggerRole }")
 fun testG6_addView_reference() = check("fun f(wm: android.view.WindowManager) { val borrowed=wm::addView }")
 fun testG6_addView_alias() = check("fun f(wm: android.view.WindowManager, view: android.view.View, params: android.view.WindowManager.LayoutParams) { val alias=wm; alias.addView(view, params) }")
 fun testF3_role_star() = check("val borrowed=Selection", "import io.github.anastaci1a.localica.ui.components.TriggerRole.*")
 fun testF3_raw_template() = check("fun f() { println(\"\"\"\${GlassAnchor()}\"\"\") }")
 fun testG_class_alias() = check("val borrowed=Anchor::class", "import io.github.anastaci1a.localica.ui.GlassAnchor as Anchor")
 fun testG_native_class_reference() = check("val borrowed=android.app.DialogFragment::class")

 fun testG3_wrapper_alpha() = check("fun f() { LyricsPanel(contentAlpha={ 0f }) }")
 fun testG3_wrapper_origin() = check("fun f(preparation: GlassOriginPreparation) { LyricsPanel(originPreparation=preparation) }")
 fun testG3_backdrop_alpha() = check("fun f() { LyricsBackdrop(null, contentAlpha={ 0f }) {} }")
 fun testG3_wrapper_positional_alpha() = check("fun f() { LyricsPanel(null, 0L, false, {}, null, 0, null, 0L, false, 1f, 0, null, null, null, null, null, null, null, { 0f }) }")
 fun testG4_positional_container() = check("fun f(home: GlassPopupHome) { GlassPopupHome(home, null, {}) {} }", name="LocalicaScreen")
 fun testG_native_framework_builder() = check("fun f() { android.app.AlertDialog.Builder() }")
 fun testG_native_appcompat_builder() = check("fun f() { androidx.appcompat.app.AlertDialog.Builder() }")
 fun testG_native_appcompat_dialog() = check("fun f() { androidx.appcompat.app.AlertDialog() }")

 fun testG1_qualified_contact() = check("fun f(contact: io.github.anastaci1a.localica.ui.GlassContact) { contact.modal=true }")
 fun testG1_typealias_contact() = check("typealias Contact = GlassContact\nfun f(contact: Contact) { contact.modal=true }")
 fun testG1_generic_shape_state() = check("fun f(output: androidx.compose.runtime.MutableState<GlassShape>, shape: GlassShape) { output.value=shape }")
 fun testG3_morph_reference() = check("val borrowed=::GlassMorphPopup")
 fun testG3_lyrics_reference() = check("val borrowed=::LyricsPanel")
 fun testG3_backdrop_reference() = check("val borrowed=::LyricsBackdrop")
 fun testG3_qualified_with_local() = check("fun GlassMorphPopup() {}\nfun f(anchor: GlassAnchor, shared: PopupSharedContent) { io.github.anastaci1a.localica.ui.GlassMorphPopup(true, anchor, {}, sharedContent=shared) {} }")
 fun testG6_with_receiver() = check("fun f(wm: android.view.WindowManager, view: android.view.View, params: android.view.WindowManager.LayoutParams) { with(wm) { addView(view, params) } }")
 fun testG6_apply_receiver() = check("fun f(wm: android.view.WindowManager, view: android.view.View, params: android.view.WindowManager.LayoutParams) { wm.apply { addView(view, params) } }")
 fun testG6_unbound_reference() = check("val borrowed=android.view.WindowManager::addView")

 fun testSanctionedWrappersAndOrdinaryViewInsertion() {
  val file=kotlin("src/main/java/io/github/anastaci1a/localica/ui/PlayerSurface.kt", """package io.github.anastaci1a.localica.ui
fun f(preparation: GlassOriginPreparation, group: android.view.ViewGroup, view: android.view.View, params: android.view.WindowManager.LayoutParams) {
 LyricsPanel(originPreparation=preparation, contentAlpha={ 1f })
 group.addView(view, params)
}
""")
  lint().projects(project().name("app").files(*(stubs.take(4) + stubs.takeLast(1) + wrapperStubs.take(4) + file).toTypedArray()))
   .allowMissingSdk().testModes(TestMode.DEFAULT).run().expectClean()
 }
 fun testSanctionedContactWriter() {
  val file=kotlin("src/main/java/io/github/anastaci1a/localica/ui/GlassSplitContact.kt", """package io.github.anastaci1a.localica.ui
fun f(contact: GlassContact) { contact.modal=true; contact.state.impulseScale=2f; contact.state.momentum.reset() }
""")
  lint().projects(project().name("app").files(*(stubs.take(4) + stubs.takeLast(1) + wrapperStubs.take(4) + file).toTypedArray()))
   .allowMissingSdk().testModes(TestMode.DEFAULT).run().expectClean()
 }

 fun testB1_surface_writer() = check("fun f(modifier: SurfaceModifier) { modifier.glassSurfaceAnchor(null, null) }", "", "Subject")
 fun testB1_surface_alias() = check("fun f(modifier: SurfaceModifier) { modifier.bindSurface(null, null) }", "import io.github.anastaci1a.localica.ui.glassSurfaceAnchor as bindSurface", "Subject")
 fun testB2_speed_provider() = check("fun f() { androidx.compose.runtime.CompositionLocalProvider(LocalMotionSpeed provides 2f) {} }", "", "Subject")
 fun testB2_speed_alias() = check("fun f() { androidx.compose.runtime.CompositionLocalProvider(Speed provides 2f) {} }", "import io.github.anastaci1a.localica.ui.LocalMotionSpeed as Speed", "Subject")
 fun testB3_positional_chrome() = check("fun f(home: GlassPopupHome) { GlassPopupHome(home, {}) {} }", "", "QueueSurface")
 fun testB3_named_chrome() = check("fun f(home: GlassPopupHome) { GlassPopupHome(home, chrome={}) {} }", "", "PlayerSurface")
 fun testB4_toast_view() = check("fun f(view: android.view.View) { android.widget.Toast().apply { this.view = view }.show() }", "", "Subject")
 fun testB4_toast_property() = check("fun f(toast: android.widget.Toast, view: android.view.View) { toast.view = view; toast.show() }", "", "Subject")
 fun testB4_toast_setter() = check("fun f(toast: android.widget.Toast, view: android.view.View) { toast.setView(view); toast.show() }", "", "Subject")
 fun testB5_player_origin() = check("fun f(anchor: GlassAnchor, preparation: GlassOriginPreparation) { GlassMorphPopup(true, anchor, {}, originPreparation=preparation) {} }", "", "PlayerSurface")
 fun testB2_speed_dot() = check("fun f() { androidx.compose.runtime.CompositionLocalProvider(LocalMotionSpeed.provides(2f)) {} }")
 fun testB4_toast_qualified_local() = check("fun f(view: android.view.View) { val toast=android.widget.Toast(); toast.view=view; toast.show() }")
 fun testSanctionedSurfaceWriter() {
  val file=kotlin("src/main/java/io/github/anastaci1a/localica/ui/LiquidGlass.kt", """package io.github.anastaci1a.localica.ui
fun LiquidGlassSurface(modifier: SurfaceModifier) { modifier.glassSurfaceAnchor(null, null); modifier.glassSurfaceAnchor() }
""")
  lint().projects(project().name("app").files(*(stubs.take(4) + stubs.takeLast(1) + wrapperStubs.take(4) + file).toTypedArray()))
   .allowMissingSdk().testModes(TestMode.DEFAULT).run().expectClean()
 }
 fun testSanctionedSpeedProvider() {
  val file=kotlin("src/main/java/io/github/anastaci1a/localica/ui/LocalicaScreen.kt", """package io.github.anastaci1a.localica.ui
fun f() { androidx.compose.runtime.CompositionLocalProvider(LocalMotionSpeed provides 2f) {} }
""")
  lint().projects(project().name("app").files(*(stubs.take(4) + stubs.takeLast(1) + wrapperStubs.take(4) + file).toTypedArray()))
   .allowMissingSdk().testModes(TestMode.DEFAULT).run().expectClean()
 }
 fun testC_provider_LocalMotionSpeed_provides_infix_direct() = check("fun f() { androidx.compose.runtime.CompositionLocalProvider(LocalMotionSpeed provides 2f) {} }", "import io.github.anastaci1a.localica.ui.LocalMotionSpeed", "Subject")
 fun testC_provider_LocalMotionSpeed_provides_infix_import() = check("fun f() { androidx.compose.runtime.CompositionLocalProvider(Policy provides 2f) {} }", "import io.github.anastaci1a.localica.ui.LocalMotionSpeed as Policy", "Subject")
 fun testC_provider_LocalMotionSpeed_provides_infix_local() = check("fun f() { val Policy=LocalMotionSpeed; androidx.compose.runtime.CompositionLocalProvider(Policy provides 2f) {} }", "import io.github.anastaci1a.localica.ui.LocalMotionSpeed", "Subject")
 fun testC_provider_LocalMotionSpeed_provides_dot_direct() = check("fun f() { androidx.compose.runtime.CompositionLocalProvider(LocalMotionSpeed.provides(2f)) {} }", "import io.github.anastaci1a.localica.ui.LocalMotionSpeed", "Subject")
 fun testC_provider_LocalMotionSpeed_provides_dot_import() = check("fun f() { androidx.compose.runtime.CompositionLocalProvider(Policy.provides(2f)) {} }", "import io.github.anastaci1a.localica.ui.LocalMotionSpeed as Policy", "Subject")
 fun testC_provider_LocalMotionSpeed_provides_dot_local() = check("fun f() { val Policy=LocalMotionSpeed; androidx.compose.runtime.CompositionLocalProvider(Policy.provides(2f)) {} }", "import io.github.anastaci1a.localica.ui.LocalMotionSpeed", "Subject")
 fun testC_provider_LocalMotionSpeed_providesDefault_infix_direct() = check("fun f() { androidx.compose.runtime.CompositionLocalProvider(LocalMotionSpeed providesDefault 2f) {} }", "import io.github.anastaci1a.localica.ui.LocalMotionSpeed", "Subject")
 fun testC_provider_LocalMotionSpeed_providesDefault_infix_import() = check("fun f() { androidx.compose.runtime.CompositionLocalProvider(Policy providesDefault 2f) {} }", "import io.github.anastaci1a.localica.ui.LocalMotionSpeed as Policy", "Subject")
 fun testC_provider_LocalMotionSpeed_providesDefault_infix_local() = check("fun f() { val Policy=LocalMotionSpeed; androidx.compose.runtime.CompositionLocalProvider(Policy providesDefault 2f) {} }", "import io.github.anastaci1a.localica.ui.LocalMotionSpeed", "Subject")
 fun testC_provider_LocalMotionSpeed_providesDefault_dot_direct() = check("fun f() { androidx.compose.runtime.CompositionLocalProvider(LocalMotionSpeed.providesDefault(2f)) {} }", "import io.github.anastaci1a.localica.ui.LocalMotionSpeed", "Subject")
 fun testC_provider_LocalMotionSpeed_providesDefault_dot_import() = check("fun f() { androidx.compose.runtime.CompositionLocalProvider(Policy.providesDefault(2f)) {} }", "import io.github.anastaci1a.localica.ui.LocalMotionSpeed as Policy", "Subject")
 fun testC_provider_LocalMotionSpeed_providesDefault_dot_local() = check("fun f() { val Policy=LocalMotionSpeed; androidx.compose.runtime.CompositionLocalProvider(Policy.providesDefault(2f)) {} }", "import io.github.anastaci1a.localica.ui.LocalMotionSpeed", "Subject")
 fun testC_provider_LocalMotionSpeed_providesComputed_infix_direct() = check("fun f() { androidx.compose.runtime.CompositionLocalProvider(LocalMotionSpeed providesComputed { 2f }) {} }", "import io.github.anastaci1a.localica.ui.LocalMotionSpeed", "Subject")
 fun testC_provider_LocalMotionSpeed_providesComputed_infix_import() = check("fun f() { androidx.compose.runtime.CompositionLocalProvider(Policy providesComputed { 2f }) {} }", "import io.github.anastaci1a.localica.ui.LocalMotionSpeed as Policy", "Subject")
 fun testC_provider_LocalMotionSpeed_providesComputed_infix_local() = check("fun f() { val Policy=LocalMotionSpeed; androidx.compose.runtime.CompositionLocalProvider(Policy providesComputed { 2f }) {} }", "import io.github.anastaci1a.localica.ui.LocalMotionSpeed", "Subject")
 fun testC_provider_LocalMotionSpeed_providesComputed_dot_direct() = check("fun f() { androidx.compose.runtime.CompositionLocalProvider(LocalMotionSpeed.providesComputed({ 2f })) {} }", "import io.github.anastaci1a.localica.ui.LocalMotionSpeed", "Subject")
 fun testC_provider_LocalMotionSpeed_providesComputed_dot_import() = check("fun f() { androidx.compose.runtime.CompositionLocalProvider(Policy.providesComputed({ 2f })) {} }", "import io.github.anastaci1a.localica.ui.LocalMotionSpeed as Policy", "Subject")
 fun testC_provider_LocalMotionSpeed_providesComputed_dot_local() = check("fun f() { val Policy=LocalMotionSpeed; androidx.compose.runtime.CompositionLocalProvider(Policy.providesComputed({ 2f })) {} }", "import io.github.anastaci1a.localica.ui.LocalMotionSpeed", "Subject")
 fun testC_provider_LocalReducedMotion_provides_infix_direct() = check("fun f() { androidx.compose.runtime.CompositionLocalProvider(LocalReducedMotion provides true) {} }", "import io.github.anastaci1a.localica.ui.LocalReducedMotion", "Subject")
 fun testC_provider_LocalReducedMotion_provides_infix_import() = check("fun f() { androidx.compose.runtime.CompositionLocalProvider(Policy provides true) {} }", "import io.github.anastaci1a.localica.ui.LocalReducedMotion as Policy", "Subject")
 fun testC_provider_LocalReducedMotion_provides_infix_local() = check("fun f() { val Policy=LocalReducedMotion; androidx.compose.runtime.CompositionLocalProvider(Policy provides true) {} }", "import io.github.anastaci1a.localica.ui.LocalReducedMotion", "Subject")
 fun testC_provider_LocalReducedMotion_provides_dot_direct() = check("fun f() { androidx.compose.runtime.CompositionLocalProvider(LocalReducedMotion.provides(true)) {} }", "import io.github.anastaci1a.localica.ui.LocalReducedMotion", "Subject")
 fun testC_provider_LocalReducedMotion_provides_dot_import() = check("fun f() { androidx.compose.runtime.CompositionLocalProvider(Policy.provides(true)) {} }", "import io.github.anastaci1a.localica.ui.LocalReducedMotion as Policy", "Subject")
 fun testC_provider_LocalReducedMotion_provides_dot_local() = check("fun f() { val Policy=LocalReducedMotion; androidx.compose.runtime.CompositionLocalProvider(Policy.provides(true)) {} }", "import io.github.anastaci1a.localica.ui.LocalReducedMotion", "Subject")
 fun testC_provider_LocalReducedMotion_providesDefault_infix_direct() = check("fun f() { androidx.compose.runtime.CompositionLocalProvider(LocalReducedMotion providesDefault true) {} }", "import io.github.anastaci1a.localica.ui.LocalReducedMotion", "Subject")
 fun testC_provider_LocalReducedMotion_providesDefault_infix_import() = check("fun f() { androidx.compose.runtime.CompositionLocalProvider(Policy providesDefault true) {} }", "import io.github.anastaci1a.localica.ui.LocalReducedMotion as Policy", "Subject")
 fun testC_provider_LocalReducedMotion_providesDefault_infix_local() = check("fun f() { val Policy=LocalReducedMotion; androidx.compose.runtime.CompositionLocalProvider(Policy providesDefault true) {} }", "import io.github.anastaci1a.localica.ui.LocalReducedMotion", "Subject")
 fun testC_provider_LocalReducedMotion_providesDefault_dot_direct() = check("fun f() { androidx.compose.runtime.CompositionLocalProvider(LocalReducedMotion.providesDefault(true)) {} }", "import io.github.anastaci1a.localica.ui.LocalReducedMotion", "Subject")
 fun testC_provider_LocalReducedMotion_providesDefault_dot_import() = check("fun f() { androidx.compose.runtime.CompositionLocalProvider(Policy.providesDefault(true)) {} }", "import io.github.anastaci1a.localica.ui.LocalReducedMotion as Policy", "Subject")
 fun testC_provider_LocalReducedMotion_providesDefault_dot_local() = check("fun f() { val Policy=LocalReducedMotion; androidx.compose.runtime.CompositionLocalProvider(Policy.providesDefault(true)) {} }", "import io.github.anastaci1a.localica.ui.LocalReducedMotion", "Subject")
 fun testC_provider_LocalReducedMotion_providesComputed_infix_direct() = check("fun f() { androidx.compose.runtime.CompositionLocalProvider(LocalReducedMotion providesComputed { true }) {} }", "import io.github.anastaci1a.localica.ui.LocalReducedMotion", "Subject")
 fun testC_provider_LocalReducedMotion_providesComputed_infix_import() = check("fun f() { androidx.compose.runtime.CompositionLocalProvider(Policy providesComputed { true }) {} }", "import io.github.anastaci1a.localica.ui.LocalReducedMotion as Policy", "Subject")
 fun testC_provider_LocalReducedMotion_providesComputed_infix_local() = check("fun f() { val Policy=LocalReducedMotion; androidx.compose.runtime.CompositionLocalProvider(Policy providesComputed { true }) {} }", "import io.github.anastaci1a.localica.ui.LocalReducedMotion", "Subject")
 fun testC_provider_LocalReducedMotion_providesComputed_dot_direct() = check("fun f() { androidx.compose.runtime.CompositionLocalProvider(LocalReducedMotion.providesComputed({ true })) {} }", "import io.github.anastaci1a.localica.ui.LocalReducedMotion", "Subject")
 fun testC_provider_LocalReducedMotion_providesComputed_dot_import() = check("fun f() { androidx.compose.runtime.CompositionLocalProvider(Policy.providesComputed({ true })) {} }", "import io.github.anastaci1a.localica.ui.LocalReducedMotion as Policy", "Subject")
 fun testC_provider_LocalReducedMotion_providesComputed_dot_local() = check("fun f() { val Policy=LocalReducedMotion; androidx.compose.runtime.CompositionLocalProvider(Policy.providesComputed({ true })) {} }", "import io.github.anastaci1a.localica.ui.LocalReducedMotion", "Subject")
 fun testC_provider_LocalUiSpec_provides_infix_direct() = check("fun f() { androidx.compose.runtime.CompositionLocalProvider(LocalUiSpec provides 1) {} }", "import io.github.anastaci1a.localica.ui.spec.LocalUiSpec", "Subject")
 fun testC_provider_LocalUiSpec_provides_infix_import() = check("fun f() { androidx.compose.runtime.CompositionLocalProvider(Policy provides 1) {} }", "import io.github.anastaci1a.localica.ui.spec.LocalUiSpec as Policy", "Subject")
 fun testC_provider_LocalUiSpec_provides_infix_local() = check("fun f() { val Policy=LocalUiSpec; androidx.compose.runtime.CompositionLocalProvider(Policy provides 1) {} }", "import io.github.anastaci1a.localica.ui.spec.LocalUiSpec", "Subject")
 fun testC_provider_LocalUiSpec_provides_dot_direct() = check("fun f() { androidx.compose.runtime.CompositionLocalProvider(LocalUiSpec.provides(1)) {} }", "import io.github.anastaci1a.localica.ui.spec.LocalUiSpec", "Subject")
 fun testC_provider_LocalUiSpec_provides_dot_import() = check("fun f() { androidx.compose.runtime.CompositionLocalProvider(Policy.provides(1)) {} }", "import io.github.anastaci1a.localica.ui.spec.LocalUiSpec as Policy", "Subject")
 fun testC_provider_LocalUiSpec_provides_dot_local() = check("fun f() { val Policy=LocalUiSpec; androidx.compose.runtime.CompositionLocalProvider(Policy.provides(1)) {} }", "import io.github.anastaci1a.localica.ui.spec.LocalUiSpec", "Subject")
 fun testC_provider_LocalUiSpec_providesDefault_infix_direct() = check("fun f() { androidx.compose.runtime.CompositionLocalProvider(LocalUiSpec providesDefault 1) {} }", "import io.github.anastaci1a.localica.ui.spec.LocalUiSpec", "Subject")
 fun testC_provider_LocalUiSpec_providesDefault_infix_import() = check("fun f() { androidx.compose.runtime.CompositionLocalProvider(Policy providesDefault 1) {} }", "import io.github.anastaci1a.localica.ui.spec.LocalUiSpec as Policy", "Subject")
 fun testC_provider_LocalUiSpec_providesDefault_infix_local() = check("fun f() { val Policy=LocalUiSpec; androidx.compose.runtime.CompositionLocalProvider(Policy providesDefault 1) {} }", "import io.github.anastaci1a.localica.ui.spec.LocalUiSpec", "Subject")
 fun testC_provider_LocalUiSpec_providesDefault_dot_direct() = check("fun f() { androidx.compose.runtime.CompositionLocalProvider(LocalUiSpec.providesDefault(1)) {} }", "import io.github.anastaci1a.localica.ui.spec.LocalUiSpec", "Subject")
 fun testC_provider_LocalUiSpec_providesDefault_dot_import() = check("fun f() { androidx.compose.runtime.CompositionLocalProvider(Policy.providesDefault(1)) {} }", "import io.github.anastaci1a.localica.ui.spec.LocalUiSpec as Policy", "Subject")
 fun testC_provider_LocalUiSpec_providesDefault_dot_local() = check("fun f() { val Policy=LocalUiSpec; androidx.compose.runtime.CompositionLocalProvider(Policy.providesDefault(1)) {} }", "import io.github.anastaci1a.localica.ui.spec.LocalUiSpec", "Subject")
 fun testC_provider_LocalUiSpec_providesComputed_infix_direct() = check("fun f() { androidx.compose.runtime.CompositionLocalProvider(LocalUiSpec providesComputed { 1 }) {} }", "import io.github.anastaci1a.localica.ui.spec.LocalUiSpec", "Subject")
 fun testC_provider_LocalUiSpec_providesComputed_infix_import() = check("fun f() { androidx.compose.runtime.CompositionLocalProvider(Policy providesComputed { 1 }) {} }", "import io.github.anastaci1a.localica.ui.spec.LocalUiSpec as Policy", "Subject")
 fun testC_provider_LocalUiSpec_providesComputed_infix_local() = check("fun f() { val Policy=LocalUiSpec; androidx.compose.runtime.CompositionLocalProvider(Policy providesComputed { 1 }) {} }", "import io.github.anastaci1a.localica.ui.spec.LocalUiSpec", "Subject")
 fun testC_provider_LocalUiSpec_providesComputed_dot_direct() = check("fun f() { androidx.compose.runtime.CompositionLocalProvider(LocalUiSpec.providesComputed({ 1 })) {} }", "import io.github.anastaci1a.localica.ui.spec.LocalUiSpec", "Subject")
 fun testC_provider_LocalUiSpec_providesComputed_dot_import() = check("fun f() { androidx.compose.runtime.CompositionLocalProvider(Policy.providesComputed({ 1 })) {} }", "import io.github.anastaci1a.localica.ui.spec.LocalUiSpec as Policy", "Subject")
 fun testC_provider_LocalUiSpec_providesComputed_dot_local() = check("fun f() { val Policy=LocalUiSpec; androidx.compose.runtime.CompositionLocalProvider(Policy.providesComputed({ 1 })) {} }", "import io.github.anastaci1a.localica.ui.spec.LocalUiSpec", "Subject")
 fun testC_pill_chromeVisibility_named() = check("fun f(anchor: GlassAnchor) { PopupActionPill(anchor, chromeVisibility={ 0f }) }", "", "Subject")
 fun testC_pill_chromeVisibility_positional() = check("fun f(anchor: GlassAnchor) { PopupActionPill(anchor, null, null, null, { 0f }) }", "", "Subject")
 fun testC_pill_chromeVisibility_alias() = check("fun f(anchor: GlassAnchor) { Pill(anchor, chromeVisibility={ 0f }) }", "import io.github.anastaci1a.localica.ui.PopupActionPill as Pill", "Subject")
 fun testC_pill_playerExpansion_named() = check("fun f(anchor: GlassAnchor) { PopupActionPill(anchor, playerExpansion=Any()) }", "", "Subject")
 fun testC_pill_playerExpansion_positional() = check("fun f(anchor: GlassAnchor) { PopupActionPill(anchor, null, null, null, { 1f }, Any()) }", "", "Subject")
 fun testC_pill_playerExpansion_alias() = check("fun f(anchor: GlassAnchor) { Pill(anchor, playerExpansion=Any()) }", "import io.github.anastaci1a.localica.ui.PopupActionPill as Pill", "Subject")
 fun testC_ratchet_growth() = check("fun f(loadingReducedMotion: Boolean) { androidx.compose.runtime.CompositionLocalProvider(LocalReducedMotion provides loadingReducedMotion, LocalReducedMotion provides loadingReducedMotion) {} }", "", "BrowserScreen")
 fun testC_ratchet_expression() = check("fun f() { androidx.compose.runtime.CompositionLocalProvider(LocalReducedMotion provides true) {} }", "", "BrowserScreen")
 fun testC_ratchet_operator() = check("fun f(loadingReducedMotion: Boolean) { androidx.compose.runtime.CompositionLocalProvider(LocalReducedMotion providesDefault loadingReducedMotion) {} }", "", "BrowserScreen")
 fun testC_ratchet_other_local() = check("fun f() { androidx.compose.runtime.CompositionLocalProvider(LocalMotionSpeed provides 2f) {} }", "", "BrowserScreen")

 fun testSanctioned_LocalMotionSpeed_providers() {
  val file=kotlin("src/main/java/io/github/anastaci1a/localica/ui/LocalicaScreen.kt", """package io.github.anastaci1a.localica.ui
import io.github.anastaci1a.localica.ui.LocalMotionSpeed as Policy
fun f() { androidx.compose.runtime.CompositionLocalProvider(Policy provides 2f, Policy.provides(2f), Policy providesDefault 2f, Policy.providesDefault(2f), Policy providesComputed { 2f }, Policy.providesComputed({ 2f })) {} }
""")
  lint().projects(project().name("app").files(*(stubs.take(4) + stubs.takeLast(1) + wrapperStubs.filterIndexed { index, _ -> index != 6 } + file).toTypedArray()))
   .allowMissingSdk().testModes(TestMode.DEFAULT).run().expectClean()
 }

 fun testSanctioned_LocalReducedMotion_providers() {
  val file=kotlin("src/main/java/io/github/anastaci1a/localica/ui/LocalicaScreen.kt", """package io.github.anastaci1a.localica.ui
import io.github.anastaci1a.localica.ui.LocalReducedMotion as Policy
fun f() { androidx.compose.runtime.CompositionLocalProvider(Policy provides true, Policy.provides(true), Policy providesDefault true, Policy.providesDefault(true), Policy providesComputed { true }, Policy.providesComputed({ true })) {} }
""")
  lint().projects(project().name("app").files(*(stubs.take(4) + stubs.takeLast(1) + wrapperStubs.filterIndexed { index, _ -> index != 6 } + file).toTypedArray()))
   .allowMissingSdk().testModes(TestMode.DEFAULT).run().expectClean()
 }

 fun testSanctioned_LocalUiSpec_providers() {
  val file=kotlin("src/main/java/io/github/anastaci1a/localica/ui/LocalicaUiRoot.kt", """package io.github.anastaci1a.localica.ui
import io.github.anastaci1a.localica.ui.spec.LocalUiSpec as Policy
fun f() { androidx.compose.runtime.CompositionLocalProvider(Policy provides 1, Policy.provides(1), Policy providesDefault 1, Policy.providesDefault(1), Policy providesComputed { 1 }, Policy.providesComputed({ 1 })) {} }
""")
  lint().projects(project().name("app").files(*(stubs.take(4) + stubs.takeLast(1) + wrapperStubs.filterIndexed { index, _ -> index != 6 } + file).toTypedArray()))
   .allowMissingSdk().testModes(TestMode.DEFAULT).run().expectClean()
 }

 fun testSanctionedBrowserRatchet() {
  val file=kotlin("src/main/java/io/github/anastaci1a/localica/ui/BrowserScreen.kt", """package io.github.anastaci1a.localica.ui
fun f(loadingReducedMotion: Boolean) { androidx.compose.runtime.CompositionLocalProvider(LocalReducedMotion provides loadingReducedMotion) {} }
""")
  lint().projects(project().name("app").files(*(stubs.take(4) + stubs.takeLast(1) + wrapperStubs.filterIndexed { index, _ -> index != 6 } + file).toTypedArray()))
   .allowMissingSdk().testModes(TestMode.DEFAULT).run().expectClean()
 }
 fun testSanctionedNavigationPillArguments() {
  val file=kotlin("src/main/java/io/github/anastaci1a/localica/ui/LocalicaScreen.kt", """package io.github.anastaci1a.localica.ui
fun f(anchor: GlassAnchor) { PopupActionPill(anchor, chromeVisibility={ 1f }, playerExpansion=Any()); PopupActionPill(anchor, null, null, null, { 1f }, Any()) }
""")
  lint().projects(project().name("app").files(*(stubs.take(4) + stubs.takeLast(1) + wrapperStubs.filterIndexed { index, _ -> index != 6 } + file).toTypedArray()))
   .allowMissingSdk().testModes(TestMode.DEFAULT).run().expectClean()
 }
 fun testD_root_wrapper() = check("fun f(settings: io.github.anastaci1a.localica.navigation.InterfaceSettings, anchor: GlassAnchor) { LocalicaUiRoot(LocalicaUiRootState(settings.copy(reducedMotion = true))) { GlassMenu(true, anchor, {}) {} } }", "")
 fun testD_root_default() = check("fun f() { LocalicaUiRoot {} }", "")
 fun testD_root_alias() = check("fun f() { Root {} }", "import io.github.anastaci1a.localica.ui.LocalicaUiRoot as Root")
 fun testD_root_qualified() = check("fun f() { io.github.anastaci1a.localica.ui.LocalicaUiRoot {} }", "")
 fun testD_root_reference() = check("val borrowed=::LocalicaUiRoot", "")
 fun testD_engine_call() = check("fun f() { io.github.anastaci1a.localica.ui.layout.ProvideLocalicaEngineContext(120f, 100f, null, 0f) {} }", "")
 fun testD_engine_alias() = check("fun f() { Engine(120f, 100f, null, 0f) {} }", "import io.github.anastaci1a.localica.ui.layout.ProvideLocalicaEngineContext as Engine")
 fun testD_engine_reference() = check("val borrowed=::ProvideLocalicaEngineContext", "import io.github.anastaci1a.localica.ui.layout.ProvideLocalicaEngineContext")
 fun testSanctionedIngressHosts() {
  for (path in listOf("io/github/anastaci1a/localica/MainActivity.kt", "io/github/anastaci1a/localica/ui/LocalicaScreen.kt")) {
   val file=kotlin("src/main/java/" + path, """package io.github.anastaci1a.localica.ui
fun f() { LocalicaUiRoot {} }
""")
   lint().projects(project().name("app").files(*(stubs.take(4) + stubs.takeLast(1) + wrapperStubs + file).toTypedArray()))
    .allowMissingSdk().testModes(TestMode.DEFAULT).run().expectClean()
  }
  val file=kotlin("src/main/java/io/github/anastaci1a/localica/ui/LocalicaUiRoot.kt", """package io.github.anastaci1a.localica.ui
fun f() { io.github.anastaci1a.localica.ui.layout.ProvideLocalicaEngineContext(120f, 100f, null, 1f) {} }
""")
  lint().projects(project().name("app").files(*(stubs.take(4) + stubs.takeLast(1) + wrapperStubs.filterIndexed { index, _ -> index != 6 } + file).toTypedArray()))
   .allowMissingSdk().testModes(TestMode.DEFAULT).run().expectClean()
 }
 fun testE_LocalEngineContext_direct() = check("fun f() { androidx.compose.runtime.CompositionLocalProvider(io.github.anastaci1a.localica.ui.engine.LocalEngineContext provides io.github.anastaci1a.localica.ui.engine.LocalEngineContext.current) {} }", "")
 fun testE_LocalEngineContext_import() = check("fun f() { androidx.compose.runtime.CompositionLocalProvider(LocalEngineContext provides LocalEngineContext.current) {} }", "import io.github.anastaci1a.localica.ui.engine.LocalEngineContext")
 fun testE_LocalEngineContext_alias() = check("fun f() { androidx.compose.runtime.CompositionLocalProvider(Policy provides Policy.current) {} }", "import io.github.anastaci1a.localica.ui.engine.LocalEngineContext as Policy")
 fun testE_LocalEngineContext_local() = check("fun f() { val Policy=LocalEngineContext; androidx.compose.runtime.CompositionLocalProvider(Policy provides LocalEngineContext.current) {} }", "import io.github.anastaci1a.localica.ui.engine.LocalEngineContext")
 fun testE_LocalUiClock_direct() = check("fun f() { androidx.compose.runtime.CompositionLocalProvider(io.github.anastaci1a.localica.ui.engine.LocalUiClock provides io.github.anastaci1a.localica.ui.engine.LocalUiClock.current) {} }", "")
 fun testE_LocalUiClock_import() = check("fun f() { androidx.compose.runtime.CompositionLocalProvider(LocalUiClock provides LocalUiClock.current) {} }", "import io.github.anastaci1a.localica.ui.engine.LocalUiClock")
 fun testE_LocalUiClock_alias() = check("fun f() { androidx.compose.runtime.CompositionLocalProvider(Policy provides Policy.current) {} }", "import io.github.anastaci1a.localica.ui.engine.LocalUiClock as Policy")
 fun testE_LocalUiClock_local() = check("fun f() { val Policy=LocalUiClock; androidx.compose.runtime.CompositionLocalProvider(Policy provides LocalUiClock.current) {} }", "import io.github.anastaci1a.localica.ui.engine.LocalUiClock")
 fun testE_LocalClockOwner_direct() = check("fun f() { androidx.compose.runtime.CompositionLocalProvider(io.github.anastaci1a.localica.ui.engine.LocalClockOwner provides io.github.anastaci1a.localica.ui.engine.LocalClockOwner.current) {} }", "")
 fun testE_LocalClockOwner_import() = check("fun f() { androidx.compose.runtime.CompositionLocalProvider(LocalClockOwner provides LocalClockOwner.current) {} }", "import io.github.anastaci1a.localica.ui.engine.LocalClockOwner")
 fun testE_LocalClockOwner_alias() = check("fun f() { androidx.compose.runtime.CompositionLocalProvider(Policy provides Policy.current) {} }", "import io.github.anastaci1a.localica.ui.engine.LocalClockOwner as Policy")
 fun testE_LocalClockOwner_local() = check("fun f() { val Policy=LocalClockOwner; androidx.compose.runtime.CompositionLocalProvider(Policy provides LocalClockOwner.current) {} }", "import io.github.anastaci1a.localica.ui.engine.LocalClockOwner")
 fun testE_engine_copy() = check("fun f(anchor: GlassAnchor) { androidx.compose.runtime.CompositionLocalProvider(LocalEngineContext provides LocalEngineContext.current.copy(durationScale = 4f)) { GlassMenu(true, anchor, {}) {} } }", "import io.github.anastaci1a.localica.ui.engine.LocalEngineContext")
 fun testSanctionedEngineClockProviders() {
  for ((path, locals) in listOf("layout/LocalicaEngineRoot.kt" to listOf("LocalEngineContext"), "engine/UiClock.kt" to listOf("LocalUiClock", "LocalClockOwner"))) {
   val file=kotlin("src/main/java/io/github/anastaci1a/localica/ui/" + path, "package io.github.anastaci1a.localica.ui\nimport io.github.anastaci1a.localica.ui.engine.*\nfun f() { androidx.compose.runtime.CompositionLocalProvider(" + locals.joinToString(",") { it + " provides " + it + ".current" } + ") {} }")
   lint().projects(project().name("app").files(*(stubs.take(4) + stubs.takeLast(1) + wrapperStubs.filter { !it.targetRelativePath.endsWith("/" + path) } + file).toTypedArray()))
    .allowMissingSdk().testModes(TestMode.DEFAULT).run().expectClean()
  }
 }
}
