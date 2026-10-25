package io.github.anastaci1a.localica

import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Color as AndroidColor
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.Window
import android.view.WindowManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.navigation.GlassAppearance
import io.github.anastaci1a.localica.ui.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Mandatory native lifecycle/presentation regressions plus opt-in optical evidence for #171/#369.
 * The optical lane does not assert that a particular rim is wrong.
 * Both API 26 and 36 capture the composed DISPLAY, including the underlying shadow
 * group. Only the synthetic fixture's rectangle is saved, never unrelated screen UI.
 * Bounded output: ten PNGs per scene, plus profiles/geometry for additional frames.
 */
class UtilityPopupRenderDiagnosticTest {
    @get:Rule val compose = createComposeRule()

    private enum class Scene(val dark: Boolean) { Light(false), Dark(true), Pattern(false) }
    private var open by mutableStateOf(false)
    private var strength by mutableFloatStateOf(GlassAppearance().shadowStrength)
    private var pageStage by mutableIntStateOf(1)
    private lateinit var anchor: GlassAnchor
    private lateinit var group: GlassShadowGroup
    private lateinit var pageView: View
    private lateinit var browseSource: FrostedBackdrop
    private lateinit var modalSource: FrostedBackdrop
    private var popupView: View? = null
    private var popupWindow: Window? = null
    private var dialogMarker: WindowMarker? = null
    private var lastPresentedDialogStage: Int? = null
    private var modalGroupsCleared = false
    private var sceneBounds = Rect.Zero
    private var safeSceneBounds = Rect.Zero
    private var pageMarkerBounds = Rect.Zero
    private var pageRevealBounds = Rect.Zero
    private var tabBounds = Rect.Zero
    private var miniBounds = Rect.Zero
    private val records = JSONArray()
    private val trace = JSONArray()
    private val endpointObservations = JSONArray()
    private lateinit var output: File
    private lateinit var report: JSONObject
    private var entryKeys = emptySet<Any>()
    private var opticalDiagnostics = false
    private var lastPresentedFrame: Bitmap? = null
    private var failureImageSaved = false

    @Test fun lightUtilityPopupRepeatedReturnDiagnostics() = diagnose(Scene.Light)
    @Test fun darkUtilityPopupRepeatedReturnDiagnostics() = diagnose(Scene.Dark)
    @Test fun patternedUtilityPopupRepeatedReturnDiagnostics() = diagnose(Scene.Pattern)

    @Test fun lightUtilityPopupPreservesNativeReturnOwnership() = exercise(Scene.Light, diagnostic = false)
    @Test fun darkUtilityPopupPreservesNativeReturnOwnership() = exercise(Scene.Dark, diagnostic = false)
    @Test fun patternedUtilityPopupPreservesNativeReturnOwnership() = exercise(Scene.Pattern, diagnostic = false)

    private fun diagnose(scene: Scene) {
        assumeTrue("Ten-cycle optical recording requires explicit utilityPopupOpticalDiagnostics=true; " +
            "mandatory native lifecycle and presentation run in the three PreservesNativeReturnOwnership methods",
            InstrumentationRegistry.getArguments().getString("utilityPopupOpticalDiagnostics") == "true")
        exercise(scene, diagnostic = true)
    }

    /** Default retains one live-return interruption, every unique acknowledged/first-frame
     * handoff and both shadow-control endpoints. Only nine repeated interruption pairs,
     * descriptive edge/shadow scans and successful evidence serialization move to opt-in.
     * Markers still prove actual native presentation at every retained capture.
     */
    private fun exercise(scene: Scene, diagnostic: Boolean) {
        opticalDiagnostics = diagnostic

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        // AGP/UTP copies this instrumentation output before uninstalling the app.
        // External app files only survive direct instrumentation runs without UTP.
        val additionalOutput = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
            ?.takeIf { it.isNotBlank() }
        val outputRoot = additionalOutput?.let(::File) ?: requireNotNull(context.getExternalFilesDir(null))
        output = File(outputRoot, "utility-popup-diagnostics")
        assertTrue("Diagnostic directory must be writable", output.isDirectory || output.mkdirs())
        report = JSONObject().put("scene", scene.name).put("api", Build.VERSION.SDK_INT)
            .put("outputRoute", if (additionalOutput != null) "additionalTestOutputDir" else "external-files")
            .put("density", compose.density.density)
            .put("interpretation", "App-shadow-on/off pairs are intended controls for configured app shadows, including recorded backdrops. Existing source metadata cannot prove completion of a particular legacy raster revision: fresh prepared identities and endpoint quiescence reduce, but cannot exclude, stale material sampling. These pairs do not by themselves prove cause. Residual edges can still be material/refraction/rim, scrim or native-window effects. A bright contour is not by itself the reported straight stripe. Compare central top/bottom scans with all four corner normals. No optical fix or shadow accumulation is presumed.")
            .put("frames", records).put("closingTrace", trace).put("observedEndpointQuiescence", endpointObservations)
        compose.setContent { Fixture(scene) }
        compose.waitForIdle()
        entryKeys = group.entries.keys.toSet()
        assertEquals("Mini-player, tab shelf and utility have exactly three persistent entries", 3, entryKeys.size)
        compose.mainClock.autoAdvance = false
        var primaryFailure: Throwable? = null
        try {
            settleEndpoint()
            val rest = capture("rest", save = true)
            var beforeOpenRaster = rasterRefs(modalSource)
            compose.onNodeWithContentDescription("More").performClick()
            settleEndpoint(beforeOpenRaster)
            compose.onNodeWithText("Scan music").assertExists()
            compose.onNodeWithText("Settings").assertExists()
            val firstOpen = capture("first-open", save = true)
            val firstWindow = popupWindow
            // Exercise ready-popup reversals, not a separate initial-mount race.
            repeat(if (diagnostic) 10 else 1) { cycle ->
                compose.runOnIdle { open = false }
                advance(96)
                capture("reverse-$cycle-closing", save = cycle == 9)
                assertTrue("This must interrupt a live return", anchor.modalPresent)
                assertTrue("Close has travelled away from the expanded endpoint", anchor.contact.expansion.value < 1f)
                beforeOpenRaster = rasterRefs(modalSource)
                compose.runOnIdle { open = true }
                advance(96)
                capture("reverse-$cycle-reopening", save = false)
                assertTrue("Reopening keeps the same modal lifetime", anchor.modalPresent)
                assertSame("Interrupted reversal cannot allocate a second dialog", firstWindow, popupWindow)
            }
            settleEndpoint(beforeOpenRaster)
            val repeatedOpen = capture("repeated-open", save = true)
            assertMatchedGeometry(firstOpen, repeatedOpen, "expanded before/after repeated interruptions")

            compose.runOnIdle { open = false }
            awaitRestoredDrawAcknowledgement()
            capture("restored-draw-ack", save = true)
            // Request reopen at the draw acknowledgement, without another frame.
            // Close may release logical ownership before the reopen effect runs,
            // while Compose still retains this same native window.
            beforeOpenRaster = rasterRefs(modalSource)
            compose.runOnIdle { open = true }
            advanceFrame()
            capture("reopen-during-ack", save = true)
            assertTrue("Reopen cancels disposal of the returning window", anchor.modalPresent)
            assertTrue("Reopen immediately restores modal body ownership", anchor.contact.modal)
            assertSame("Acknowledgement reopen retains the original dialog", firstWindow, popupWindow)
            assertTrue("The first reopened frame immediately hides the restored trigger", anchor.hidden)
            assertTrue("The retained modal immediately reclaims shadow ownership", anchor.shadowInModal)
            assertEquals("Reopening leaves only the mini-player and tabs in the bottom group", 2,
                group.entries.values.count { it.anchor?.shadowInModal != true })
            advanceFrame()
            capture("reopen-second-frame", save = false)
            assertSame("The second reopened frame retains the original dialog", firstWindow, popupWindow)
            assertTrue("The second reopened frame retains modal body ownership", anchor.modalPresent && anchor.contact.modal)
            assertTrue("The trigger stays hidden on the second reopened frame", anchor.hidden)
            assertTrue("The modal keeps shadow ownership on the second reopened frame", anchor.shadowInModal)
            assertEquals("No duplicate bottom-group body appears on the second reopened frame", 2,
                group.entries.values.count { it.anchor?.shadowInModal != true })
            settleEndpoint(beforeOpenRaster)

            compose.runOnIdle { open = false }
            awaitRestoredDrawAcknowledgement()
            capture("handoff-before", save = false)
            val before = anchor.contact.body.value
            advanceFrame()
            assertFalse("Acknowledged trigger takes over on the next frame", anchor.modalPresent)
            assertFalse("The acknowledged trigger owns its visible surface", anchor.hidden)
            assertFalse("The acknowledged trigger owns its shadow", anchor.shadowInModal)
            assertEquals("Normal handoff restores all three bottom-group bodies", 3,
                group.entries.values.count { it.anchor?.shadowInModal != true })
            val after = anchor.contact.body.value
            trace.put(stateJson().put("observation", "first-trigger-owned-frame"))
            assertTrue("Actual pill keeps its depth through handoff", abs(after.depth - before.depth) < .03f)
            assertTrue("Actual pill keeps its strain through handoff", abs(after.strain - before.strain) < .03f)
            assertFalse("Low damping still rebounds after the window returns", anchor.contact.state.momentum.settled)
            // Ownership transfers before Compose disposes the Dialog on API 26.
            // Preserve the first-frame body assertions above; separately finish
            // disposal before asking the compositor to prove the window is gone.
            awaitDialogDisposal()
            capture("handoff-after", save = true)
            settleEndpoint()
            val returned = capture("returned-rest", save = true)
            assertMatchedGeometry(rest, returned, "rest before/after repeated interruptions")
            assertTrue("The actual utility body eventually sleeps", anchor.contact.state.momentum.settled)
            assertEquals(GlassShape(), anchor.contact.body.value)

            compose.runOnIdle { strength = 0f }
            settleEndpoint()
            val noShadowRest = capture("app-shadow-off-rest", save = true)
            assertMatchedGeometry(returned, noShadowRest, "closed app-shadow control")
            beforeOpenRaster = rasterRefs(modalSource)
            compose.onNodeWithContentDescription("More").performClick()
            settleEndpoint(beforeOpenRaster)
            val noShadowOpen = capture("app-shadow-off-open", save = true)
            assertMatchedGeometry(firstOpen, noShadowOpen, "expanded app-shadow control")
            report.put("matchedPairs", JSONArray().put("first-open / repeated-open")
                .put("rest / returned-rest").put("returned-rest / app-shadow-off-rest")
                .put("first-open / app-shadow-off-open"))
            report.put("status", if (diagnostic) "structural assertions passed; optical profiles require inspection"
                else "mandatory native presentation and ownership assertions passed; optical profiling not requested")
        } catch (failure: Throwable) {
            primaryFailure = failure
            report.put("status", "failed: ${failure.message}")
            if (!diagnostic && !failureImageSaved) lastPresentedFrame?.let { image ->
                // Last already-presented frame, not necessarily the failing state; no new capture.
                runCatching { report.put("lastPresentedFailureImage", saveScene(image, "last-presented-failure")) }
                    .exceptionOrNull()?.let(failure::addSuppressed)
            }
            throw failure
        } finally {
            try {
                try {
                    if (diagnostic || report.optString("status").startsWith("failed:")) writeReport()
                } catch (outputFailure: Throwable) {
                    val original = primaryFailure
                    if (original == null) throw outputFailure
                    original.addSuppressed(outputFailure)
                }
            } finally {
                lastPresentedFrame?.recycle()
                lastPresentedFrame = null
                try { compose.runOnIdle { open = false } }
                finally { compose.mainClock.autoAdvance = true }
            }
        }
    }

    @Composable private fun Fixture(scene: Scene) {
        LocalicaTheme {
            val appearance = GlassAppearance(shapeDamping = 2f, shadowStrength = strength)
            CompositionLocalProvider(LocalGlassAppearance provides appearance, LocalReducedMotion provides false,
                LocalContentColor provides if (scene.dark) androidx.compose.ui.graphics.Color.White else androidx.compose.ui.graphics.Color.Black) {
                val browse = rememberFrostedBackdrop(scene, dark = scene.dark)
                val modal = rememberFrostedBackdrop(scene, dark = scene.dark)
                browseSource = browse
                modalSource = modal
                group = remember { GlassShadowGroup() }
                anchor = rememberGlassAnchor()
                pageView = LocalView.current
                CompositionLocalProvider(LocalFrostedBackdrop provides browse, LocalModalBackdrop provides modal,
                    LocalBottomShadows provides group) {
                    Box(Modifier.fillMaxSize().onGloballyPositioned {
                        sceneBounds = Rect(it.backdropPosition(pageView), Size(it.size.width.toFloat(), it.size.height.toFloat()))
                    }) {
                        // Same separation as LocalicaScreen: browse excludes floating
                        // controls, modal records the complete underlying scene/group.
                        Box(Modifier.fillMaxSize().recordFrostedBackdrop(modal).bottomGlassShadowHost(group)) {
                            Canvas(Modifier.fillMaxSize().recordFrostedBackdrop(browse)) {
                                drawRect(when (scene) {
                                    Scene.Light -> androidx.compose.ui.graphics.Color(0xFFF4F4F6)
                                    Scene.Dark -> androidx.compose.ui.graphics.Color(0xFF24262A)
                                    Scene.Pattern -> androidx.compose.ui.graphics.Color(0xFFE4B695)
                                })
                                if (scene == Scene.Pattern) {
                                    val cell = 19.dp.toPx()
                                    for (y in 0..ceil(size.height / cell).toInt()) for (x in 0..ceil(size.width / cell).toInt())
                                        if ((x + y) % 2 == 0) drawRect(androidx.compose.ui.graphics.Color(0xFF2A6275),
                                            Offset(x * cell, y * cell), Size(cell, cell))
                                }
                            }
                            Column(Modifier.align(Alignment.BottomCenter).safeDrawingPadding()
                                .padding(horizontal = 12.dp).padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                LiquidGlassSurface(Modifier.fillMaxWidth().height(CompactControlSize)
                                    .onGloballyPositioned { miniBounds = Rect(it.backdropPosition(pageView), Size(it.size.width.toFloat(), it.size.height.toFloat())) }
                                    .bottomGlassShadow(), source = browse, shadow = false, depthScale = RaisedGlassDepthScale) {
                                    Text("Synthetic mini-player", Modifier.align(Alignment.Center))
                                }
                                Row(Modifier.fillMaxWidth().height(NavigationPillHeight), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    LiquidGlassSurface(Modifier.weight(1f).fillMaxHeight()
                                        .onGloballyPositioned { tabBounds = Rect(it.backdropPosition(pageView), Size(it.size.width.toFloat(), it.size.height.toFloat())) }
                                        .bottomGlassShadow(), source = browse, shadow = false, depthScale = BottomPillDepthScale) {
                                        Text("Home     Library", Modifier.align(Alignment.Center))
                                    }
                                    NavigationUtilityPill(anchor, browse, Modifier.width(104.dp).fillMaxHeight(),
                                        onSearch = {}, onMore = { open = true })
                                }
                            }
                        }
                        // Outside both recordings and the bottom region; only the
                        // popup's known .16*p scrim changes this page marker's RGB.
                        Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                            Box(Modifier.fillMaxSize().onGloballyPositioned {
                                safeSceneBounds = Rect(it.backdropPosition(pageView), Size(it.size.width.toFloat(), it.size.height.toFloat()))
                            }) {
                                PresentationMarker(Modifier.offset(24.dp, 24.dp).onGloballyPositioned {
                                    pageMarkerBounds = Rect(it.backdropPosition(pageView), Size(it.size.width.toFloat(), it.size.height.toFloat()))
                                }) { pageStage }
                                // Revealed when the dialog actually leaves the
                                // compositor, not just when Compose removes it.
                                PresentationMarker(Modifier.align(Alignment.TopEnd).offset((-24).dp, 24.dp).onGloballyPositioned {
                                    pageRevealBounds = Rect(it.backdropPosition(pageView), Size(it.size.width.toFloat(), it.size.height.toFloat()))
                                }) { pageStage }
                            }
                        }
                        GlassMenu(open, anchor, { open = false }) {
                            InstallDialogMarker()
                            ActionMenuItem("Scan music", onClick = { open = false })
                            ActionMenuItem("Settings", onClick = { open = false })
                        }
                    }
                }
            }
        }
    }

    @Composable private fun InstallDialogMarker() {
        val view = LocalView.current
        val window = (view.parent as DialogWindowProvider).window
        val cleared = LocalBottomShadows.current == null && LocalSurfaceShadows.current == null
        SideEffect { popupView = view; popupWindow = window; modalGroupsCleared = cleared }
        DisposableEffect(window) {
            val marker = WindowMarker()
            dialogMarker = marker
            window.decorView.overlay.add(marker)
            onDispose {
                window.decorView.overlay.remove(marker)
                if (dialogMarker === marker) { dialogMarker = null; popupView = null; popupWindow = null }
            }
        }
    }

    private fun advance(milliseconds: Long) {
        compose.runOnIdle { pageStage = pageStage % 30 + 1 }
        compose.mainClock.advanceTimeBy(milliseconds)
        compose.waitForIdle()
    }

    private fun rasterRefs(source: FrostedBackdrop): List<Any?> = listOf(
        source.snapshot, source.fallback, source.glassFallback, source.lensSnapshot, source.lensFrostSnapshot)

    private data class NativeLayout(val origin: Offset, val width: Int, val height: Int,
        val attached: Boolean, val layoutRequested: Boolean) {
        fun json() = JSONObject().put("origin", JSONArray().put(origin.x).put(origin.y))
            .put("width", width).put("height", height).put("attached", attached).put("layoutRequested", layoutRequested)
    }

    private data class EndpointLayout(val utility: Rect, val popup: Rect?, val page: NativeLayout,
        val modal: NativeLayout?, val decor: NativeLayout?, val scene: Rect, val safe: Rect,
        val pageMarker: Rect, val revealMarker: Rect) {
        val ready get() = utility.width > 0f && safe.width > 0f && safe.height > 0f &&
            page.width > 0 && page.height > 0 && page.attached && !page.layoutRequested &&
            (popup == null || popup.width > 0f && popup.height > 0f &&
                modal?.attached == true && modal.width > 0 && modal.height > 0 && !modal.layoutRequested &&
                decor?.attached == true && decor.width > 0 && decor.height > 0 && !decor.layoutRequested)
    }

    private fun endpointLayout(): EndpointLayout {
        val popupBounds = if (anchor.modalPresent)
            compose.onNodeWithTag("glass-morph-surface").fetchSemanticsNode().boundsInRoot else null
        return compose.runOnIdle {
            fun native(view: View): NativeLayout {
                val origin = IntArray(2)
                view.getLocationOnScreen(origin)
                return NativeLayout(Offset(origin[0].toFloat(), origin[1].toFloat()), view.width, view.height,
                    view.isAttachedToWindow, view.isLayoutRequested)
            }
            val modal = popupView?.let(::native)
            EndpointLayout(anchor.bounds, popupBounds?.translate(requireNotNull(modal).origin), native(pageView),
                modal, popupWindow?.decorView?.let(::native), sceneBounds, safeSceneBounds, pageMarkerBounds, pageRevealBounds)
        }
    }

    private fun EndpointLayout.json() = JSONObject().put("utility", utility.json())
        .put("popupScreenBounds", popup?.json() ?: JSONObject.NULL).put("pageNative", page.json())
        .put("popupNative", modal?.json() ?: JSONObject.NULL).put("decorNative", decor?.json() ?: JSONObject.NULL)
        .put("sceneBounds", scene.json()).put("safeBounds", safe.json())
        .put("pageMarkerBounds", pageMarker.json()).put("revealMarkerBounds", revealMarker.json())

    private fun settleEndpoint(previousModal: List<Any?>? = null) {
        advance(12000)
        val legacy = Build.VERSION.SDK_INT < 33
        var last = emptyList<Any?>()
        var lastLayout: EndpointLayout? = null
        var unchangedSince = SystemClock.uptimeMillis()
        var unchangedFrameSince = compose.mainClock.currentTime
        var stableSamples = 0
        var layoutChanges = 0
        val observation = JSONObject().put("requestedOpen", open).put("legacyRasterRequired", legacy)
            .put("freshModalRequired", legacy && previousModal != null)
        endpointObservations.put(observation)
        // Endpoint-only, both APIs: virtual time alone cannot finish native inset/
        // layout callbacks or legacy readback/scatter/tint. Observe unchanged layout
        // across real UI turns as well as clock time. Never wait for an expected
        // alignment or pixel result, and never use this in frozen transition captures.
        compose.mainClock.autoAdvance = true
        try {
            compose.waitUntil(10000) {
                val layout = endpointLayout()
                val browse = if (legacy) rasterRefs(browseSource) else emptyList()
                val modal = if (legacy && open) rasterRefs(modalSource) else emptyList()
                val current = browse + modal
                if (lastLayout == null) observation.put("firstLayout", layout.json())
                else if (layout != lastLayout) layoutChanges++
                if (current != last || layout != lastLayout) {
                    last = current; lastLayout = layout; unchangedSince = SystemClock.uptimeMillis()
                    unchangedFrameSince = compose.mainClock.currentTime
                    stableSamples = 0
                }
                stableSamples++
                val refreshed = !legacy || !open || previousModal == null ||
                    modal.indices.all { modal[it] !== previousModal[it] }
                layout.ready && browseSource.recorded && (!open || modalSource.recorded) &&
                    current.all { it != null } && refreshed && SystemClock.uptimeMillis() - unchangedSince >= 200 &&
                    compose.mainClock.currentTime - unchangedFrameSince >= 600 && stableSamples >= 4
            }
            observation.put("complete", true)
        } finally {
            observation.put("clockMs", compose.mainClock.currentTime).put("stableSamples", stableSamples)
                .put("layoutChanges", layoutChanges)
                .put("quietWallMs", SystemClock.uptimeMillis() - unchangedSince)
                .put("quietClockMs", compose.mainClock.currentTime - unchangedFrameSince)
                .put("lastLayout", lastLayout?.json() ?: JSONObject.NULL)
            compose.mainClock.autoAdvance = false
        }
        advanceFrame()
    }

    private fun awaitDialogDisposal() {
        repeat(12) {
            if (popupWindow == null && popupView == null && dialogMarker == null) return
            advanceFrame()
            trace.put(stateJson().put("observation", "awaiting-dialog-disposal"))
        }
        assertTrue("Dialog window, view and marker must dispose within twelve extra frames",
            popupWindow == null && popupView == null && dialogMarker == null)
    }

    private fun advanceFrame() {
        compose.runOnIdle { pageStage = pageStage % 30 + 1 }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
    }

    private fun awaitRestoredDrawAcknowledgement() {
        repeat(180) {
            advanceFrame()
            val state = stateJson()
            trace.put(state)
            if (anchor.modalPresent && !anchor.hidden && anchor.restoredDrawn && anchor.contact.expansion.value == 0f) return
            assertTrue("Do not skip the actual restored-trigger drawing acknowledgement: $state", anchor.modalPresent)
        }
        fail("Return did not reach its drawing acknowledgement within 180 controlled frames")
    }

    private data class Geometry(val bounds: Rect, val drawn: Rect, val radius: Float)
    private data class Frame(val utility: Geometry, val popup: Geometry?, val shape: GlassShape, val expansion: Float)

    private fun capture(name: String, save: Boolean): Frame {
        assertEquals("Repeated reopen cannot accumulate group entries", entryKeys, group.entries.keys.toSet())
        assertTrue("All persistent bottom entries remain attached", group.entries.values.all { it.coordinates?.isAttached == true })
        assertEquals("Exactly one persistent entry belongs to the utility anchor", 1, group.entries.values.count { it.anchor === anchor })
        if (anchor.modalPresent) assertTrue("Popup descendants cannot join another window's group", modalGroupsCleared)
        val shape = anchor.contact.body.value
        val sx = shape.scaleX(anchor.contact.vertical)
        val sy = shape.scaleY(anchor.contact.vertical)
        fun geometry(rect: Rect, radius: Float): Geometry {
            val half = Offset(rect.width * sx / 2, rect.height * sy / 2)
            return Geometry(rect, Rect(rect.center - half, rect.center + half), radius)
        }
        val p = anchor.contact.expansion.value.coerceIn(0f, 1f)
        val utility = geometry(anchor.bounds, anchor.bounds.height / 2)
        val popup = if (anchor.modalPresent) {
            val bounds = compose.onNodeWithTag("glass-morph-surface").fetchSemanticsNode().boundsInRoot
            val origin = IntArray(2)
            compose.runOnIdle { requireNotNull(popupView).getLocationOnScreen(origin) }
            geometry(bounds.translate(Offset(origin[0].toFloat(), origin[1].toFloat())),
                anchor.bounds.height / 2 * (1f - p) + 28f * compose.density.density * p)
        } else null
        val frame = Frame(utility, popup, shape, p)
        val frameJson = stateJson().put("name", name).put("pageStage", pageStage)
            .put("dialogStage", if (popup != null) 31 - pageStage else JSONObject.NULL)
            .put("markerFamilies", "page RGB=(8*stage,255,0); dialog RGB=(8*stage,255,255)")
            .put("utility", utility.json()).put("popup", popup?.json() ?: JSONObject.NULL)
            .put("tabBounds", tabBounds.json()).put("miniBounds", miniBounds.json())
            .put("pageMarkerBounds", pageMarkerBounds.json()).put("sceneBounds", sceneBounds.json())
            .put("pageRevealBounds", pageRevealBounds.json()).put("safeSceneBounds", safeSceneBounds.json())
        val dialogSample = pageRevealBounds.center
        frameJson.put("dialogMarkerSample", JSONArray().put(dialogSample.x).put(dialogSample.y))
            .put("previousPresentedDialogStage", lastPresentedDialogStage ?: JSONObject.NULL)
        if (popup != null) {
            assertFalse("Dialog marker must be outside the optical measurement", popup.drawn.contains(dialogSample))
            assertFalse("Page marker must be outside the optical measurement", popup.drawn.contains(pageMarkerBounds.center))
            compose.runOnIdle {
                val decor = requireNotNull(popupWindow).decorView
                val origin = IntArray(2)
                decor.getLocationOnScreen(origin)
                requireNotNull(dialogMarker).apply {
                    stage = 31 - pageStage
                    val half = 4f * compose.density.density
                    setBounds((dialogSample.x - origin[0] - half).roundToInt(), (dialogSample.y - origin[1] - half).roundToInt(),
                        (dialogSample.x - origin[0] + half).roundToInt(), (dialogSample.y - origin[1] + half).roundToInt())
                    invalidateSelf()
                }
                // Installed outside the popup content's alpha/clip, after Compose
                // has drawn this frozen state. This requests a native presentation,
                // without advancing the close coroutine or waiting for desired optics.
                decor.invalidate()
            }
        }
        var screenshot: Bitmap? = null
        try {
            compose.waitUntil(5000) {
                screenshot?.recycle()
                val next = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
                screenshot = next
                val factor = if (popup != null) 1f - .16f * p else 1f
                val pageMatches = markerMatches(next, pageMarkerBounds.center, pageStage, factor)
                val dialogMatches = markerMatches(next, dialogSample, if (popup != null) 31 - pageStage else pageStage, 1f,
                    dialog = popup != null)
                frameJson.put("observedPageMarker", Integer.toHexString(next.getPixel(pageMarkerBounds.center.x.roundToInt(), pageMarkerBounds.center.y.roundToInt())))
                    .put("observedDialogMarker", Integer.toHexString(next.getPixel(dialogSample.x.roundToInt(), dialogSample.y.roundToInt())))
                pageMatches && dialogMatches
            }
            val image = requireNotNull(screenshot)
            if (popup != null) lastPresentedDialogStage = 31 - pageStage
            frameJson.put("presentationVerified", true)
            if (opticalDiagnostics) {
                frameJson.put("utilityProfiles", profiles(image, utility, sx, sy))
                if (popup != null) frameJson.put("popupProfiles", profiles(image, popup, sx, sy))
                val d = compose.density.density
                val regions = JSONObject()
                    .put("belowUtility", region(image, Rect(utility.drawn.left, utility.drawn.bottom + 2*d, utility.drawn.right, utility.drawn.bottom + 12*d)))
                    .put("tabUtilityGap", region(image, Rect(tabBounds.right + d, anchor.bounds.top + 20*d, anchor.bounds.left - d, anchor.bounds.bottom - 20*d)))
                    .put("miniShelfGap", region(image, Rect(miniBounds.left + 40*d, miniBounds.bottom + d, miniBounds.right - 40*d, tabBounds.top - d)))
                if (popup != null) regions.put("abovePopup", region(image, Rect(popup.drawn.left, popup.drawn.top - 12*d, popup.drawn.right, popup.drawn.top - 2*d)))
                frameJson.put("shadowRegions", regions)
                if (save) frameJson.put("image", saveScene(image, name))
                Log.i("UtilityPopupProbe", "${report.getString("scene")} $name p=$p shape=$shape " +
                    "hidden=${anchor.hidden} modal=${anchor.modalPresent} group=${group.entries.size} regions=$regions")
            } else {
                // Transfer this existing screenshot for bounded failure-only evidence.
                lastPresentedFrame?.recycle()
                lastPresentedFrame = image
                screenshot = null
            }
        } catch (failure: Throwable) {
            frameJson.put("captureFailure", failure.message ?: failure.javaClass.simpleName)
            // At most one extra PNG per failed scene. It is explicitly unverified,
            // and explains marker failures without accepting stale optical pixels.
            screenshot?.let { image -> runCatching {
                frameJson.put("unverifiedFailureImage", saveScene(image, "$name-unverified-failure"))
                failureImageSaved = true
            } }
            Log.e("UtilityPopupProbe", "Presentation/profile failure: $frameJson")
            throw failure
        } finally {
            screenshot?.recycle()
            records.put(frameJson)
            if (opticalDiagnostics) writeReport()
        }
        return frame
    }

    private fun saveScene(image: Bitmap, name: String): JSONObject {
        val left = ceil(safeSceneBounds.left).toInt(); val top = ceil(safeSceneBounds.top).toInt()
        val right = floor(safeSceneBounds.right).toInt(); val bottom = floor(safeSceneBounds.bottom).toInt()
        assertTrue("Only the synthetic scene can be saved", left >= 0 && top >= 0 && right <= image.width && bottom <= image.height && right > left && bottom > top)
        val crop = Bitmap.createBitmap(image, left, top, right - left, bottom - top)
        val file = File(output, "${report.getString("scene")}-api${Build.VERSION.SDK_INT}-$name.png")
        try { file.outputStream().use { assertTrue(crop.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
        finally { if (crop !== image) crop.recycle() }
        return JSONObject().put("file", file.name).put("origin", JSONArray().put(left).put(top))
    }

    private fun stateJson(): JSONObject {
        val body = anchor.contact.body.value
        val window = popupWindow
        return JSONObject().put("clockMs", compose.mainClock.currentTime).put("requestedOpen", open)
            .put("expansion", anchor.contact.expansion.value).put("strain", body.strain).put("depth", body.depth)
            .put("scaleX", body.scaleX(anchor.contact.vertical)).put("scaleY", body.scaleY(anchor.contact.vertical))
            .put("vertical", anchor.contact.vertical).put("settled", anchor.contact.state.momentum.settled)
            .put("hidden", anchor.hidden).put("modalPresent", anchor.modalPresent).put("restoredDrawn", anchor.restoredDrawn)
            .put("shadowInModal", anchor.shadowInModal).put("appShadowStrength", strength)
            .put("groupEntries", group.entries.size).put("activeGroupBodies", group.entries.values.count { it.anchor?.shadowInModal != true })
            .put("browseRecorded", browseSource.recorded).put("modalRecorded", modalSource.recorded)
            .put("browseRevision", browseSource.revision).put("modalRevision", modalSource.revision)
            .put("browseRasterReady", browseSource.lensSnapshot != null).put("modalRasterReady", modalSource.lensSnapshot != null)
            .put("rasterIdentityOrder", "snapshot,fallback,glassFallback,lensSnapshot,lensFrostSnapshot")
            .put("browseRasterIdentities", JSONArray(rasterRefs(browseSource).map { it?.let(System::identityHashCode) ?: JSONObject.NULL }))
            .put("modalRasterIdentities", JSONArray(rasterRefs(modalSource).map { it?.let(System::identityHashCode) ?: JSONObject.NULL }))
            .put("browseSize", "${browseSource.size.width}x${browseSource.size.height}")
            .put("modalSize", "${modalSource.size.width}x${modalSource.size.height}")
            .put("windowFlags", window?.attributes?.flags ?: JSONObject.NULL)
            .put("windowIdentity", window?.let(System::identityHashCode) ?: JSONObject.NULL)
            .put("popupViewPresent", popupView != null).put("dialogMarkerPresent", dialogMarker != null)
            .put("nativeDimBehind", window?.let { it.attributes.flags and WindowManager.LayoutParams.FLAG_DIM_BEHIND != 0 } ?: false)
            .put("windowDimAmount", window?.attributes?.dimAmount ?: JSONObject.NULL)
            .put("decorElevation", window?.decorView?.elevation ?: JSONObject.NULL)
            .put("decorTranslationZ", window?.decorView?.translationZ ?: JSONObject.NULL)
    }

    private fun assertMatchedGeometry(a: Frame, b: Frame, reason: String) {
        assertEquals("$reason: utility layout", a.utility.bounds, b.utility.bounds)
        assertEquals("$reason: popup layout", a.popup?.bounds, b.popup?.bounds)
        assertEquals("$reason: expansion", a.expansion, b.expansion, .0001f)
        assertEquals("$reason: strain", a.shape.strain, b.shape.strain, .0001f)
        assertEquals("$reason: depth", a.shape.depth, b.shape.depth, .0001f)
    }

    private fun markerMatches(image: Bitmap, sample: Offset, stage: Int, factor: Float, dialog: Boolean = false): Boolean {
        val x = sample.x.roundToInt(); val y = sample.y.roundToInt()
        assertTrue("Presentation marker must be inside the composed display", x in 0 until image.width && y in 0 until image.height)
        val color = image.getPixel(x, y)
        return abs(AndroidColor.red(color) - stage * 8f * factor) <= 3f &&
            abs(AndroidColor.green(color) - 255f * factor) <= 3f &&
            abs(AndroidColor.blue(color) - if (dialog) 255 else 0) <= 3
    }

    private fun profiles(image: Bitmap, geometry: Geometry, sx: Float, sy: Float): JSONObject {
        val rect = geometry.drawn
        val result = JSONObject().put("offsetUnit", "physical pixel; negative inside, positive outside")
        for (top in listOf(true, false)) {
            val rows = JSONArray()
            for (offset in -8..8) {
                val y = (if (top) rect.top - offset else rect.bottom + offset).roundToInt().toFloat()
                rows.put(JSONObject().put("offset", offset).put("samples", region(image,
                    Rect(rect.left + rect.width * .35f, y, rect.right - rect.width * .35f, y + 1))))
            }
            result.put(if (top) "topCentre" else "bottomCentre", rows)
        }
        val rx = minOf(geometry.radius * sx, rect.width / 2)
        val ry = minOf(geometry.radius * sy, rect.height / 2)
        for ((name, signs) in listOf("topLeft" to (-1 to -1), "topRight" to (1 to -1), "bottomLeft" to (-1 to 1), "bottomRight" to (1 to 1))) {
            val (xSign, ySign) = signs
            val cx = if (xSign < 0) rect.left + rx else rect.right - rx
            val cy = if (ySign < 0) rect.top + ry else rect.bottom - ry
            val normalLength = sqrt(1f / (rx * rx) + 1f / (ry * ry))
            val samples = JSONArray()
            for (offset in -8..8) {
                val x = (cx + xSign * (rx / sqrt(2f) + offset / rx / normalLength)).roundToInt().toFloat()
                val y = (cy + ySign * (ry / sqrt(2f) + offset / ry / normalLength)).roundToInt().toFloat()
                samples.put(JSONObject().put("offset", offset).put("samples", region(image, Rect(x, y, x + 1, y + 1))))
            }
            result.put(name, samples)
        }
        return result
    }

    private fun region(image: Bitmap, rect: Rect): JSONObject {
        // Record clipping explicitly rather than silently pretending edge-of-display
        // samples cover the entire shadow. Means are descriptive, never pass criteria.
        val safeLeft = ceil(safeSceneBounds.left).toInt().coerceIn(0, image.width)
        val safeTop = ceil(safeSceneBounds.top).toInt().coerceIn(0, image.height)
        val safeRight = floor(safeSceneBounds.right).toInt().coerceIn(safeLeft, image.width)
        val safeBottom = floor(safeSceneBounds.bottom).toInt().coerceIn(safeTop, image.height)
        val left = floor(rect.left).toInt().coerceIn(safeLeft, safeRight)
        val top = floor(rect.top).toInt().coerceIn(safeTop, safeBottom)
        val right = ceil(rect.right).toInt().coerceIn(left, safeRight)
        val bottom = ceil(rect.bottom).toInt().coerceIn(top, safeBottom)
        var r = 0.0; var g = 0.0; var b = 0.0
        var min = 255.0; var max = 0.0; var count = 0
        for (y in top until bottom) for (x in left until right) {
            val pixel = image.getPixel(x, y)
            val red = AndroidColor.red(pixel); val green = AndroidColor.green(pixel); val blue = AndroidColor.blue(pixel)
            val luma = .2126 * red + .7152 * green + .0722 * blue
            r += red; g += green; b += blue; min = minOf(min, luma); max = maxOf(max, luma); count++
        }
        return JSONObject().put("requestedBounds", rect.json()).put("sampleBounds", JSONArray().put(left).put(top).put(right).put(bottom))
            .put("count", count).put("rgbMean", if (count == 0) JSONObject.NULL else JSONArray().put(r / count).put(g / count).put(b / count))
            .put("lumaRange", if (count == 0) JSONObject.NULL else JSONArray().put(min).put(max))
    }

    private fun Rect.json() = JSONArray().put(left).put(top).put(right).put(bottom)
    private fun Geometry.json() = JSONObject().put("layout", bounds.json()).put("drawn", drawn.json()).put("unscaledRadius", radius)
    private fun writeReport() = File(output, "${report.getString("scene")}-api${Build.VERSION.SDK_INT}-profiles.json").writeText(report.toString(2))

    /** Test-only presentation token outside the popup's fading/clipped foreground. */
    private class WindowMarker : Drawable() {
        var stage = 0
        private val paint = Paint()
        override fun draw(canvas: AndroidCanvas) {
            // Disjoint blue channel: no previous dialog token can masquerade as
            // the newly exposed page token, even when stage IDs wrap or coincide.
            paint.color = AndroidColor.rgb(stage * 8, 255, 255)
            canvas.drawRect(bounds, paint)
        }
        override fun setAlpha(alpha: Int) { paint.alpha = alpha }
        override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
        @Deprecated("Deprecated in Android") override fun getOpacity() = PixelFormat.OPAQUE
    }
}
