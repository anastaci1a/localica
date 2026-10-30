package io.github.anastaci1a.localica

import android.graphics.Bitmap
import android.graphics.Color as PixelColor
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewTreeObserver
import android.view.Window
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import io.github.anastaci1a.localica.navigation.GlassAppearance
import io.github.anastaci1a.localica.ui.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToInt

/** Real shared GlassMenu/ActionMenuItem input and viewport test, not a real Library route.
 * A narrow red rail is test-only content: it witnesses the actual scroll mask without
 * replacing the glass renderer or deriving an oracle from artwork. Full original native
 * window copies are saved BEFORE inspecting the start/middle/actual-end pixels. */
class GlassMenuViewportTouchTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val activated = mutableListOf<Int>()
    private lateinit var anchor: GlassAnchor
    private lateinit var popupView: View
    private lateinit var output: File
    private var open by mutableStateOf(false)
    private var nativeDismissRequests = 0
    private var mounted by mutableStateOf(true)
    private var stage by mutableIntStateOf(0)
    private val records = JSONArray()

    @Test fun longMenuTouchScrollsToActualBoundaryThenTapsDismissesAndReopens() {
        val base = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
            ?.takeIf(String::isNotBlank)?.let(::File)
            ?: requireNotNull(instrumentation.targetContext.getExternalFilesDir(null))
        output = File(base, "menu-viewport-touch/api${Build.VERSION.SDK_INT}-${SystemClock.uptimeMillis()}")
        assertTrue(output.mkdirs())
        writeManifest("running")
        var primary: Throwable? = null
        var contentStarted = false
        try {
            contentStarted = true
            compose.setContent { if (mounted) LocalicaTheme {
                CompositionLocalProvider(LocalReducedMotion provides true,
                    LocalGlassAppearance provides GlassAppearance()) {
                    anchor = rememberGlassAnchor()
                    val source = rememberFrostedBackdrop(Unit, dark = false)
                    Box(Modifier.fillMaxSize()) {
                        Canvas(Modifier.fillMaxSize().recordFrostedBackdrop(source)) {
                            drawRect(Color(0xFFEAEAEA))
                        }
                        CompositionLocalProvider(LocalModalBackdrop provides source, LocalFrostedBackdrop provides source) {
                            GlassActionButton(R.drawable.ic_more, "Open long menu",
                                Modifier.padding(32.dp).glassAnchor(anchor).testTag("long-menu-trigger")) { open = true }
                            GlassMenu(open, anchor, { nativeDismissRequests++; open = false }) {
                                val view = LocalView.current
                                SideEffect { popupView = view }
                                Box(Modifier.width(240.dp)) {
                                    Column {
                                        repeat(24) { index -> ActionMenuItem("Action ${index + 1}") {
                                            activated += index; open = false
                                        } }
                                    }
                                    // No input node: all injected contacts still reach the real buttons.
                                    // Stage is encoded along the rail so its unfaded midpoint detects a
                                    // stale copy without optical polling or screenshot replacements.
                                    Canvas(Modifier.matchParentSize()) {
                                        drawRect(Color(1f, 0f, stage * 8f / 255f),
                                            Offset(size.width / 2f - 2.dp.toPx(), 0f),
                                            Size(4.dp.toPx(), size.height))
                                    }
                                }
                            }
                        }
                    }
                }
            } }
            openByNativeTap()
            val initial = surface().fetchSemanticsNode().boundsInRoot
            val start = scrollPosition()
            assertEquals(0f, start.first, 0f)
            assertTrue("Fixture must require more than one screen of scrolling", start.second > initial.height * .6f)
            capture("start", topFaded = false, bottomFaded = true)

            compose.onNodeWithText("Action 1").performTouchInput {
                down(center)
                advanceEventTime(200)
                moveBy(Offset(0f, -80f * popupView.resources.displayMetrics.density), delayMillis = 100)
                up()
            }
            assertEquals("Held action drag retains its owner instead of scrolling", 0f, scrollPosition().first, 0f)
            compose.runOnIdle { assertTrue("A held drag cannot activate an action", activated.isEmpty()) }
            touchScrollUp()
            val middle = scrollPosition()
            assertTrue("A fast swipe beginning on a real action must scroll", middle.first > 0f)
            assertTrue("Middle capture must precede the actual end", middle.first < middle.second)
            compose.runOnIdle { assertTrue("Swiping never activates an action", activated.isEmpty()) }
            assertEquals(initial, surface().fetchSemanticsNode().boundsInRoot)
            capture("middle", topFaded = true, bottomFaded = true)

            // Bound work by this finite 24-row fixture; never fake the endpoint with a
            // semantic click, scrollTo, dispatchRawDelta or visibility of the last label.
            var swipes = 1
            while (scrollPosition().let { it.first < it.second }) {
                assertTrue("Physical scrolling did not reach the finite menu end", swipes++ < 24)
                val before = scrollPosition().first
                touchScrollUp()
                assertTrue("Each swipe must advance until the endpoint", scrollPosition().first > before)
                compose.runOnIdle { assertTrue(activated.isEmpty()) }
            }
            val end = scrollPosition()
            assertEquals("Actual scroll maximum, not merely a visible final label", end.second, end.first, 0f)
            assertEquals(initial, surface().fetchSemanticsNode().boundsInRoot)
            capture("end", topFaded = true, bottomFaded = false)

            nativeTap(compose.onNodeWithText("Action 24").assertIsDisplayed())
            awaitClosed()
            compose.runOnIdle { assertEquals(listOf(23), activated) }
            openByNativeTap()
            assertEquals("Fresh opening starts at the first row", 0f, scrollPosition().first, 0f)
            pressSystemBackAndAwaitDismissRequest()
            awaitClosed()
            openByNativeTap()
            // The shell is inset from the left window edge. Tap the real native scrim,
            // not onDismiss or a semantics callback; coordinate is checked first.
            val bounds = surface().fetchSemanticsNode().boundsInRoot
            val density = popupView.resources.displayMetrics.density
            assertTrue("Known outside point must be left of the popup", bounds.left > 4f * density)
            val origin = IntArray(2)
            compose.runOnUiThread { popupView.getLocationOnScreen(origin) }
            assertTrue(UiDevice.getInstance(instrumentation).click(origin[0] + (4f * density).roundToInt(),
                origin[1] + bounds.center.y.roundToInt()))
            awaitClosed()
            openByNativeTap()
            nativeTap(compose.onNodeWithText("Action 1").assertIsDisplayed())
            awaitClosed()
            compose.runOnIdle { assertEquals("One activation per native tap, none from drags/dismissals", listOf(23, 0), activated) }
        } catch (failure: Throwable) {
            primary = failure
        } finally {
            if (contentStarted) try {
                compose.runOnIdle { mounted = false }
                compose.waitForIdle()
            } catch (cleanup: Throwable) {
                val original = primary
                if (original == null) primary = cleanup
                else if (cleanup !== original) original.addSuppressed(cleanup)
            }
        }
        // Terminal status includes unmount/disposal. Never let teardown or failure
        // reporting replace the first test failure; retained originals stay untouched.
        val failure = primary
        try {
            writeManifest(if (failure == null) "passed" else "failed", failure?.stackTraceToString())
        } catch (reporting: Throwable) {
            if (failure == null) throw reporting
            else if (reporting !== failure) failure.addSuppressed(reporting)
        }
        if (failure != null) throw failure
    }

    private fun surface() = compose.onNodeWithTag("glass-morph-surface", useUnmergedTree = true)
    private fun scrollNode() = compose.onNode(hasScrollAction() and
        hasAnyAncestor(hasTestTag("glass-morph-surface")), useUnmergedTree = true)
    private fun scrollPosition(): Pair<Float, Float> {
        val axis = scrollNode().fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
        return compose.runOnIdle { axis.value() to axis.maxValue() }
    }

    private fun touchScrollUp() {
        compose.awaitExpectedWindowFocus(surface())
        val scroll = scrollNode()
        val scrollSemantic = scroll.fetchSemanticsNode()
        val viewport = scrollSemantic.boundsInRoot
        // Starting at an arbitrary viewport coordinate could hit an inter-row gap.
        // Select geometry only: DOWN must land on a fully visible real button.
        val action = compose.onAllNodes(hasClickAction() and hasText("Action ", substring = true))
            .fetchSemanticsNodes().filter { node ->
                val b = node.boundsInRoot
                node.root === scrollSemantic.root && b.height >= 48f * popupView.resources.displayMetrics.density &&
                    b.top >= viewport.top && b.bottom <= viewport.bottom &&
                    b.center.y > viewport.top + viewport.height * .55f && b.center.y < viewport.top + viewport.height * .9f
            }.minByOrNull { kotlin.math.abs(it.boundsInRoot.center.y - (viewport.top + viewport.height * .8f)) }
            ?: throw AssertionError("No fully visible action in the lower scroll viewport")
        val start = action.boundsInRoot.center - viewport.topLeft
        val end = Offset(start.x, viewport.height * .25f)
        assertTrue("Swipe DOWN is inside an actual action", action.boundsInRoot.contains(start + viewport.topLeft))
        scroll.performTouchInput {
            down(start)
            // Fast initial intent yields the button to its real parent scroll owner.
            moveTo(start + (end - start) * .2f, delayMillis = 16)
            moveTo(end, delayMillis = 104)
            // Actual stationary MOVE samples retire the old motion from velocity
            // tracking; advancing the event clock alone does not send such samples.
            repeat(4) { moveTo(end, delayMillis = 50) }
            up()
        }
        compose.waitForIdle()
    }

    private fun nativeTap(node: SemanticsNodeInteraction) {
        compose.awaitExpectedWindowFocus(node)
        val semantic = node.fetchSemanticsNode()
        val view = (semantic.root as ViewRootForTest).view
        val origin = IntArray(2)
        compose.runOnUiThread { view.getLocationOnScreen(origin) }
        val center = semantic.boundsInRoot.center
        assertTrue(UiDevice.getInstance(instrumentation).click(origin[0] + center.x.roundToInt(), origin[1] + center.y.roundToInt()))
        compose.waitForIdle()
    }

    private fun openByNativeTap() {
        nativeTap(compose.onNodeWithTag("long-menu-trigger").assertIsDisplayed())
        surface().assertIsDisplayed()
        compose.awaitExpectedWindowFocus(surface())
    }

    private fun awaitClosed() {
        awaitGlassReturn(compose, anchor) { "open=$open activated=$activated" }
        compose.waitUntil(5000) { compose.onAllNodesWithTag("glass-morph-surface", useUnmergedTree = true).fetchSemanticsNodes().isEmpty() }
        compose.awaitExpectedWindowFocus(compose.onNodeWithTag("long-menu-trigger"))
    }

    private fun pressSystemBackAndAwaitDismissRequest() {
        val before = compose.runOnUiThread { nativeDismissRequests }
        val virtualStart = compose.mainClock.currentTime
        val wallStart = SystemClock.uptimeMillis()
        val autoAdvance = compose.mainClock.autoAdvance
        // Acceptance by Android is not delivery to this dialog. Keep the animation
        // allowance untouched while the real native Back reaches onDismissRequest.
        compose.mainClock.autoAdvance = false
        fun state() = compose.runOnUiThread {
            "before=$before requests=$nativeDismissRequests open=$open " +
                "wallElapsed=${SystemClock.uptimeMillis() - wallStart} " +
                "virtualElapsed=${compose.mainClock.currentTime - virtualStart}"
        }
        try {
            Log.i("NativeBackTest", "requesting menu Back: ${state()}")
            pressSystemBack()
            compose.waitUntil(5000) { compose.runOnUiThread { nativeDismissRequests > before } }
            compose.runOnUiThread {
                assertEquals("One native dismissal request per Back", before + 1, nativeDismissRequests)
                assertFalse("The native dismissal callback must close the menu", open)
            }
            Log.i("NativeBackTest", "menu Back acknowledged: ${state()}")
        } catch (failure: Throwable) {
            val detail = "menu Back acknowledgement failed: " +
                runCatching { state() }.getOrElse { "diagnostic-error=$it" }
            Log.e("NativeBackTest", detail, failure)
            failure.addSuppressed(IllegalStateException(detail))
            throw failure
        } finally {
            compose.mainClock.autoAdvance = autoAdvance
        }
    }

    private fun capture(label: String, topFaded: Boolean, bottomFaded: Boolean) {
        val view = popupView
        val window = compose.runOnUiThread { (view.parent as DialogWindowProvider).window }
        val draws = AtomicInteger(0)
        lateinit var tree: ViewTreeObserver
        val listener = ViewTreeObserver.OnDrawListener { draws.incrementAndGet() }
        compose.runOnUiThread { tree = view.viewTreeObserver; tree.addOnDrawListener(listener) }
        try {
            compose.runOnIdle { stage++ }
            compose.waitUntil(5000) { draws.get() > 0 && compose.runOnUiThread { view.hasWindowFocus() && !view.isLayoutRequested } }
            val bounds = surface().fetchSemanticsNode().boundsInRoot
            val viewport = scrollNode().fetchSemanticsNode().boundsInRoot
            val inWindow = IntArray(2)
            compose.runOnUiThread { view.getLocationInWindow(inWindow) }
            val bitmap = copyOnce(window)
            try {
                val file = File(output, "$label-original-window.png")
                file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                val position = scrollPosition()
                records.put(JSONObject().put("phase", label).put("image", file.name)
                    .put("sha256", MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it.toInt() and 255) })
                    .put("scrollValue", position.first).put("scrollMax", position.second)
                    .put("boundsInRoot", JSONArray(listOf(bounds.left, bounds.top, bounds.right, bounds.bottom)))
                    .put("viewportBoundsInRoot", JSONArray(listOf(viewport.left, viewport.top, viewport.right, viewport.bottom)))
                    .put("stage", stage).put("topFaded", topFaded).put("bottomFaded", bottomFaded))
                writeManifest("running")
                assertEquals("Shared menu viewport reaches the shell instead of an inset clip", bounds, viewport)
                val x = inWindow[0] + viewport.center.x.roundToInt()
                val middleY = inWindow[1] + viewport.center.y.roundToInt()
                assertTrue("Unfaded rail identifies this capture stage",
                    kotlin.math.abs(stage * 8 - PixelColor.blue(bitmap.getPixel(x, middleY))) <= 2)
                val sample = 10f * view.resources.displayMetrics.density
                fun checkFade(y: Float, faded: Boolean) {
                    val color = bitmap.getPixel(x, inWindow[1] + y.roundToInt())
                    val redDominance = (PixelColor.red(color) - PixelColor.green(color)) / 255f
                    if (faded) assertTrue("$label expected partial edge fade at 10dp, got $redDominance", redDominance in .15f.. .65f)
                    else assertTrue("$label reached edge must reveal the rail, got $redDominance", redDominance > .9f)
                }
                checkFade(viewport.top + sample, topFaded)
                checkFade(viewport.bottom - sample - 1f, bottomFaded)
            } finally { bitmap.recycle() }
        } finally { compose.runOnUiThread { if (tree.isAlive) tree.removeOnDrawListener(listener) } }
    }

    private fun copyOnce(window: Window): Bitmap {
        val bitmap = compose.runOnUiThread { Bitmap.createBitmap(window.decorView.width, window.decorView.height, Bitmap.Config.ARGB_8888) }
        return copyNativeWindowOnce(window, bitmap,
            "Single native copy timed out; no retry") { result -> "Single native copy failed: $result" }
    }

    private fun writeManifest(status: String, failure: String? = null) {
        File(output, "manifest.json").writeText(JSONObject().put("status", status)
            .put("failure", failure ?: JSONObject.NULL).put("api", Build.VERSION.SDK_INT).put("records", records)
            .put("scope", "Shared long GlassMenu/ActionMenuItem fixture; touch-injected swipes, native coordinate taps, system Back; reduced motion")
            .put("limits", "Test-only 4dp red fade/stage rail; not an unmodified Library route or physical-finger/device acceptance. Single native copy per phase; cached backing shader execution is not established.")
            .toString(2))
    }
}
