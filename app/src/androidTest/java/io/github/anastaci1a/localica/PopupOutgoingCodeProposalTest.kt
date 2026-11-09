package io.github.anastaci1a.localica

import android.view.View
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.test.*
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class PopupOutgoingCodeProposalTest {
    @get:Rule val compose = localicaComposeRule()
    @get:Rule val method = org.junit.rules.TestName()
    private val evidence = HomeNativeEvidence { method.methodName }
    private var nativeState: () -> org.json.JSONObject = { org.json.JSONObject() }
    private val workReceipts = org.json.JSONArray()
    private var evidencePhase = "setup"
    private val markers = java.util.IdentityHashMap<View, FrameMarker>()
    private var frameViews: () -> List<View> = { emptyList() }
    private var frameState: () -> String = { "" }
    private var stage = 0
    private enum class Mutation { None, Loss, FlatTint }
    private var mutation = Mutation.None
    private var removeCode by mutableStateOf(false)

    @org.junit.After fun removeMarkers() = compose.runOnUiThread {
        markers.forEach { (view, marker) -> view.overlay.remove(marker) }
    }

    @Test fun settingsBackDuringRetractLeavesWithSubmenu() = navigate(settings = true, expanding = false)
    @Test fun settingsBackDuringExpandThenBackLeavesWithSubmenu() = navigate(settings = true, expanding = true)
    @Test fun subviewBackDuringRetractLeavesWithPage() = navigate(settings = false, expanding = false)
    @Test fun subviewBackDuringExpandThenBackLeavesWithPage() = navigate(settings = false, expanding = true)

    @Test fun libraryBackDuringRetractKeepsBothOpticalSourcesOutsideThePopup() = navigate(settings = false, expanding = false, library = true)
    @Test fun libraryBackDuringExpandKeepsBothOpticalSourcesOutsideThePopup() = navigate(settings = false, expanding = true, library = true)

    @Test fun playlistEditorCancellationLeavesPopupBackUsable() = navigate(settings = false, expanding = true, playlistEditor = true)

    @Test fun foregroundLossMutantMustFailContinuity() {
        mutation = Mutation.Loss
        navigate(settings = false, expanding = true, library = true)
    }
    @Test fun flatTintMutantMustFailIdentity() {
        mutation = Mutation.FlatTint
        navigate(settings = false, expanding = true, library = true)
    }

    private fun navigate(settings: Boolean, expanding: Boolean, library: Boolean = false, playlistEditor: Boolean = false) {
        var naming by mutableStateOf(playlistEditor)
        var page by mutableStateOf(true)
        var open by mutableStateOf(false)
        lateinit var anchor: GlassAnchor
        var popupView: View? = null
        var parentView: View? = null
        var parentProgress = 1f
        var parentHome: GlassPopupHome? = null
        var dialogs: GlassDialogs? = null
        fun anchorState(value: GlassAnchor) = org.json.JSONObject()
            .put("identity", System.identityHashCode(value)).put("hidden", value.hidden)
            .put("modal", value.modalPresent).put("inModal", value.shadowInModal)
            .put("restored", value.restoredDrawn).put("bounds", value.bounds.toString())
            .put("presentedBounds", value.presentedBounds.toString())
            .put("expansion", value.contact.expansion.value)
        fun homeState(value: GlassPopupHome?) = value?.let { home -> org.json.JSONObject()
            .put("identity", System.identityHashCode(home)).put("departing", home.departing)
            .put("mounted", home.mounted).put("handedOff", home.handedOff)
            .put("origin", home.origin.toString()).put("currentOrigin", home.currentOrigin.toString())
            .put("backdropRecorded", home.backdrop?.recorded).put("fallback", home.backdrop?.fallback != null)
            .put("flights", org.json.JSONArray(home.flights.map { flight -> org.json.JSONObject()
                .put("identity", System.identityHashCode(flight)).put("revision", flight.revision)
                .put("prepared", flight.prepared).put("handedOff", flight.handedOff)
                .put("wasProjected", flight.wasProjected).put("layerPresent", flight.layer != null)
                .put("layerReleased", flight.layer?.isReleased).put("origin", flight.origin.toString())
                .put("homeOrigin", flight.homeOrigin.toString()) })) }
        nativeState = { androidx.compose.runtime.snapshots.Snapshot.withoutReadObservation {
            org.json.JSONObject().put("clock", compose.mainClock.currentTime).put("phase", evidencePhase)
                .put("page", page).put("open", open).put("parentProgress", parentProgress)
                .put("parentHome", homeState(parentHome))
                .put("entries", org.json.JSONArray(dialogs?.entries?.map { entry -> org.json.JSONObject()
                    .put("mounted", entry.mounted).put("anchor", anchorState(entry.anchor))
                    .put("home", homeState(entry.popupHome)) }.orEmpty()))
        } }
        frameViews = { listOfNotNull(parentView, popupView) }
        frameState = { "parent=$parentProgress open=$open parentAttached=${parentView?.isAttachedToWindow} popupAttached=${popupView?.isAttachedToWindow}" }
        compose.setContent { LocalicaTheme {
            dialogs = LocalGlassDialogs.current
            CompositionLocalProvider(LocalGlassMorphWorkObserver provides { phase, window, progress ->
                if (phase == "geometry" && progress != null && window ===
                    (parentView?.parent as? DialogWindowProvider)?.window) parentProgress = progress
                if (workReceipts.length() < 4096) workReceipts.put(org.json.JSONObject()
                    .put("ns", android.os.SystemClock.elapsedRealtimeNanos()).put("phase", phase)
                    .put("window", window?.let { System.identityHashCode(it) }).put("progress", progress))
            }) {
            Box(Modifier.fillMaxSize().background(Color.Blue))
            @Composable fun Body() {
                parentView = LocalView.current
                parentHome = LocalGlassPopupHome.current
                anchor = rememberGlassAnchor()
                Column(Modifier.fillMaxSize().background(Color.Blue).padding(24.dp)) {
                    if (library) {
                        val source = LocalFrostedBackdrop.current
                        SideEffect { anchor.source = source }
                        Box(Modifier.glassAnchor(anchor).clickable { open = true }) {
                            androidx.compose.material3.Text("Popup home")
                        }
                    } else SettingsLink("Popup home", anchor = anchor) { open = true }
                    if (open) CompositionLocalProvider(LocalGlassOrigin provides anchor, LocalMotionSpeed provides .5f) {
                        GlassDialog({ open = false }) {
                            popupView = LocalView.current
                            Canvas(Modifier.size(240.dp)) {
                                if (!removeCode) {
                                    repeat(4) { band -> drawRect(if (band % 2 == 0) Color.Red else Color.Green,
                                        Offset(size.width * band / 4f, 0f), Size(size.width / 4f, size.height)) }
                                } else if (mutation == Mutation.FlatTint) drawRect(Color.Red)
                            }
                        }
                    }
                }
            }
            if (library) {
                val source = rememberFrostedBackdrop(Unit)
                androidx.activity.compose.BackHandler(page) { page = false }
                CompositionLocalProvider(LocalFrostedBackdrop provides source, LocalModalBackdrop provides source) {
                    // A real Library source encloses its screen home. Sampling it
                    // after projection would recursively sample the modal itself.
                    Box(Modifier.fillMaxSize().recordFrostedBackdrop(source)) {
                        GlassPopupNavigation(page) { shown ->
                            if (shown) Body()
                            else Box(Modifier.fillMaxSize().background(Color.Blue).testTag("popup-home-destination"))
                        }
                    }
                }
            } else if (settings) FolderSettings(LibraryUiState(), { page = false }, {}, {}, interfaceContent = { Body() })
            else if (page) GlassDialog({ page = false }, GlassPlacement.Page) { Body() }
            if (naming) AddToPlaylistDialog(emptyList(), io.github.anastaci1a.localica.playlist.PlaylistState(loading = false), null, { naming = false }, { naming = false })
            }
        } }
        if (playlistEditor) {
            val input = compose.onNodeWithContentDescription("New playlist name input")
            input.performClick().performTextInput("Draft")
            val inputView = (input.fetchSemanticsNode().root as androidx.compose.ui.platform.ViewRootForTest).view
            val visibleIme = InstrumentationRegistry.getArguments().getString("popupHostVisibleIme") == "true"
            compose.waitUntil(10_000) { compose.runOnUiThread {
                inputView.hasWindowFocus() && inputView.context.getSystemService(android.view.inputmethod.InputMethodManager::class.java).isAcceptingText &&
                    (!visibleIme || androidx.core.view.ViewCompat.getRootWindowInsets(inputView)?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) == true)
            } }
            compose.onNodeWithText("Cancel").performClick()
            compose.waitForIdle()
        }
        if (settings) {
            compose.onNodeWithText("Interface").performClick()
            compose.onNodeWithText("Glass").performClick()
        }
        compose.mainClock.autoAdvance = false
        compose.mainClock.advanceTimeBy(1600)
        compose.runOnIdle { open = true }
        if (expanding) {
            // Interrupt the actual #608 flight; the legacy 720 ms spring budget
            // can run the new expansion to rest before Back is delivered.
            compose.waitUntil(5000) {
                compose.mainClock.advanceTimeByFrame()
                compose.waitForIdle()
                anchor.contact.expansion.value >= .7f
            }
        } else compose.advanceMorphOpening(3200, anchor) { popupView }
        if (expanding) assertTrue("Back interrupts an unfinished expansion", anchor.contact.expansion.value < .99f)
        fun back(view: View?, condition: () -> Boolean) {
            compose.waitUntil(5000) { view?.hasWindowFocus() == true }
            UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).pressKeyCode(android.view.KeyEvent.KEYCODE_BACK)
            compose.waitUntil(5000, condition)
        }
        // Back during expansion must reverse the same window, then the next Back
        // navigates while it is still returning. Also cover an ordinary dismissal.
        if (expanding) back(popupView) { !open }
        else compose.runOnIdle { open = false }
        compose.mainClock.advanceTimeBy(32)
        assertTrue("Return is in flight before navigation", anchor.modalPresent && anchor.contact.expansion.value > 0f)
        evidencePhase = "before-navigation"
        val before = redFrame()
        assertTrue("Fixture presents popup foreground before navigation: $before", before.pixels > 100)
        if (mutation != Mutation.None) {
            compose.runOnIdle { removeCode = true }
            compose.waitForIdle()
        }
        back(parentView) { settings || !page }
        var transitionStarted = false
        for (frame in 0 until 20) {
            evidencePhase = "host-admission-$frame"
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            assertTrue("Popup remains continuous while the host admits navigation", redFrame().pixels > 100)
            transitionStarted = if (settings) compose.onAllNodesWithText("Interface").fetchSemanticsNodes().isNotEmpty()
                else if (library) compose.onAllNodesWithTag("popup-home-destination").fetchSemanticsNodes().isNotEmpty()
                else parentProgress < .995f
            if (transitionStarted) break
        }
        assertTrue("The host must begin its outgoing transition", transitionStarted)
        compose.mainClock.advanceTimeBy(16)
        evidencePhase = "first-outgoing"
        assertTrue("Navigation preserves the popup in the first outgoing frame", redFrame().pixels > 100)
        assertTrue("The same native popup keeps retracting", popupView?.isAttachedToWindow == true)
        compose.mainClock.advanceTimeBy(32)
        evidencePhase = "during-outgoing"
        val during = redFrame()
        assertTrue("Outgoing popup fades continuously with its host: $before -> $during",
            during.peak in 1 until before.peak)
        compose.mainClock.advanceTimeBy(208)
        evidencePhase = "after-outgoing"
        val after = redFrame()
        assertEquals("Popup cannot outlive its outgoing host layer over the destination", 0, after.pixels)
        compose.mainClock.advanceTimeBy(1600)
    }

    private data class RedFrame(val pixels: Int, val peak: Int)

    private class FrameMarker(val x: Int) : android.graphics.drawable.Drawable() {
        var stage = 0
        private val paint = android.graphics.Paint()
        init { setBounds(x, 100, x + 8, 108) }
        override fun draw(canvas: android.graphics.Canvas) {
            paint.color = android.graphics.Color.rgb(stage * 8, 255, 128)
            canvas.drawRect(bounds, paint)
        }
        override fun setAlpha(alpha: Int) { }
        override fun setColorFilter(filter: android.graphics.ColorFilter?) { }
        @Suppress("DEPRECATION") override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
    }

    private fun redFrame(): RedFrame {
        compose.waitForIdle()
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        stage = stage % 24 + 1
        val requested = stage
        lateinit var views: List<View>
        lateinit var requestState: org.json.JSONObject
        lateinit var matchedState: org.json.JSONObject
        compose.runOnUiThread {
            requestState = nativeState()
            views = frameViews().filter { it.isAttachedToWindow }
            views.forEachIndexed { index, view ->
                val marker = markers.getOrPut(view) { FrameMarker(4 + index * 16).also { view.overlay.add(it) } }
                marker.stage = requested
                marker.invalidateSelf()
                view.invalidate()
            }
        }
        var presented: android.graphics.Bitmap? = null
        // Window overlay freshness does not certify a retained child recording.
        compose.waitUntil(5000) {
            val next = requireNotNull(automation.takeScreenshot())
            var matches = true
            compose.runOnUiThread {
                views.filter { it.isAttachedToWindow }.forEach { view ->
                    val origin = IntArray(2)
                    view.getLocationOnScreen(origin)
                    val c = next.getPixel(origin[0] + requireNotNull(markers[view]).x + 4, origin[1] + 104)
                    val green = android.graphics.Color.green(c)
                    // A covering native scrim scales all channels equally.
                    matches = matches && green > 80 && kotlin.math.abs(android.graphics.Color.red(c).toFloat() / green - requested * 8 / 255f) < .012f
                }
                if (matches) matchedState = nativeState().put("workReceipts", org.json.JSONArray(workReceipts.toString()))
            }
            if (matches) presented = next else next.recycle()
            matches
        }
        android.util.Log.i("PopupHostNavigation", "stage=$requested clock=${compose.mainClock.currentTime} ${frameState()}")
        val bitmap = requireNotNull(presented)
        return try {
            var count = 0
            var peak = 0
            for (y in 0 until bitmap.height step 2) for (x in 0 until bitmap.width step 2) {
                val c = bitmap.getPixel(x, y)
                val excess = android.graphics.Color.red(c) - maxOf(android.graphics.Color.green(c), android.graphics.Color.blue(c))
                peak = maxOf(peak, excess)
                if (android.graphics.Color.red(c) > 80 && excess > 32) count++
            }
            val code = OutgoingFixtureCode.measure(bitmap, if (evidencePhase == "after-outgoing") 4 else 32)
            evidence.save(bitmap, org.json.JSONObject().put("stage", requested).put("pixels", code.pixels).put("peak", code.peak)
                .put("oldRedPixels", count).put("oldRedPeak", peak).put("mutation", mutation.name)
                .put("codeRows", code.rows).put("codeBounds", code.bounds)
                .put("requestedState", requestState).put("matchedState", matchedState))
            RedFrame(code.pixels, code.peak)
        } finally { bitmap.recycle() }
    }
}
