package io.github.anastaci1a.localica

import android.app.Activity
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.os.Build
import android.view.View
import android.view.ViewTreeObserver
import android.view.Window
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.asAndroidBitmap
import kotlinx.coroutines.runBlocking
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.*
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.library.LibrarySnapshot
import io.github.anastaci1a.localica.library.Track
import io.github.anastaci1a.localica.navigation.InterfacePreferences
import io.github.anastaci1a.localica.navigation.InterfaceSettings
import io.github.anastaci1a.localica.navigation.GlassAppearance
import io.github.anastaci1a.localica.navigation.GlassRendererMode
import io.github.anastaci1a.localica.navigation.MainTab
import io.github.anastaci1a.localica.playback.PlaybackState
import io.github.anastaci1a.localica.playlist.PlaylistState
import io.github.anastaci1a.localica.ui.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.IdentityHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.max

/** Fixture-only R1 back-port. Production is exactly published v0.34.1. */
class PopupR1DepthBaseTest {
    @get:Rule val compose = localicaComposeRule()
    private val frames by lazy { PopupWindowEvidence(compose) }
    private val channels: List<(Int)->Int> = listOf({ Color.red(it) }, { Color.green(it) }, { Color.blue(it) })
    @org.junit.After fun removeMarkers() = frames.dispose()
    @Test fun normalDepartingBrowseMenuStaysAboveTabs() = nonChromeMenu(GlassRendererMode.Normal)
    @Test fun singleToneDepartingBrowseMenuStaysAboveTabs() = nonChromeMenu(GlassRendererMode.SingleToneBlur)

    private fun nonChromeMenu(mode: GlassRendererMode) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = InterfacePreferences(context)
        val previous = preferences.read()
        val outputRoot = File(nativeEvidenceDirectory("popup-chrome-depth", context.getExternalFilesDir(null)),
            "${mode.name}/non-chrome-menu").apply { mkdirs() }
        var output = File(outputRoot, "offset-0").apply { mkdirs() }
        var trial by mutableIntStateOf(0)
        lateinit var clock: io.github.anastaci1a.localica.ui.engine.UiClock
        val qualifiedPhases = mutableSetOf<Int>()
        val qualifiedBounds = mutableSetOf<List<Int>>()
        var originalWindowCount = 0
        val trials = JSONArray()
        lateinit var host: View
        lateinit var home: GlassPopupHome
        var popup: Window? = null
        var geometryProgress = 0f
        lateinit var fixtureAnchor: GlassAnchor
        lateinit var fixtureBackdrop: FrostedBackdrop
        var menuMaterial: Pair<GlassPopupFlight, FrostedBackdrop?>? = null
        val cleanPageMutant = InstrumentationRegistry.getArguments().getString("r1CleanPageMutant") == "true"
        var leaveClicked = false
        lateinit var baseScene: androidx.compose.ui.graphics.layer.GraphicsLayer
        var baseScenePixels: Bitmap? = null
        var activityWindow: Window? = null
        var originalNavigationContrast: Boolean? = null
        val enforcedContrast = InstrumentationRegistry.getArguments().getString("r1NavigationContrastMutant") == "enforced"
        var navigationInsetTop = Int.MIN_VALUE
        val contrastFailures = JSONArray()
        var matchingContrastFrame = false
        var open by mutableStateOf(false)
        var leaving by mutableStateOf(false)
        val cycleFixture = InstrumentationRegistry.getArguments().getString("r1BackdropCycleMutant") == "ancestor"
        var phaseControl = JSONArray()
        var receipts = JSONArray()
        try {
            preferences.write(InterfaceSettings(glass = GlassAppearance(renderer = mode)))
            compose.setContent { LocalicaTheme { key(trial) {
                clock = io.github.anastaci1a.localica.ui.engine.LocalUiClock.current
                host = LocalView.current
                home = rememberGlassPopupHome(leaving)
                val page = rememberFrostedBackdrop(home, dark = false).also { fixtureBackdrop = it }
                baseScene = rememberGraphicsLayer()
                BackdropLease(home.backdrop)
                CompositionLocalProvider(LocalGlassMorphWorkObserver provides { kind, window, phase ->
                    if (kind == "dialog" && window != null && popup == null) popup = window
                    if (kind == "geometry" && window === popup && phase != null) geometryProgress = phase
                }) {
                    // Base's home recorded this complete scene: page plus chrome,
                    // before any non-chrome flight was drawn above the home.
                    Box(Modifier.fillMaxSize().drawWithContent {
                        baseScene.record { this@drawWithContent.drawContent() }
                        drawLayer(baseScene)
                    }) {
                    R1ReferenceHome(home, chrome = {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                            Box(Modifier.fillMaxWidth().height(64.dp).testTag("overlap-tabs")) {
                                SlidingTabs(listOf(MainTab.Home, MainTab.Library), MainTab.Home, { 0f }, source = page) {}
                            }
                        }
                    }) {
                        Box(Modifier.fillMaxSize()
                            .then(if (cycleFixture) Modifier.recordFrostedBackdrop(page) else Modifier)
                            .background(androidx.compose.ui.graphics.Color.White), contentAlignment = Alignment.BottomEnd) {
                            // The sampled page is a sibling: recording the pill inside its own source is a RenderNode cycle.
                            if (!cycleFixture) Box(Modifier.fillMaxSize().recordFrostedBackdrop(page)
                                .background(androidx.compose.ui.graphics.Color.White))
                            R1BasePopupActionPill(rememberGlassAnchor().also { fixtureAnchor = it }, source = page,
                                modifier = Modifier.size(104.dp, 64.dp),
                                trailingLabel = "Open overlap menu", menuVisible = open, onMenuDismiss = { open = false },
                                menuContent = {
                                    val material = LocalFrostedBackdrop.current
                                    SideEffect { menuMaterial = home.flights.singleOrNull()?.let { it to material } }
                                    ActionMenuItem("Leave page", icon = AppIcon.TabFolders, onClick = { leaveClicked = true; open = false; leaving = true })
                                    Box(Modifier.fillMaxWidth().height(240.dp).background(androidx.compose.ui.graphics.Color.Green))
                                }, onSearch = {}, onMore = { open = true })
                        }
                    }
                    }
                }
            } } }
            val offsetMs = 0L
            compose.waitForIdle()
            val activity = compose.runOnUiThread {
                var owner = host.context
                while (owner is ContextWrapper && owner !is Activity) owner = owner.baseContext
                (owner as Activity).window
            }
            if (Build.VERSION.SDK_INT >= 29) {
                val tabsBefore = compose.onNodeWithTag("overlap-tabs").fetchSemanticsNode().boundsInRoot
                val hostBefore = IntArray(2).also { compose.runOnUiThread { host.getLocationOnScreen(it) } }
                compose.runOnUiThread {
                    activityWindow = activity
                    if (originalNavigationContrast == null) originalNavigationContrast = activity.isNavigationBarContrastEnforced
                    activity.isNavigationBarContrastEnforced = enforcedContrast
                }
                compose.waitForIdle()
                val tabsAfter = compose.onNodeWithTag("overlap-tabs").fetchSemanticsNode().boundsInRoot
                val hostAfter = IntArray(2).also { compose.runOnUiThread { host.getLocationOnScreen(it) } }
                val navigation = compose.runOnUiThread {
                    val decor = activity.decorView
                    val rootAt = IntArray(2).also { decor.getLocationOnScreen(it) }
                    val bottom = requireNotNull(androidx.core.view.ViewCompat.getRootWindowInsets(decor))
                        .getInsets(androidx.core.view.WindowInsetsCompat.Type.navigationBars()).bottom
                    JSONObject().put("originalEnforced", originalNavigationContrast)
                        .put("enforcedForCapture", activity.isNavigationBarContrastEnforced)
                        .put("navigationInsetBottom", bottom)
                        .put("navigationInsetTopScene", rootAt[1] + decor.height - bottom - home.currentOrigin.y)
                }
                navigationInsetTop = navigation.getDouble("navigationInsetTopScene").toInt()
                navigation.put("hostBefore", JSONArray(hostBefore.toList())).put("hostAfter", JSONArray(hostAfter.toList()))
                    .put("tabBoundsBefore", tabsBefore.toString()).put("tabBoundsAfter", tabsAfter.toString())
                File(output, "navigation-contrast.json").writeText(navigation.toString(2))
                assertArrayEquals("Navigation contrast keeps host location", hostBefore, hostAfter)
                assertEquals("Navigation contrast keeps tab bounds", tabsBefore, tabsAfter)
                if (Build.VERSION.SDK_INT == 36) assertEquals("API 36 navigation inset top", 1196f,
                    navigation.getDouble("navigationInsetTopScene").toFloat(), 0f)
            }
            original(activity, output, "base-complete-root").recycle()
            val independence = compose.runOnUiThread { captureR1BackdropIndependence(fixtureBackdrop, fixtureAnchor) }
            assertR1BackdropIndependent(independence, output)
            baseScenePixels = r1InspectionBitmap(runBlocking { baseScene.toImageBitmap() }.asAndroidBitmap(), output, "base-complete-scene")
            File(output, "base-complete-scene.png").outputStream().use {
                assertTrue(requireNotNull(baseScenePixels).compress(Bitmap.CompressFormat.PNG, 100, it))
            }
            File(output, "trigger-pre-open.json").writeText(JSONObject()
                .put("hidden", fixtureAnchor.hidden).put("quietPolicy", fixtureAnchor.quiet)
                .put("surfaceShadowEnabled", true).put("bounds", fixtureAnchor.bounds.toString())
                .put("scene", "base-complete-scene.png").toString(2))
            compose.mainClock.autoAdvance = false
            frames.openReferenceTrigger("Open overlap menu")
            compose.waitUntil(10000) {
                compose.mainClock.advanceTimeByFrame(); compose.waitForIdle()
                popup?.decorView?.hasWindowFocus() == true && home.flights.size == 1 && geometryProgress >= .999f
            }
            val window = requireNotNull(popup)
            val flight = compose.runOnUiThread { home.flights.single() }
            // Published base has one ordinary projection path, above chrome.
            val tabBounds = compose.onNodeWithTag("overlap-tabs").fetchSemanticsNode().boundsInRoot
            // The pre-open scene remains evidence of the retiring trigger field.
            // Freeze the independent complete-home record at the same hidden-trigger state as return.
            original(activity, output, "reference-ready-root").recycle()
            val referenceState = compose.runOnUiThread {
                JSONObject().put("phase", geometryProgress).put("candidateFlightPhase", r1CandidateFlightPhase(flight) ?: JSONObject.NULL).put("popupFocused", window.decorView.hasWindowFocus())
                    .put("triggerHidden", fixtureAnchor.hidden).put("handedOff", flight.handedOff)
                    .put("wasProjected", flight.wasProjected).put("homeDeparting", home.departing)
                    .put("leavePageClicked", leaveClicked)
            }
            File(output, "reference-freeze-state.json").writeText(referenceState.toString(2))
            assertTrue("R1 reference freezes a fully open menu", referenceState.getDouble("phase") >= .999)
            assertTrue("R1 reference freezes the focused popup", referenceState.getBoolean("popupFocused"))
            assertTrue("R1 reference freezes after trigger hiding", referenceState.getBoolean("triggerHidden"))
            assertFalse("R1 reference precedes handoff", referenceState.getBoolean("handedOff"))
            assertFalse("R1 reference precedes projection", referenceState.getBoolean("wasProjected"))
            assertFalse("R1 reference precedes page departure", referenceState.getBoolean("homeDeparting"))
            assertFalse("R1 reference precedes Leave page input", referenceState.getBoolean("leavePageClicked"))
            val preOpenPixels = requireNotNull(baseScenePixels)
            baseScenePixels = r1InspectionBitmap(runBlocking { baseScene.toImageBitmap() }.asAndroidBitmap(), output, "base-return-reference-scene")
            try {
                File(output, "base-return-reference-scene.png").outputStream().use {
                    assertTrue(requireNotNull(baseScenePixels).compress(Bitmap.CompressFormat.PNG, 100, it))
                }
                val hostAt = IntArray(2).also { compose.runOnUiThread { host.getLocationOnScreen(it) } }
                verifyR1ReferenceClass(preOpenPixels, requireNotNull(baseScenePixels), tabBounds,
                    hostAt, home.currentOrigin, context.resources.displayMetrics.densityDpi, output)
            } finally { preOpenPixels.recycle() }
            compose.onNodeWithText("Leave page").performClick()
            // A mounted departing page deliberately holds its menu at full size.
            // Cancel that page transition only after its inline draw is admitted;
            // the dismissed menu then completes a real projected return.
            compose.waitUntil(10000) {
                compose.mainClock.advanceTimeByFrame(); compose.waitForIdle()
                flight.handedOff && flight.wasProjected
            }
            // The trigger's retained SurfaceView follows the popup's physical MotionRect.
            // Read that existing bound, never the fixed full-screen recording extent.
            fun panelBounds(): List<Int> = compose.runOnUiThread {
                val node = requireNotNull(fixtureAnchor.returningNode ?: fixtureAnchor.surfaceNode)
                val pixels = glassMorphPixelBounds(node.transform.value())
                listOf(pixels.left, pixels.top, pixels.right, pixels.bottom)
            }
            val holdBounds = panelBounds()
            val releaseBefore = compose.runOnUiThread { clock.stamp.nanos }
            if (offsetMs > 0) compose.mainClock.advanceTimeBy(offsetMs, ignoreFrameDuration = true)
            val intent = compose.runOnUiThread {
                JSONObject().put("requestedOffsetMs", offsetMs).put("virtualClockMs", compose.mainClock.currentTime)
                    .put("presentedBeforeOffsetNanos", releaseBefore).put("presentedNanos", clock.stamp.nanos)
                    .put("intentNanos", clock.stampNow.nanos)
                    .put("actualIntentOffsetNanos", clock.stampNow.nanos - clock.stamp.nanos)
                    .also { leaving = false }
            }
            File(output, "start-offset.json").writeText(intent.toString(2))
            for (frame in 0 until 96) {
                // Add projected sub-full frames above .8; retain the .1 lower bound and 96-step budget.
                compose.mainClock.advanceTimeBy(4, ignoreFrameDuration = true); compose.waitForIdle()
                val attached = window.decorView.isAttachedToWindow
                val phase = geometryProgress
                val candidatePhase = r1CandidateFlightPhase(flight)
                phaseControl.put(JSONObject().put("frame", frame).put("clockMs", compose.mainClock.currentTime)
                    .put("presentedNanos", compose.runOnUiThread { clock.stamp.nanos }).put("flightPhase", candidatePhase ?: JSONObject.NULL).put("phaseBits", phase.toRawBits()).put("geometryPhase", geometryProgress)
                    .put("attached", attached).put("prepared", flight.prepared).put("triggerHidden", fixtureAnchor.hidden))
                File(output, "phase-control.json").writeText(JSONObject().put("source", BuildConfig.SOURCE_SHA)
                    .put("renderer", mode.name).put("stepMs", 4).put("samples", phaseControl).put("qualifiedFrames", qualifiedPhases.size).put("distinctPresentedPhaseBits", JSONArray(qualifiedPhases.toList())).toString(2))
                if (!attached) break
                if (phase < .1f || phase >= 1f || !flight.prepared || !flight.handedOff || leaving || home.departing) continue
                val bounds = panelBounds()
                val moving = bounds != holdBounds
                val modal = original(window, output, "return-$frame-popup")
                val root = original(activity, output, "return-$frame-root")
                val layer = r1InspectionBitmap(runBlocking { requireNotNull(flight.layer).toImageBitmap() }.asAndroidBitmap(), output, "return-$frame-flight-layer")
                val sourceSelection = compose.runOnUiThread {
                    val captured = requireNotNull(menuMaterial) { "R1 menu material belongs to one flight" }
                    assertSame("R1 material belongs to the flight under test", flight, captured.first)
                    val parent = requireNotNull(requireNotNull(captured.second).parent) { "R1 sampling parent must not be null" }
                    assertFalse("R1 sampling parent must not be retired", parent.isRetired)
                    Triple(parent, requireNotNull(home.backdrop), parent === home.backdrop)
                }
                val source = r1InspectionBitmap(runBlocking { sourceSelection.first.layer.toImageBitmap() }.asAndroidBitmap(), output, "return-$frame-sampled-scene")
                val oldSource = if (cleanPageMutant) r1InspectionBitmap(runBlocking { sourceSelection.second.layer.toImageBitmap() }.asAndroidBitmap(), output, "return-$frame-old-home-scene") else null
                try {
                    if (!flight.handedOff) continue
                    val hostAt = IntArray(2); val rootAt = IntArray(2)
                    compose.runOnUiThread { host.getLocationOnScreen(hostAt); activity.decorView.getLocationOnScreen(rootAt) }
                    File(output, "return-$frame-flight-layer.png").outputStream().use { assertTrue(layer.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                    File(output, "return-$frame-sampled-scene.png").outputStream().use { assertTrue(source.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                    var sourcePixels = 0; var sourceChanged = 0; var chromePixels = 0
                    var oldSourceChanged = 0
                    // The independent base scene was frozen before opening. The
                    // left tabs stay still; exclude the trigger at the right.
                    for (y in tabBounds.top.toInt() until tabBounds.bottom.toInt())
                        for (x in tabBounds.left.toInt() until tabBounds.center.x.toInt()) {
                            val sx = x + hostAt[0] - home.currentOrigin.x.toInt()
                            val sy = y + hostAt[1] - home.currentOrigin.y.toInt()
                            val expected = requireNotNull(baseScenePixels).getPixel(sx, sy)
                            if (expected != Color.WHITE) chromePixels++
                            if (source.getPixel(sx, sy) != expected) sourceChanged++
                            if (oldSource != null && oldSource.getPixel(sx, sy) != expected) oldSourceChanged++
                            sourcePixels++
                        }
                    assertTrue("The base recording contains real tab ink", chromePixels > 100)
                    File(output, "return-$frame-sampling-parent.json").writeText(JSONObject()
                        .put("phase", phase).put("phaseBits", phase.toRawBits())
                        .put("parentIsHomeBackdrop", sourceSelection.third).put("cleanPageMutant", cleanPageMutant)
                        .put("actualReadChanged", sourceChanged).put("actualReadWouldPass", sourceChanged == 0)
                        .put("oldReadChanged", if (cleanPageMutant) oldSourceChanged else JSONObject.NULL)
                        .put("oldReadWouldPass", if (cleanPageMutant) oldSourceChanged == 0 else JSONObject.NULL).toString(2))
                    assertEquals("R1: a departing non-chrome menu samples the base scene including tabs", 0, sourceChanged)
                    var sampled = 0; var bad = 0
                    val badRows = sortedSetOf<Int>()
                    for (y in tabBounds.top.toInt() until tabBounds.bottom.toInt() step 2)
                        for (x in tabBounds.left.toInt() until tabBounds.right.toInt() step 2) {
                            val lx = x + hostAt[0] - flight.origin.x.toInt()
                            val ly = y + hostAt[1] - flight.origin.y.toInt()
                            if (lx !in 0 until layer.width || ly !in 0 until layer.height) continue
                            val expected = layer.getPixel(lx, ly)
                            if (Color.alpha(expected) < 254 || Color.green(expected) < 200 || Color.red(expected) > 40 || Color.blue(expected) > 40) continue
                            val actual = root.getPixel(x + hostAt[0] - rootAt[0], y + hostAt[1] - rootAt[1])
                            if (channels.maxOf { abs(it(expected) - it(actual)) } > 14) { bad++; badRows.add(y) }
                            sampled++
                        }
                    receipts.put(JSONObject().put("frame", frame).put("clockMs", compose.mainClock.currentTime)
                        .put("phase", phase).put("phaseBits", phase.toRawBits()).put("pixels", sampled).put("changed", bad)
                        .put("baseSourcePixels", sourcePixels).put("baseSourceChanged", sourceChanged)
                        .put("panelPixelBounds", JSONArray(bounds)).put("holdPixelBounds", JSONArray(holdBounds))
                        .put("moving", moving).put("prepared", flight.prepared).put("handedOff", flight.handedOff)
                        .put("leaving", leaving).put("homeDeparting", home.departing).put("badRows", JSONArray(badRows.toList())))
                    File(output, "frames.json").writeText(JSONObject().put("source", BuildConfig.SOURCE_SHA)
                        .put("renderer", mode.name).put("samples", receipts).toString(2))
                    if (enforcedContrast) assertTrue("Every enforced-contrast changed pixel is inside the navigation inset: $badRows",
                        badRows.all { it >= navigationInsetTop })
                    if (Build.VERSION.SDK_INT == 36 && !enforcedContrast && phase.toRawBits() == .73084176f.toRawBits())
                        assertEquals("Positive contrast keeps the matching selected class", 2594, sampled)
                    if (sampled > 10) {
                        if (moving && phase.toRawBits() !in qualifiedPhases && bounds !in qualifiedBounds) {
                            qualifiedPhases.add(phase.toRawBits())
                            qualifiedBounds.add(bounds)
                            if (phase in .1f.. .8f) originalWindowCount++
                        }
                        if (enforcedContrast) {
                            if (bad > max(4, sampled / 1000)) contrastFailures.put(JSONObject()
                                .put("phase", phase).put("changed", bad).put("pixels", sampled).put("badRows", JSONArray(badRows.toList())))
                            if (phase.toRawBits() == .73084176f.toRawBits()) {
                                assertEquals("Enforced contrast keeps the matching selected class", 2594, sampled)
                                assertEquals("Enforced contrast reproduces the matching changed class", 942, bad)
                                matchingContrastFrame = true
                            }
                        } else assertTrue("Non-chrome departing menu stays above actual tabs: $bad/$sampled", bad <= max(4, sampled / 1000))
                    }
                } finally { modal.recycle(); root.recycle(); layer.recycle(); source.recycle(); oldSource?.recycle() }
            }
            File(output, "phase-control.json").writeText(JSONObject().put("source", BuildConfig.SOURCE_SHA)
                .put("renderer", mode.name).put("stepMs", 4).put("samples", phaseControl).put("qualifiedFrames", qualifiedPhases.size).put("distinctPresentedPhaseBits", JSONArray(qualifiedPhases.toList())).toString(2))
            trials.put(JSONObject().put("requestedOffsetMs", offsetMs).put("intent", intent).put("samples", receipts))
            File(outputRoot, "offset-matrix.json").writeText(JSONObject().put("source", BuildConfig.SOURCE_SHA)
                .put("renderer", mode.name).put("trials", trials).put("distinctQualifiedPhaseBits", JSONArray(qualifiedPhases.toList()))
                .put("distinctQualifiedPhases", qualifiedPhases.size).put("originalWindowCount", originalWindowCount)
                .put("distinctQualifiedBounds", JSONArray(qualifiedBounds.map { JSONArray(it) })).toString(2))
            if (enforcedContrast) {
                File(output, "enforced-contrast-result.json").writeText(JSONObject()
                    .put("insetTop", navigationInsetTop).put("failures", contrastFailures)
                    .put("matchingFrameVerified", matchingContrastFrame).toString(2))
                assertTrue("Enforced contrast reaches the original .7308 matching frame", matchingContrastFrame)
                assertTrue("Enforced contrast must reproduce an above-tabs failure", contrastFailures.length() > 0)
                fail("Non-chrome departing menu stays above actual tabs: enforced navigation contrast reproduced " + contrastFailures.length() + " failing captures")
            }
            assertTrue("At least one counted return phase remains in the original .1-.8 window", originalWindowCount >= 1)
            assertTrue("Non-chrome menu overlaps tabs at three distinct presented return phases: $trials", qualifiedPhases.size >= 3)
        } finally {
            if (Build.VERSION.SDK_INT >= 29) originalNavigationContrast?.let { restore ->
                compose.runOnUiThread { activityWindow?.isNavigationBarContrastEnforced = restore }
            }
            baseScenePixels?.recycle(); compose.mainClock.autoAdvance = true; preferences.write(previous)
        }
    }

    private fun original(window: Window, output: File, name: String): Bitmap = frames.capture(window, output, name)
}
