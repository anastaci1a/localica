package io.github.anastaci1a.localica

import android.view.View
import androidx.compose.foundation.background
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

class PopupHostNavigationTest {
    @get:Rule val compose = localicaComposeRule()
    private val markers = java.util.IdentityHashMap<View, FrameMarker>()
    private var frameViews: () -> List<View> = { emptyList() }
    private var frameState: () -> String = { "" }
    private var stage = 0

    @org.junit.After fun removeMarkers() = compose.runOnUiThread {
        markers.forEach { (view, marker) -> view.overlay.remove(marker) }
    }

    @Test fun settingsBackDuringRetractLeavesWithSubmenu() = navigate(settings = true, expanding = false)
    @Test fun settingsBackDuringExpandThenBackLeavesWithSubmenu() = navigate(settings = true, expanding = true)
    @Test fun subviewBackDuringRetractLeavesWithPage() = navigate(settings = false, expanding = false)
    @Test fun subviewBackDuringExpandThenBackLeavesWithPage() = navigate(settings = false, expanding = true)

    @Test fun libraryBackDuringRetractKeepsBothOpticalSourcesOutsideThePopup() = navigate(settings = false, expanding = false, library = true)
    @Test fun libraryBackDuringExpandKeepsBothOpticalSourcesOutsideThePopup() = navigate(settings = false, expanding = true, library = true)

    private fun navigate(settings: Boolean, expanding: Boolean, library: Boolean = false) {
        var page by mutableStateOf(true)
        var open by mutableStateOf(false)
        lateinit var anchor: GlassAnchor
        var popupView: View? = null
        var parentView: View? = null
        var parentProgress = 1f
        frameViews = { listOfNotNull(parentView, popupView) }
        frameState = { "parent=$parentProgress open=$open parentAttached=${parentView?.isAttachedToWindow} popupAttached=${popupView?.isAttachedToWindow}" }
        compose.setContent { LocalicaTheme {
            CompositionLocalProvider(LocalGlassMorphWorkObserver provides { phase, window, progress ->
                if (phase == "geometry" && progress != null && window ===
                    (parentView?.parent as? DialogWindowProvider)?.window) parentProgress = progress
            }) {
            Box(Modifier.fillMaxSize().background(Color.Blue))
            @Composable fun Body() {
                parentView = LocalView.current
                anchor = rememberGlassAnchor()
                Column(Modifier.fillMaxSize().background(Color.Blue).padding(24.dp)) {
                    SettingsLink("Popup home", anchor = anchor) { open = true }
                    if (open) CompositionLocalProvider(LocalGlassOrigin provides anchor, LocalMotionSpeed provides .5f) {
                        GlassDialog({ open = false }) {
                            popupView = LocalView.current
                            Box(Modifier.size(240.dp).background(Color.Red))
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
            }
        } }
        if (settings) {
            compose.onNodeWithText("Interface").performClick()
            compose.onNodeWithText("Glass").performClick()
        }
        compose.mainClock.autoAdvance = false
        compose.mainClock.advanceTimeBy(1600)
        compose.runOnIdle { open = true }
        compose.advanceMorphOpening(if (expanding) 720 else 3200, anchor) { popupView }
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
        val before = redFrame()
        assertTrue("Fixture presents popup foreground before navigation: $before", before.pixels > 100)
        back(parentView) { settings || !page }
        var transitionStarted = false
        for (frame in 0 until 20) {
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
        assertTrue("Navigation preserves the popup in the first outgoing frame", redFrame().pixels > 100)
        assertTrue("The same native popup keeps retracting", popupView?.isAttachedToWindow == true)
        compose.mainClock.advanceTimeBy(32)
        val during = redFrame()
        assertTrue("Outgoing popup fades continuously with its host: $before -> $during",
            during.peak in 1 until before.peak)
        compose.mainClock.advanceTimeBy(208)
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
        compose.runOnUiThread {
            views = frameViews().filter { it.isAttachedToWindow }
            views.forEachIndexed { index, view ->
                val marker = markers.getOrPut(view) { FrameMarker(4 + index * 16).also { view.overlay.add(it) } }
                marker.stage = requested
                marker.invalidateSelf()
                view.invalidate()
            }
        }
        var presented: android.graphics.Bitmap? = null
        // Window overlays prove the current frame of both native windows, even
        // when the modal's Compose drawing has transferred into its home layer.
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
            RedFrame(count, peak)
        } finally { bitmap.recycle() }
    }
}
