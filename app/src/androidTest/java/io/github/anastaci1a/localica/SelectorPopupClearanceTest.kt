package io.github.anastaci1a.localica

import android.os.Build
import android.view.View
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/** Presented contour acceptance at the actual dialog's scrolling/clipping boundaries. */
class SelectorPopupClearanceTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    @Before fun configureWindowLikeTheApp() {
        compose.activityRule.scenario.onActivity {
            androidx.core.view.WindowCompat.setDecorFitsSystemWindows(it.window, false)
            it.window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
    }

    @Test fun choiceDialogKeepsBothEndpointContoursUnclipped() =
        checkEndpoints(count = 3)

    @Test fun scrollingChoiceDialogKeepsItsFirstAndLastContoursUnclipped() =
        checkEndpoints(count = 24)

    @Test fun largeFontScrollingChoiceDialogKeepsItsFirstAndLastContoursUnclipped() =
        checkEndpoints(count = 24, fontScale = 2f)

    @Test fun horizontalDialogChoicesKeepBothEndpointContoursAtLargeFont() =
        checkEndpoints(count = 2, fontScale = 2f, horizontal = true)

    private var stage by mutableIntStateOf(0)
    private lateinit var popupView: View
    private var source: FrostedBackdrop? = null
    private var corner = 0f
    private var popupFontScale = 0f

    private fun showDialog(count: Int, fontScale: Float, horizontal: Boolean) {
        val labels = List(count) { "Row ${it + 1}" }
        compose.setContent { LocalicaTheme {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                // Keep a real modal window, production panel clip and constraints.
                // Inline only the inner GlassDialog call: ChoiceDialog itself,
                // its DialogViewport, scroll/fade layers and all padding are real.
                // This existing inline path lets the fixture supply a diagnostic
                // source *inside* GlassMorphPopup's own backdrop provider, without
                // adding hooks or reproducing ChoiceDialog's content in the test.
                GlassMorphPopup(true, rememberGlassAnchor(), {}, GlassPlacement.Center,
                    darkOverride = false, fitContent = false) {
                    popupView = LocalView.current
                    val popupDensity = LocalDensity.current
                    corner = with(popupDensity) { 24.dp.toPx() - ChoiceInnerInset.roundToPx() }
                    val backing = rememberFrostedBackdrop(Unit, glassBlur = 0f, whiteTint = 0f)
                    SideEffect { source = backing; popupFontScale = popupDensity.fontScale }
                    Box(Modifier.fillMaxWidth().testTag("popup-clearance-scene")) {
                        // Only the real lens can display red. The recorded red
                        // source is covered directly by blue, as in GlassPressureTest.
                        Canvas(Modifier.matchParentSize().recordFrostedBackdrop(backing)) { drawRect(Color.Red) }
                        Canvas(Modifier.matchParentSize()) { drawRect(Color.Blue) }
                        CompositionLocalProvider(LocalFrostedBackdrop provides backing,
                            LocalInlineGlassDialog provides true,
                            LocalGlassAppearance provides LocalGlassAppearance.current.copy(
                                blur = 0f, whiteTint = 0f, contrast = 0f)) {
                            if (!horizontal) {
                                ChoiceDialog("Choose an option", labels, labels.first(), { it }, {}, {})
                            } else {
                                // ChoiceDialog is intentionally vertical. Its horizontal
                                // counterpart uses the same production alert viewport and
                                // shared GlassChoices, with the same body clearances.
                                var selected by remember { mutableIntStateOf(0) }
                                GlassAlertDialog({}, confirmButton = { Text("Select") },
                                    title = { Text("Choose an option") }, text = {
                                        Box(Modifier.padding(horizontal = 8.dp, vertical = 12.dp)) {
                                            GlassChoices(labels, selected, horizontal = true) { selected = it }
                                        }
                                    })
                            }
                        }
                        // Top-center stays inside the real rounded popup clip.
                        PresentationMarker(Modifier.align(Alignment.TopCenter)) { stage }
                    }
                }
            }
        } }
        if (Build.VERSION.SDK_INT < 33) compose.waitUntil(10000) { source?.lensSnapshot != null }
        compose.waitForIdle()
        assertEquals("The real popup must inherit the requested font scale", fontScale, popupFontScale, .001f)
    }

    private fun checkEndpoints(count: Int, fontScale: Float = 1f, horizontal: Boolean = false) {
        showDialog(count, fontScale, horizontal)
        val body = if (horizontal) null else choiceScrollViewport()
        if (body != null) {
            val maximum = body.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].maxValue()
            assertEquals("The long-list case must actually overflow the real ChoiceDialog body",
                count > 3, maximum > 0f)
        }
        for (index in listOf(0, count - 1)) {
            // Scroll the production viewport to its real endpoint, including its
            // trailing padding. performScrollTo alone can stop at the row edge.
            body?.performSemanticsAction(SemanticsActions.ScrollBy) { scroll ->
                scroll(0f, if (index == 0) -100000f else 100000f)
            }
            compose.waitForIdle()
            val scrollBefore = body?.fetchSemanticsNode()?.config?.get(SemanticsProperties.VerticalScrollAxisRange)?.value()
            if (body != null) {
                val range = body.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
                assertEquals("The endpoint's fade must be inactive", if (index == 0) 0f else range.maxValue(), range.value(), 1f)
            }
            val option = compose.onNodeWithTag("choice-option-$index")
            option.assertIsDisplayed().performClick()
            compose.waitForIdle()
            option.assertIsSelected()
            compose.mainClock.autoAdvance = false
            var held = false
            var failure: Throwable? = null
            try {
                val rest = frame("rest-$index")
                val host = transformedBounds("choice-track-host")
                option.performTouchInput { down(center); held = true }
                // A stationary hold acquires the selector before moving in the
                // same axis as the genuine scrolling parent.
                compose.mainClock.advanceTimeBy(650)
                val sign = if (index == 0) -1f else 1f
                option.performTouchInput {
                    moveBy(if (horizontal) Offset(sign * width * 2f, 0f)
                        else Offset(0f, sign * height * 2f), delayMillis = 100)
                }
                compose.mainClock.advanceTimeBy(400)
                val pulled = frame("held-$index")
                val edge = if (horizontal) {
                    if (index == 0) host.left - pulled.left else pulled.right - host.right
                } else {
                    if (index == 0) host.top - pulled.top else pulled.bottom - host.bottom
                }
                assertTrue("Presented lens must reach outside its resting host, not be clipped to it: $edge px", edge > 1f)
                if (body != null) assertEquals("An owned pull must not scroll away from the tested boundary",
                    scrollBefore!!, body.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value(), 1f)
                assertTrue("The actual held contour compresses on travel",
                    if (horizontal) pulled.width < rest.width - 1f else pulled.height < rest.height - 1f)
                option.performTouchInput { up(); held = false }
                compose.mainClock.advanceTimeBy(64)
                frame("release-64-$index")
                compose.mainClock.advanceTimeBy(160)
                frame("release-224-$index")
                compose.mainClock.advanceTimeBy(1600)
                val settled = frame("settled-$index")
                assertEquals(rest.left, settled.left, 2f)
                assertEquals(rest.top, settled.top, 2f)
                assertEquals(rest.right, settled.right, 2f)
                assertEquals(rest.bottom, settled.bottom, 2f)
            } catch (error: Throwable) {
                failure = error
                throw error
            } finally {
                try {
                    if (held) option.performTouchInput { cancel() }
                } catch (cleanup: Throwable) {
                    if (failure != null) failure.addSuppressed(cleanup) else throw cleanup
                } finally { compose.mainClock.autoAdvance = true }
            }
        }
    }

    /** Nearest real scroll owner, rather than DialogViewport's outer overflow scroll. */
    private fun choiceScrollViewport(): SemanticsNodeInteraction {
        val host = compose.onNodeWithTag("choice-track-host", useUnmergedTree = true).fetchSemanticsNode()
        val owner = generateSequence(host.parent) { it.parent }
            .first { it.config.contains(SemanticsProperties.VerticalScrollAxisRange) }
        return compose.onNode(SemanticsMatcher("ChoiceDialog body scroll owner") { it.id == owner.id }, useUnmergedTree = true)
    }

    /** Map un-clipped layout corners through every ancestor, including scroll translation. */
    private fun transformedBounds(tag: String): Rect {
        val coordinates = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().layoutInfo.coordinates
        val size = coordinates.size
        val points = listOf(Offset.Zero, Offset(size.width.toFloat(), 0f),
            Offset(0f, size.height.toFloat()), Offset(size.width.toFloat(), size.height.toFloat()))
            .map { coordinates.localToRoot(it) }
        val scene = compose.onNodeWithTag("popup-clearance-scene").fetchSemanticsNode().boundsInRoot
        return Rect(points.minOf { it.x }, points.minOf { it.y }, points.maxOf { it.x }, points.maxOf { it.y })
            .translate(-scene.topLeft)
    }

    private fun red(color: Color) = color.red > .6f && color.green < .4f && color.blue < .4f

    /** Read the full rendered silhouette; semantic bounds only supply its expected, un-clipped location. */
    private fun frame(label: String): Rect {
        compose.runOnIdle { stage = (stage + 1) % 32 }
        val scene = compose.onNodeWithTag("popup-clearance-scene")
        val sceneWidth = scene.fetchSemanticsNode().boundsInRoot.width
        val markerWidth = with(compose.density) { 8.dp.toPx() }
        val pixels = capturePresentedPopupPixels(compose, scene, stage,
            IntOffset(((sceneWidth - markerWidth) / 2f).toInt() + 2, 2)) { popupView }
        val expected = transformedBounds("choice-lens")
        assertTrue("$label: the complete expected lens must be inside the popup capture: $expected",
            expected.left >= 0f && expected.top >= 0f && expected.right <= pixels.width && expected.bottom <= pixels.height)
        // Restrict the scan to the lens neighborhood so action-label colors can
        // never masquerade as an intact lens. Do not clamp its expected bounds.
        val scan = expected.inflate(3f)
        var left = pixels.width; var top = pixels.height; var right = -1; var bottom = -1
        for (y in floor(scan.top).toInt().coerceAtLeast(0) until ceil(scan.bottom).toInt().coerceAtMost(pixels.height)) {
            for (x in floor(scan.left).toInt().coerceAtLeast(0) until ceil(scan.right).toInt().coerceAtMost(pixels.width)) {
                if (red(pixels[x, y])) {
                    left = minOf(left, x); top = minOf(top, y)
                    right = maxOf(right, x); bottom = maxOf(bottom, y)
                }
            }
        }
        assertTrue("$label: the real selector must render its red source", right >= left && bottom >= top)
        val rendered = Rect(left.toFloat(), top.toFloat(), right + 1f, bottom + 1f)
        assertEquals("$label: left contour", expected.left, rendered.left, 2f)
        assertEquals("$label: top contour", expected.top, rendered.top, 2f)
        assertEquals("$label: right contour", expected.right, rendered.right, 2f)
        assertEquals("$label: bottom contour", expected.bottom, rendered.bottom, 2f)
        assertRoundedContour(pixels, expected, label)
        android.util.Log.i("SelectorPopupClearance", "$label stage=$stage expected=$expected rendered=$rendered")
        return rendered
    }

    /** Corner arcs and all four edges must actually be present, not merely a bounding-box extremum. */
    private fun assertRoundedContour(pixels: PixelMap, expected: Rect, label: String) {
        // Endpoint choices retain their 20 dp circular lens corners through
        // pressure/release. Use the independently rounded production inner inset.
        val radius = corner.coerceAtMost(minOf(expected.width, expected.height) / 2f)
        val inset = 2f // Physical pixels, only to avoid antialiasing at the mathematical boundary.
        val innerRadius = (radius - inset).coerceAtLeast(0f)
        val centers = listOf(
            Offset(expected.left + radius, expected.top + radius) to 180,
            Offset(expected.right - radius, expected.top + radius) to 270,
            Offset(expected.right - radius, expected.bottom - radius) to 0,
            Offset(expected.left + radius, expected.bottom - radius) to 90,
        )
        val samples = centers.flatMap { (center, start) ->
            (0..6).map { step ->
                val angle = Math.toRadians((start + step * 15).toDouble())
                center + Offset(cos(angle).toFloat(), sin(angle).toFloat()) * innerRadius
            }
        } + listOf(
            Offset(expected.center.x, expected.top + inset), Offset(expected.center.x, expected.bottom - inset),
            Offset(expected.left + inset, expected.center.y), Offset(expected.right - inset, expected.center.y),
        )
        for (point in samples) {
            val x = floor(point.x).toInt(); val y = floor(point.y).toInt()
            val visible = (-1..1).any { dy -> (-1..1).any { dx ->
                val px = x + dx; val py = y + dy
                px in 0 until pixels.width && py in 0 until pixels.height && red(pixels[px, py])
            } }
            assertTrue("$label: rounded contour is clipped at $point, expected=$expected radius=$radius", visible)
        }
    }
}
