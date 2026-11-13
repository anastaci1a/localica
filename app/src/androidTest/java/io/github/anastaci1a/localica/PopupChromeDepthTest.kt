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
import io.github.anastaci1a.localica.ui.components.TriggerRole
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

/** #926: the real overflow stays above the bars on a plain close; only a page takeover moves it into the page. */
class PopupChromeDepthTest {
    @get:Rule val compose = localicaComposeRule()
    private val frames by lazy { PopupWindowEvidence(compose) }
    private val channels: List<(Int)->Int> = listOf({ Color.red(it) }, { Color.green(it) }, { Color.blue(it) })

    @org.junit.After fun removeMarkers() = frames.dispose()

    @Test fun overflowSettingsHandsOffUnderTheSettingsPage() { overflow("Settings") }
    @Test fun plainOverflowCloseStaysAboveMiniPlayerAndTabsScan() { overflow("Scan music") }
    @Test fun plainOverflowCloseStaysAboveMiniPlayerAndTabsBack() { overflow("Back") }
    @Test fun plainOverflowCloseStaysAboveMiniPlayerAndTabsOutsideTap() { overflow("Outside tap") }
    @Test fun normalHomeProjectionIsOutsideItsSampledBackdrop() { cleanBackdrop(GlassRendererMode.Normal) }
    @Test fun normalDepartingBrowseMenuStaysAboveTabs() { nonChromeMenu(GlassRendererMode.Normal) }
    @Test fun normalSelectionPillKeepsItsShellDepth() { selectionPill(GlassRendererMode.Normal) }
    @Test fun collapsedShellControlsRemainAccessible() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = InterfacePreferences(context)
        val previous = preferences.read()
        val output = File(nativeEvidenceDirectory("popup-chrome-depth", context.getExternalFilesDir(null)), "shell-accessibility").apply { mkdirs() }
        lateinit var host: View
        try {
            preferences.write(InterfaceSettings(reducedMotion = true))
            val track = Track("identity:accessible", "Accessible song", artist = "Test", fileName = "accessible.mp3", relativePath = "accessible.mp3")
            compose.setContent { LocalicaTheme {
                host = LocalView.current
                LocalicaScreen(LibraryUiState(LibrarySnapshot(tracks = listOf(track)), loading = false),
                    PlaybackState(connected = true, trackId = track.id, trackUri = track.id, title = track.title, durationMs = 120000),
                    {}, {}, {}, {}, {}, {}, playlist = PlaylistState(loading = false))
            } }
            compose.onNodeWithContentDescription("Play", useUnmergedTree = true).assertExists()
            compose.onNodeWithTag("player-title", useUnmergedTree = true).assertExists()
            val activity = compose.runOnUiThread {
                var owner = host.context
                while (owner is ContextWrapper && owner !is Activity) owner = owner.baseContext
                (owner as Activity).window
            }
            original(activity, output, "collapsed-mini-player").recycle()
            compose.onNodeWithTag("player-surface").performTouchInput { swipeUp() }
            compose.waitForIdle()
            compose.onNodeWithContentDescription("Open queue", useUnmergedTree = true).assertExists()
            original(activity, output, "collapsed-queue-bar").recycle()
            File(output, "semantics.txt").writeText(compose.onRoot(useUnmergedTree = true).printToString())
        } finally { preferences.write(previous) }
    }

    /** #926: a plain close never leaves its window, so a re-tap at any point of the return has no projection to clear. */
    @Test fun plainMoreRetapStaysInItsWindowEarly() = reopenMore(.92f, takeover = false)
    @Test fun plainMoreRetapStaysInItsWindowMid() = reopenMore(.7f, takeover = false)
    @Test fun plainMoreRetapStaysInItsWindowLate() = reopenMore(.45f, takeover = false)
    @Test fun plainMoreRetapStaysInItsWindowTail() = reopenMore(.2f, takeover = false)
    /** #925: a takeover that reverts mid-return, then a re-tap. */
    @Test fun earlyMoreReopenClearsProjectionBeforeDialogDraw() = reopenMore(.92f)
    @Test fun lateMoreReopenClearsProjectionBeforeDialogDraw() = reopenMore(.45f)
    /** #925: the opposite native order. A cycle crashes the RenderThread on API 33+. */
    @Test fun dialogFirstMoreReopenSamplesOutsideItsProjectingHome() = reopenMore(.45f, dialogFirst = true)

    private fun reopenMore(target: Float, dialogFirst: Boolean = false, takeover: Boolean = true) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = InterfacePreferences(context)
        val previous = preferences.read()
        val output = File(nativeEvidenceDirectory("popup-chrome-depth", context.getExternalFilesDir(null)),
            "reopen-$target-${if (takeover) "takeover" else "plain"}").apply { mkdirs() }
        lateinit var host: View
        lateinit var home: GlassPopupHome
        lateinit var anchor: GlassAnchor
        val phase = { anchor.contact.expansion.value }
        var popup: Window? = null
        var open by mutableStateOf(false)
        var departing by mutableStateOf(false)
        var blockedTree: ViewTreeObserver? = null
        var blocker: ViewTreeObserver.OnPreDrawListener? = null
        var nativeDraws = 0
        var blockedDraws = 0
        var composedOpen = false
        val nativeWatch = ViewTreeObserver.OnDrawListener { nativeDraws++ }
        try {
            preferences.write(InterfaceSettings(reducedMotion = false, glass = GlassAppearance(renderer = GlassRendererMode.Normal)))
            compose.setContent { LocalicaTheme {
                host = LocalView.current
                home = rememberGlassPopupHome(departing)
                val page = rememberFrostedBackdrop(home, dark = false)
                // As in LocalicaScreen, the ambient modal scene encloses the popup home.
                val modal = rememberFrostedBackdrop(page, dark = false)
                anchor = rememberGlassAnchor()
                CompositionLocalProvider(LocalModalBackdrop provides modal, LocalGlassMorphWorkObserver provides { kind, window, _ ->
                    if (kind == "dialog" && window != null) popup = window
                }) {
                    val observedOpen = open
                    SideEffect { composedOpen = observedOpen }
                    Box(Modifier.fillMaxSize().recordFrostedBackdrop(modal)) { GlassPopupHome(home, chrome = {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomEnd) {
                            PopupActionPill(anchor, page, role = TriggerRole.Navigation,
                                menuVisible = open, onMenuDismiss = { open = false },
                                menuContent = { ActionMenuItem("Close fixture", icon = AppIcon.ActionClose) { open = false } },
                                onSearch = {}, onMore = { open = true })
                        }
                    }) { Box(Modifier.fillMaxSize().recordFrostedBackdrop(page).background(androidx.compose.ui.graphics.Color.White)) } }
                }
            } }
            compose.mainClock.autoAdvance = false
            compose.runOnUiThread { open = true }
            compose.waitUntil(10000) {
                compose.mainClock.advanceTimeByFrame(); compose.waitForIdle()
                home.flights.isNotEmpty() && phase() >= .999f && popup != null
            }
            val flight = compose.runOnUiThread { home.flights.single() }
            val window = requireNotNull(popup)
            // Settings-style takeover: the home departs with the close, then returns before the re-tap.
            compose.runOnUiThread { departing = takeover; open = false }
            compose.waitUntil(10000) {
                compose.mainClock.advanceTimeBy(1); compose.waitForIdle()
                phase() <= target && (!takeover || flight.prepared && flight.handedOff)
            }
            if (!takeover) {
                assertFalse("#926 a plain close is never handed to the home", flight.handedOff || flight.wasProjected)
                assertTrue("The return is still in its native window", window.decorView.isAttachedToWindow)
                compose.runOnUiThread { open = true }
                compose.waitUntil(10_000) {
                    compose.mainClock.advanceTimeByFrame(); compose.waitForIdle()
                    assertFalse("#926 the re-tapped popup never projects", flight.handedOff || flight.wasProjected)
                    phase() >= .999f
                }
                File(output, "plain-retap.json").writeText(JSONObject().put("target", target)
                    .put("source", BuildConfig.SOURCE_SHA).put("reopened", phase()).toString(2))
                return
            }
            assertTrue("Reopen starts from an actual prepared home projection", flight.wasProjected)
            val activity = compose.runOnUiThread {
                var owner = host.context
                while (owner is ContextWrapper && owner !is Activity) owner = owner.baseContext
                (owner as Activity).window
            }
            compose.runOnUiThread {
                blockedTree = (if (dialogFirst) activity else window).decorView.viewTreeObserver
                blocker = ViewTreeObserver.OnPreDrawListener { blockedDraws++; false }
                blockedTree!!.addOnPreDrawListener(blocker)
                window.decorView.viewTreeObserver.addOnDrawListener(nativeWatch)
                departing = false; open = true
                window.decorView.invalidate()
            }
            compose.waitUntil(10_000) {
                compose.mainClock.advanceTimeByFrame()
                compose.runOnUiThread { composedOpen && (!dialogFirst || nativeDraws > 0) }
            }
            if (dialogFirst) {
                // The Dialog drew its reopened layers while the Activity's committed frame still projects the flight.
                assertTrue("Activity pre-draw was actually intercepted", blockedDraws > 0)
                assertTrue("The latch holds until the home commits a frame without the flight", flight.wasProjected)
                compose.runOnUiThread { blockedTree!!.removeOnPreDrawListener(blocker); activity.decorView.invalidate() }
                compose.waitUntil(10_000) {
                    compose.mainClock.advanceTimeByFrame()
                    compose.runOnUiThread { !flight.wasProjected }
                }
                return
            }
            compose.runOnUiThread { assertTrue("Reopen composition completed before the Activity draw", composedOpen) }
            // A committed Activity frame with the Dialog pre-draw blocked forces
            // the ordering that exposed B1; no race between native windows.
            original(activity, output, "activity-first-reopen").recycle()
            val result = compose.runOnUiThread {
                JSONObject().put("target", target).put("phase", phase())
                    .put("prepared", flight.prepared).put("handedOff", flight.handedOff)
                    .put("blockedNativePreDraws", blockedDraws).put("nativeDraws", nativeDraws)
                    .put("source", BuildConfig.SOURCE_SHA)
            }
            File(output, "ordering.json").writeText(result.toString(2))
            assertTrue("Dialog pre-draw was actually intercepted", blockedDraws > 0)
            assertEquals("Dialog drawing is blocked until after the Activity receipt", 0, nativeDraws)
            assertFalse("B1 reopen clears prepared before the Activity-first draw", flight.prepared)
            assertFalse("B1 Activity-first reopen must not hand off the stale closing layer", flight.handedOff)
        } finally {
            compose.runOnUiThread {
                blockedTree?.takeIf { it.isAlive }?.let { tree ->
                    blocker?.let { tree.removeOnPreDrawListener(it) }
                    tree.removeOnDrawListener(nativeWatch)
                    popup?.decorView?.invalidate()
                }
            }
            compose.mainClock.autoAdvance = true
            preferences.write(previous)
        }
    }

    @Test fun normalSelectionPillPixelsCoverOverlappingTabs() = selectionOverlap(GlassRendererMode.Normal)

    private fun selectionOverlap(mode: GlassRendererMode) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = InterfacePreferences(context)
        val previous = preferences.read()
        val output = File(nativeEvidenceDirectory("popup-chrome-depth", context.getExternalFilesDir(null)),
            "${mode.name}/selection-stacking").apply { mkdirs() }
        lateinit var host: View
        var tabsVisible by mutableStateOf(false)
        var selectionVisible by mutableStateOf(true)
        val track = Track("identity:overlap", "Overlap song", fileName = "overlap.mp3", relativePath = "overlap.mp3")
        try {
            preferences.write(InterfaceSettings(reducedMotion = true, glass = GlassAppearance(renderer = mode)))
            compose.setContent { LocalicaTheme {
                host = LocalView.current
                val home = rememberGlassPopupHome(false)
                val page = rememberFrostedBackdrop(home, dark = false)
                val panel = rememberFloatingSelectionPanel(selectionVisible, true)
                CompositionLocalProvider(LocalFrostedBackdrop provides page,
                    LocalGlassAppearance provides GlassAppearance(renderer = mode,
                        tabStyle = io.github.anastaci1a.localica.navigation.TabLensStyle.Classic,
                        classicOpacity = 1f, blur = 0f, whiteTint = 0f, contrast = 0f, shadowStrength = 0f)) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        // Deliberate collision, as in SelectorStackingTest: use the
                        // shell's real shared controls and ordering with no motion override.
                        Box(Modifier.size(308.dp, 180.dp)) {
                        GlassPopupHome(home, chrome = {
                            if (tabsVisible) Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(64.dp).testTag("overlap-tabs")) {
                                SlidingTabs(listOf(MainTab.Home, MainTab.Library), MainTab.Home, { 0f }, source = page) {}
                            }
                            FloatingSelectionPanel(panel, Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
                                SelectionActions(listOf(io.github.anastaci1a.localica.library.SongRow(track.id, track)), setOf(track.id), {}, null, null, PlaylistState(loading = false),
                                    minimized = true, onExpand = {})
                            }
                        }) {
                            Box(Modifier.fillMaxSize()) {
                                Box(Modifier.fillMaxSize().recordFrostedBackdrop(page).background(androidx.compose.ui.graphics.Color.Blue))
                                Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.White))
                            }
                        }
                        }
                    }
                }
            } }
            compose.onNodeWithTag("selection-minimized").assertIsDisplayed()
            val pill = compose.onNodeWithTag("selection-minimized").fetchSemanticsNode().boundsInRoot
            val activity = compose.runOnUiThread {
                var owner = host.context
                while (owner is ContextWrapper && owner !is Activity) owner = owner.baseContext
                (owner as Activity).window
            }
            val alone = original(activity, output, "selection-only")
            try {
                compose.runOnIdle { tabsVisible = true; selectionVisible = false }
                compose.waitForIdle()
                val tabs = original(activity, output, "tabs-only")
                try {
                    compose.runOnIdle { selectionVisible = true }
                    compose.onNodeWithTag("selection-minimized").assertIsDisplayed()
                    val combined = original(activity, output, "selection-over-tabs")
                    try {
                        val tab = compose.onNodeWithTag("overlap-tabs").fetchSemanticsNode().boundsInRoot
                        val location = IntArray(2).also { compose.runOnUiThread { host.getLocationInWindow(it) } }
                        val overlap = pill.intersect(tab)
                        var discriminating = 0
                        var wrong = 0
                        for (y in overlap.top.toInt() until overlap.bottom.toInt()) for (x in overlap.left.toInt() until overlap.right.toInt()) {
                            val px = x + location[0]; val py = y + location[1]
                            val expected = alone.getPixel(px, py)
                            val behind = tabs.getPixel(px, py)
                            if (expected == Color.BLUE && abs(Color.blue(expected) - Color.blue(behind)) > 40) {
                                discriminating++
                                if (combined.getPixel(px, py) != expected) wrong++
                            }
                        }
                        File(output, "stacking.json").writeText(JSONObject().put("source", BuildConfig.SOURCE_SHA)
                            .put("overlap", overlap.toString()).put("discriminatingPixels", discriminating)
                            .put("wrongForegroundPixels", wrong).toString(2))
                        assertTrue("Selection stacking fixture reaches real overlapping control pixels", discriminating >= 100)
                        assertEquals("S3: selection pill pixels cover tabs at the shell sibling depth", 0, wrong)
                    } finally { combined.recycle() }
                } finally { tabs.recycle() }
            } finally { alone.recycle() }
        } finally { preferences.write(previous) }
    }

    private fun selectionPill(mode: GlassRendererMode) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = InterfacePreferences(context)
        val previous = preferences.read()
        val output = File(nativeEvidenceDirectory("popup-chrome-depth", context.getExternalFilesDir(null)),
            "${mode.name}/selection-pill").apply { mkdirs() }
        lateinit var host: View
        try {
            preferences.write(InterfaceSettings(tabs = listOf(MainTab.Albums, MainTab.Artists, MainTab.Songs, MainTab.Playlists),
                reducedMotion = true, glass = GlassAppearance(renderer = mode)))
            val tracks = (1..2).map { Track("identity:depth-$it", "Song $it", artist = "Shared artist", album = "Shared album",
                fileName = "$it.mp3", relativePath = "$it.mp3") }
            compose.setContent { LocalicaTheme {
                host = LocalView.current
                LocalicaScreen(LibraryUiState(LibrarySnapshot(tracks = tracks), loading = false), PlaybackState(connected = true),
                    {}, {}, {}, {}, {}, {}, playlist = PlaylistState(loading = false))
            } }
            compose.awaitRowActions("Shared album").performClick()
            compose.onNodeWithText("Select all songs").performClick()
            compose.onNodeWithText("2 selected").assertIsDisplayed()
            compose.waitUntil(5000) { compose.onAllNodesWithTag("glass-morph-surface").fetchSemanticsNodes().isEmpty() }
            compose.awaitExpectedWindowFocus(compose.onNodeWithContentDescription("Collapse selection"))
            compose.onNodeWithContentDescription("Collapse selection").performClick()
            val pill = compose.onNodeWithTag("selection-minimized").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            val tabs = compose.onNodeWithTag("tab-pill", useUnmergedTree = true).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            val activity = compose.runOnUiThread {
                var owner = host.context
                while (owner is ContextWrapper && owner !is Activity) owner = owner.baseContext
                (owner as Activity).window
            }
            original(activity, output, "selection-and-tabs").recycle()
            File(output, "scene.json").writeText(JSONObject().put("source", BuildConfig.SOURCE_SHA).put("renderer", mode.name)
                .put("api", Build.VERSION.SDK_INT).put("reducedMotion", true).put("selection", pill.toString()).put("tabs", tabs.toString())
                .put("density", host.resources.displayMetrics.density).toString(2))
        } finally { preferences.write(previous) }
    }

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
                    GlassPopupHome(home, chrome = {
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
                            PopupActionPill(rememberGlassAnchor().also { fixtureAnchor = it }, source = page,
                                modifier = Modifier.size(104.dp, 64.dp), role = TriggerRole.BrowseOptions,
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
                popup?.decorView?.hasWindowFocus() == true && home.flights.size == 1 && fixtureAnchor.contact.expansion.value >= .999f
            }
            val window = requireNotNull(popup)
            val flight = compose.runOnUiThread { home.flights.single() }
            assertFalse("BrowseOptions is a non-chrome role", fixtureAnchor.chromeTrigger)
            val tabBounds = compose.onNodeWithTag("overlap-tabs").fetchSemanticsNode().boundsInRoot
            // The pre-open scene remains evidence of the retiring trigger field.
            // Freeze the independent complete-home record at the same hidden-trigger state as return.
            original(activity, output, "reference-ready-root").recycle()
            val referenceState = compose.runOnUiThread {
                JSONObject().put("phase", fixtureAnchor.contact.expansion.value).put("popupFocused", window.decorView.hasWindowFocus())
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
                val phase = fixtureAnchor.contact.expansion.value
                phaseControl.put(JSONObject().put("frame", frame).put("clockMs", compose.mainClock.currentTime)
                    .put("presentedNanos", compose.runOnUiThread { clock.stamp.nanos }).put("flightPhase", phase).put("phaseBits", phase.toRawBits()).put("geometryPhase", geometryProgress)
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

    private fun cleanBackdrop(mode: GlassRendererMode) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = InterfacePreferences(context)
        val previous = preferences.read()
        val output = File(nativeEvidenceDirectory("popup-chrome-depth", context.getExternalFilesDir(null)),
            "${mode.name}/clean-backdrop").apply { mkdirs() }
        lateinit var host: View
        lateinit var home: GlassPopupHome
        lateinit var flight: GlassPopupFlight
        lateinit var page: FrostedBackdrop
        try {
            preferences.write(InterfaceSettings(glass = GlassAppearance(renderer = mode)))
            compose.setContent { LocalicaTheme {
                host = LocalView.current
                home = rememberGlassPopupHome(false)
                page = rememberFrostedBackdrop(home, dark = false)
                flight = remember { GlassPopupFlight() }
                val layer = rememberGraphicsLayer()
                BackdropLease(page)
                DisposableEffect(home, layer) {
                    flight.layer = layer; flight.prepared = true
                    home.flights.add(flight)
                    onDispose { home.flights.remove(flight) }
                }
                GlassPopupHome(home, chrome = {}) {
                    Box(Modifier.fillMaxSize().recordFrostedBackdrop(page).drawWithContent {
                        layer.record(size = IntSize(size.width.toInt(), size.height.toInt())) {
                            drawRect(androidx.compose.ui.graphics.Color.Magenta)
                        }
                        drawRect(androidx.compose.ui.graphics.Color.Green)
                    })
                }
            } }
            compose.waitForIdle()
            val activity = compose.runOnUiThread {
                var owner = host.context
                while (owner is ContextWrapper && owner !is Activity) owner = owner.baseContext
                (owner as Activity).window
            }
            for (frame in 0..2) {
                val root = original(activity, output, "clean-$frame-root")
                val sampled = r1InspectionBitmap(runBlocking { page.layer.toImageBitmap() }.asAndroidBitmap(), output, "clean-$frame-sampled-backdrop")
                try {
                    val rootAt = IntArray(2).also { compose.runOnUiThread { activity.decorView.getLocationOnScreen(it) } }
                    val x = sampled.width / 2; val y = sampled.height / 2
                    val rootX = (home.currentOrigin.x - rootAt[0]).toInt() + x
                    val rootY = (home.currentOrigin.y - rootAt[1]).toInt() + y
                    val samplePixel = sampled.getPixel(x, y)
                    val displayPixel = root.getPixel(rootX, rootY)
                    File(output, "clean-$frame.json").writeText(JSONObject().put("source", BuildConfig.SOURCE_SHA)
                        .put("renderer", mode.name).put("projected", flight.wasProjected)
                        .put("samplePixel", samplePixel).put("displayPixel", displayPixel).toString(2))
                    assertTrue("The home projection is actually displayed", flight.wasProjected)
                    assertEquals("Displayed flight control is magenta", Color.MAGENTA, displayPixel)
                    assertEquals("The home projection must not enter the backdrop sampled by its flight", Color.GREEN, samplePixel)
                } finally { root.recycle(); sampled.recycle() }
            }
        } finally { preferences.write(previous) }
    }

    private fun overflow(destination: String, mode: GlassRendererMode = GlassRendererMode.Normal) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = InterfacePreferences(context)
        val previous = preferences.read()
        val output = File(nativeEvidenceDirectory("popup-chrome-depth", context.getExternalFilesDir(null)),
            "${mode.name}/" + destination.replace(' ', '-')).apply { mkdirs() }
        val samples = JSONArray()
        val report = JSONObject().put("source", BuildConfig.SOURCE_SHA).put("destination", destination).put("renderer", mode.name)
            .put("samples", samples).put("status", "preparing").put("captureOrder", "popup-before-activity")
            .put("comparison", "Opaque overflow-window pixels over the bar regions, per frame")
        fun save() { File(output, "frames.json").writeText(report.toString(2)) }
        var baseline: Bitmap? = null
        try {
            preferences.write(InterfaceSettings(reducedMotion = false, animationSpeedTenths = 10, glass = GlassAppearance(renderer = mode)))
            val track = Track("file:///missing-667.mp3", "One", artist = "Test", album = "One",
                fileName = "missing-667.mp3", relativePath = "missing-667.mp3")
            lateinit var host: View
            lateinit var home: GlassPopupHome
            var popup: Window? = null
            var progress = 0f
            compose.setContent { LocalicaTheme {
                host = LocalView.current
                home = requireNotNull(LocalGlassPopupHome.current)
                CompositionLocalProvider(LocalGlassMorphWorkObserver provides { kind, window, p ->
                    if (kind == "dialog" && window != null && popup == null) popup = window
                    if (kind == "geometry" && window === popup && p != null) progress = p
                }) {
                    LocalicaScreen(LibraryUiState(LibrarySnapshot(tracks = listOf(track)), loading = false),
                        PlaybackState(connected = true, trackId = track.id, trackUri = track.id,
                            title = track.title, artist = track.artist, durationMs = 125000),
                        {}, {}, {}, {}, {}, {}, playlist = PlaylistState(loading = false))
                }
            } }
            compose.onNodeWithContentDescription("Library").performClick()
            compose.waitForIdle()
            compose.mainClock.advanceTimeBy(2000)
            compose.waitForIdle()
            val activity = compose.runOnUiThread {
                var owner = host.context
                while (owner is ContextWrapper && owner !is Activity) owner = owner.baseContext
                (owner as Activity).window
            }
            val origin = IntArray(2).also { compose.runOnUiThread { host.getLocationInWindow(it) } }
            val regions = listOf("mini-player" to "player-surface", "tabs" to "tab-pill", "utility" to "utility-pill").map { (name, tag) ->
                name to compose.onNodeWithTag(tag, useUnmergedTree = true).assertIsDisplayed()
                    .fetchSemanticsNode().boundsInRoot.translate(androidx.compose.ui.geometry.Offset(origin[0].toFloat(), origin[1].toFloat()))
            }
            report.put("chrome", JSONArray(regions.map { (name, r) -> JSONObject().put("name", name).put("bounds", r.toString()) }))
            compose.mainClock.autoAdvance = false
            baseline = original(activity, output, "baseline")
            compose.onNodeWithContentDescription("More").performClick()
            compose.waitUntil(10_000) {
                compose.mainClock.advanceTimeByFrame(); compose.waitForIdle()
                popup?.decorView?.isAttachedToWindow == true && progress >= .999f &&
                    compose.onAllNodesWithText("Scan music", substring = false).fetchSemanticsNodes().size == 1
            }
            val window = requireNotNull(popup)
            compose.mainClock.advanceTimeBy(1000); compose.waitForIdle()
            compose.waitUntil(5000) { window.decorView.hasWindowFocus() }
            val flight = compose.runOnUiThread {
                fun flights(h: GlassPopupHome): List<GlassPopupFlight> = h.flights.toList() + h.children.flatMap(::flights)
                flights(home).single()
            }
            val device = androidx.test.uiautomator.UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
            when (destination) {
                "Back" -> assertTrue("Back input is injected", device.pressKeyCode(android.view.KeyEvent.KEYCODE_BACK))
                "Outside tap" -> {
                    val screen = IntArray(2).also { compose.runOnUiThread { host.getLocationOnScreen(it) } }
                    assertTrue("Outside input is injected", device.click(screen[0] + host.width / 2, screen[1] + 64))
                }
                else -> compose.onNodeWithText(destination, substring = false).performClick()
            }
            // #926: a plain close stays in its native window above the bars until it retires. Only the
            // Settings takeover hands the popup to its departing home, which the Settings window covers.
            val takeover = destination == "Settings"
            var overChrome = 0
            var lowest = 1f
            var projected = false
            for (frame in 0 until 96) {
                if (frame < 8) compose.mainClock.advanceTimeBy(1) else compose.mainClock.advanceTimeByFrame()
                compose.waitForIdle()
                if (!window.decorView.isAttachedToWindow) break
                val p = progress.coerceIn(0f, 1f)
                val handedOff = compose.runOnUiThread { flight.handedOff || flight.wasProjected }
                projected = projected || handedOff
                if (!takeover) assertFalse("#926 a plain $destination close must not leave its window: frame=$frame p=$p", handedOff)
                if (p !in .04f..0.9f) continue
                val modal = original(window, output, "frame-$frame-popup")
                try {
                    val rootAt = IntArray(2); val modalAt = IntArray(2)
                    compose.runOnUiThread { activity.decorView.getLocationOnScreen(rootAt); window.decorView.getLocationOnScreen(modalAt) }
                    val counts = JSONArray()
                    var opaque = 0
                    regions.forEach { (name, bounds) ->
                        var count = 0
                        for (y in bounds.top.toInt() until bounds.bottom.toInt() step 2)
                            for (x in bounds.left.toInt() until bounds.right.toInt() step 2) {
                                val mx = x + rootAt[0] - modalAt[0]; val my = y + rootAt[1] - modalAt[1]
                                if (mx in 0 until modal.width && my in 0 until modal.height && Color.alpha(modal.getPixel(mx, my)) >= 254) count++
                            }
                        counts.put(JSONObject().put("region", name).put("opaquePopupPixels", count))
                        opaque += count
                    }
                    samples.put(JSONObject().put("frame", frame).put("phase", p).put("projected", handedOff).put("regions", counts)); save()
                    if (takeover && handedOff) assertTrue("#667 a handed-off popup draws nothing in its own window: frame=$frame $counts", opaque <= 4)
                    if (!takeover && opaque > 100) { overChrome++; lowest = minOf(lowest, p) }
                } finally { modal.recycle() }
            }
            report.put("overChromeFrames", overChrome).put("lowestOverChromePhase", lowest).put("projected", projected); save()
            if (takeover) assertTrue("The Settings takeover hands the popup to its departing home", projected)
            else assertTrue("#926 the popup draws over the bars into its tail: $report", overChrome >= 3 && lowest <= .2f)
            assertFalse("The overflow's original native window retires after return", window.decorView.isAttachedToWindow)
            if (!takeover) compose.onNodeWithTag("utility-pill").assertIsDisplayed()
            report.put("status", "passed"); save()
        } catch (failure: Throwable) {
            report.put("status", "failed").put("failure", failure.toString()); save(); throw failure
        } finally {
            baseline?.recycle(); compose.mainClock.autoAdvance = true; preferences.write(previous)
        }
    }


    /** One original Window copy after a native draw receipt, with an unrelated opaque frame token. No copy retry. */
    private fun original(window: Window, output: File, name: String): Bitmap = frames.capture(window, output, name)
}
