package io.github.anastaci1a.localica

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.*
import io.github.anastaci1a.localica.localicaComposeRule as createComposeRule
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.ui.*
import io.github.anastaci1a.localica.ui.engine.Curve
import org.junit.Assert.*
import kotlin.math.roundToInt
import org.junit.Rule
import org.junit.Test

class LyricsHeaderActionsTest {
    @get:Rule val compose = createComposeRule()

    private fun advanceWithNativeFrames(durationMillis: Long) {
        if (durationMillis <= 0) return
        val start = compose.mainClock.currentTime
        compose.mainClock.advanceTimeByFrame()
        val frameMillis = compose.mainClock.currentTime - start
        compose.waitForIdle()
        // Only these unobserved endpoint interiors are batched. Measure one clock
        // frame, then spend the same ceil(duration/frame) budget without early exit.
        // Four-frame batches still yield to native layout/draw/window work.
        var remainingFrames = (durationMillis - 1) / frameMillis
        while (remainingFrames > 0) {
            val batchFrames = minOf(4L, remainingFrames)
            compose.mainClock.advanceTimeBy(batchFrames * frameMillis)
            compose.waitForIdle()
            remainingFrames -= batchFrames
        }
    }

    @Test fun realHeaderSplitsBeforeOwnershipAndRejoinsAfterReturn() {
        var sources by mutableStateOf(false)
        lateinit var anchor: GlassAnchor
        lateinit var preparation: GlassOriginPreparation
        lateinit var backing: FrostedBackdrop
        compose.setContent { EngineTheme(clear = true) {
            anchor = rememberGlassAnchor()
            preparation = rememberGlassOriginPreparation()
            backing = rememberFrostedBackdrop(Unit)
            Box(Modifier.fillMaxSize()) {
                Canvas(Modifier.fillMaxSize().recordFrostedBackdrop(backing)) {
                    val tile = 16.dp.toPx()
                    val colors = listOf(Color(0xFF8BC7BD), Color(0xFFF0C06F), Color(0xFF7D91C9), Color(0xFFCD83A7))
                    for (y in 0..(size.height / tile).toInt()) for (x in 0..(size.width / tile).toInt()) {
                        drawRect(colors[(x + 3 * y) % colors.size], Offset(x * tile, y * tile), Size(tile, tile))
                    }
                }
                CompositionLocalProvider(LocalFrostedBackdrop provides backing) { Box(Modifier.padding(32.dp)) {
                LyricsHeaderActions(true, 1f, anchor, preparation, {}, { sources = true }, {})
                GlassMenu(sources, anchor, { sources = false }, originPreparation = preparation) {
                    ActionMenuItem("Embedded lyrics", icon = AppIcon.ActionLyrics) { sources = false }
                }
                } }
            }
        } }
        compose.waitForIdle()
        if (android.os.Build.VERSION.SDK_INT < 33) compose.waitUntil(10000) { backing.snapshot != null }
        fun capture(name: String) {
            val bitmap = compose.onNodeWithTag("player-lyrics-pill").captureToImage().asAndroidBitmap()
            val folder = nativeEvidenceDirectory("lyrics-header-actions",
                androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext.cacheDir)
            java.io.File(folder, "lyrics-header-$name.png").outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
        }
        compose.mainClock.autoAdvance = false
        try {
            capture("joined")
            compose.onNodeWithContentDescription("Lyrics sources").performTouchInput { down(center) }
            compose.mainClock.advanceTimeBy(160)
            capture("held")
            compose.onNodeWithContentDescription("Lyrics sources").performTouchInput { cancel() }
            compose.mainClock.advanceTimeBy(4000)
            compose.onNodeWithContentDescription("Lyrics sources").performTouchInput { down(center) }
            compose.mainClock.advanceTimeBy(32)
            compose.onNodeWithContentDescription("Lyrics sources").performTouchInput { up() }
            compose.waitUntil(5000) {
                compose.mainClock.advanceTimeByFrame()
                compose.waitForIdle()
                preparation.progress > 0f
            }
            capture("preparing")
            compose.runOnIdle {
                assertTrue(preparation.progress > 0f)
                assertFalse(anchor.hidden)
                assertFalse(anchor.modalPresent)
            }
            compose.onNodeWithContentDescription("Hide lyrics").assertExists()
            advanceWithNativeFrames(4000)
            capture("modal-owned")
            compose.runOnIdle {
                assertTrue(anchor.modalPresent)
                assertTrue(anchor.hidden)
                assertEquals(1f, preparation.progress, 0f)
            }
            compose.runOnIdle { sources = false }
            compose.mainClock.advanceTimeByFrame()
            compose.runOnIdle { assertEquals(1f, preparation.progress, 0f) }
            advanceWithNativeFrames(4000)
            capture("rejoined")
            compose.runOnIdle {
                assertFalse(anchor.hidden)
                assertFalse(anchor.modalPresent)
                assertEquals(0f, preparation.progress, 0f)
            }
            compose.onNodeWithContentDescription("Lyrics sources").assertExists()
            compose.onNodeWithContentDescription("Hide lyrics").assertExists()
        } finally { compose.mainClock.autoAdvance = true }
    }

    /** #1076: the header's shadow plane draws over its glass, so each cast body must be the body its surface
     * draws while held. Every pixel the held glass covers matches a shadowless render of the same frame. */
    @Test fun heldExpandedHeaderCastsNoShadowInsideItsBody() = heldBodyCastsNoShadowInside("Show lyrics", separated = false)

    /** The separated right body lifts and drifts toward an off-centre contact. */
    @Test fun heldSeparatedRightBodyCastsNoShadowInsideItsBody() = heldBodyCastsNoShadowInside("Hide lyrics", separated = true)

    /** The anchored left body grows under the same press law as the other bodies. */
    @Test fun heldAnchoredLeftBodyCastsNoShadowInsideItsBody() = heldBodyCastsNoShadowInside("Lyrics sources", separated = true)

    @Test fun heldHeaderSplitKeepsJoinedPressPoseAtHandoff() {
        val density = 2.625f
        val backdrop = Color(0xFF3F6FB0)
        val phase = Animatable(.4999f)
        lateinit var anchor: GlassAnchor
        lateinit var preparation: GlassOriginPreparation
        compose.setContent { EngineTheme {
            anchor = rememberGlassAnchor()
            preparation = rememberGlassOriginPreparation()
            CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(density),
                LocalGlassAppearance provides LocalGlassAppearance.current.copy(shadowStrength = 0f, touchLight = 0f)) {
                Box(Modifier.fillMaxSize().background(backdrop).padding(32.dp)) {
                    LyricsHeaderActions(true, 1f, anchor, preparation, {}, {}, {})
                }
            }
        } }
        compose.runOnIdle { preparation.bind(AnimatablePhase(phase), Curve.linear(0f, 1f)) }
        compose.waitForIdle()
        val header = compose.onNodeWithTag("player-lyrics-pill")
        val node = compose.onNodeWithContentDescription("Lyrics sources")
        compose.mainClock.autoAdvance = false
        try {
            node.performTouchInput { down(center) }
            compose.mainClock.advanceTimeBy(2000)
            for (separated in listOf(false, true)) {
                if (separated) {
                    compose.runOnUiThread { kotlinx.coroutines.runBlocking { phase.snapTo(.5f) } }
                    compose.mainClock.advanceTimeBy(32)
                }
                compose.waitForIdle()
                val bounds = header.fetchSemanticsNode().boundsInRoot
                val width = bounds.width
                val height = bounds.height
                val half = if (separated) height else width / 2f
                val scale = 1.0 + 16.0 * density / width
                fun x(value: Float) = (width / 2 + (value - width / 2) * scale).toFloat()
                val top = (height * (1 - scale) / 2).toFloat()
                val bottom = height - top
                val want = listOf(Rect(x(0f), top, x(half), bottom), Rect(x(width - half), top, x(width), bottom))
                compose.runOnIdle {
                    assertEquals(separated, preparation.separated)
                    val cast = requireNotNull(anchor.shadowPlane).casters.mapNotNull {
                        (it.shape as io.github.anastaci1a.localica.ui.engine.LayoutShape).laidOut()?.boundingRect
                    }.sortedBy { it.left }
                    assertEquals(2, cast.size)
                    for ((expected, actual) in want.zip(cast)) {
                        assertEquals("held handoff left separated=$separated", expected.left, actual.left, .01f)
                        assertEquals("held handoff top separated=$separated", expected.top, actual.top, .01f)
                        assertEquals("held handoff right separated=$separated", expected.right, actual.right, .01f)
                        assertEquals("held handoff bottom separated=$separated", expected.bottom, actual.bottom, .01f)
                    }
                }
                val image = compose.onRoot().captureToImage().asAndroidBitmap()
                for (expected in want) {
                    val column = (bounds.left + expected.center.x).roundToInt()
                    val occupied = ((bounds.top - height / 2).toInt()..(bounds.bottom + height / 2).toInt()).filter { row ->
                        val pixel = image.getPixel(column, row)
                        kotlin.math.abs(android.graphics.Color.red(pixel) - android.graphics.Color.red(backdrop.toArgb())) > 10
                    }
                    assertTrue("held handoff covers the body centre separated=$separated", occupied.isNotEmpty())
                    assertEquals("rendered top separated=$separated", bounds.top + top, occupied.first().toFloat(), 1.5f)
                    assertEquals("rendered bottom separated=$separated", bounds.top + bottom, occupied.last() + 1f, 1.5f)
                }
            }
            node.performTouchInput { cancel() }
        } finally { compose.mainClock.autoAdvance = true }
    }

    /** #1076: separated casters are the measured surfaces (CompactControlSize squares at either end of the
     * header), exactly, at fractional densities whose header width rounds independently, and mid-separation. */
    @Test fun separatedCastersMatchTheirMeasuredSurfacesAtRoundedSizes() {
        var density by mutableStateOf(1.75f)
        val phase = Animatable(1f)
        lateinit var anchor: GlassAnchor
        lateinit var preparation: GlassOriginPreparation
        compose.setContent { EngineTheme {
            anchor = rememberGlassAnchor()
            preparation = rememberGlassOriginPreparation()
            CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(density)) {
                Box(Modifier.padding(32.dp)) { LyricsHeaderActions(true, 1f, anchor, preparation, {}, {}, {}) }
            }
        } }
        compose.runOnIdle { preparation.bind(AnimatablePhase(phase), Curve.linear(0f, 1f)) }
        for (value in listOf(1.75f, 2.625f, 2.75f, 3f, 3.5f)) for (progress in listOf(.75f, 1f)) {
            compose.runOnUiThread { density = value; kotlinx.coroutines.runBlocking { phase.snapTo(progress) } }
            compose.waitForIdle()
            val width = compose.onNodeWithTag("player-lyrics-pill").fetchSemanticsNode().size.width.toFloat()
            val side = (CompactControlSize.value * value).roundToInt().toFloat()
            val expected = listOf(androidx.compose.ui.geometry.Rect(0f, 0f, side, side), androidx.compose.ui.geometry.Rect(width - side, 0f, width, side))
            compose.runOnIdle {
                assertTrue(preparation.separated)
                val cast = requireNotNull(anchor.shadowPlane).casters.mapNotNull { (it.shape as io.github.anastaci1a.localica.ui.engine.LayoutShape).laidOut()?.let { body ->
                    androidx.compose.ui.geometry.Rect(body.left, body.top, body.right, body.bottom) } }.sortedBy { it.left }
                assertEquals(2, cast.size)
                for ((want, got) in expected.zip(cast)) for ((a, b) in listOf(want.left to got.left, want.top to got.top, want.right to got.right, want.bottom to got.bottom))
                    assertEquals("density $value, progress $progress: cast $got, surface $want", a, b, .01f)
            }
        }
        // Held mid-separation at a fractional density: the left cast is the body the anchored surface draws,
        // posed by that surface's own presentation at its measured height (pivot shift included).
        compose.runOnUiThread { density = 1.75f; kotlinx.coroutines.runBlocking { phase.snapTo(.75f) } }
        compose.waitForIdle()
        val measured = compose.onNodeWithContentDescription("Lyrics sources").fetchSemanticsNode().size.height.toFloat()
        compose.mainClock.autoAdvance = false
        try {
            compose.onNodeWithContentDescription("Lyrics sources").performTouchInput { down(Offset(width * .3f, height * .35f)) }
            compose.mainClock.advanceTimeBy(2000)
            compose.runOnIdle {
                val pose = requireNotNull(anchor.originPresentation)(measured)
                assertTrue("Held mid-separation shifts the pivot (${pose.horizontalShift})", kotlin.math.abs(pose.horizontalShift) > .01f)
                val drawn = pose.transform(Size(measured, measured), density)
                val square = androidx.compose.ui.geometry.RoundRect(0f, 0f, measured, measured, androidx.compose.ui.geometry.CornerRadius(measured / 2f))
                val want = square.deformed(drawn.copy(drift = drawn.drift + Offset(pose.horizontalShift, 0f)), Offset(measured / 2f, measured / 2f))
                val got = requireNotNull(anchor.shadowPlane).casters.mapNotNull { (it.shape as io.github.anastaci1a.localica.ui.engine.LayoutShape).laidOut() }.minBy { it.left }
                for ((a, b) in listOf(want.left to got.left, want.top to got.top, want.right to got.right, want.bottom to got.bottom))
                    assertEquals("held at density 1.75, progress .75: cast $got, surface $want", a, b, .01f)
            }
            compose.onNodeWithContentDescription("Lyrics sources").performTouchInput { cancel() }
        } finally { compose.mainClock.autoAdvance = true }
    }

    private fun heldBodyCastsNoShadowInside(label: String, separated: Boolean) {
        val backdrop = Color(0xFF3F6FB0)
        var shadows by mutableStateOf(true)
        lateinit var backing: FrostedBackdrop
        lateinit var preparation: GlassOriginPreparation
        compose.setContent { EngineTheme {
            val anchor = rememberGlassAnchor()
            preparation = rememberGlassOriginPreparation()
            backing = rememberFrostedBackdrop(Unit)
            Box(Modifier.fillMaxSize()) {
                Canvas(Modifier.fillMaxSize().recordFrostedBackdrop(backing)) { drawRect(backdrop) }
                CompositionLocalProvider(LocalFrostedBackdrop provides backing, LocalGlassAppearance provides
                    LocalGlassAppearance.current.copy(shadowStrength = if (shadows) 1f else 0f)) {
                    Box(Modifier.padding(48.dp)) { LyricsHeaderActions(separated, if (separated) 1f else 0f, anchor, preparation, {}, {}, {}) }
                }
            }
        } }
        if (separated) compose.runOnIdle { preparation.bind(AnimatablePhase(Animatable(1f)), Curve.linear(0f, 1f)) }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(separated, preparation.separated) }
        if (android.os.Build.VERSION.SDK_INT < 33) compose.waitUntil(10000) { backing.snapshot != null }
        val rest = compose.onNodeWithTag("player-lyrics-pill").fetchSemanticsNode().boundsInRoot
        val margin = rest.height.toInt()
        val left = (rest.left.toInt() - margin).coerceAtLeast(0); val top = (rest.top.toInt() - margin).coerceAtLeast(0)
        fun shot(): IntArray {
            val image = compose.onRoot().captureToImage().asAndroidBitmap()
            val width = minOf(rest.width.toInt() + 2 * margin, image.width - left); val height = minOf(rest.height.toInt() + 2 * margin, image.height - top)
            return IntArray(width * height + 2).also { image.getPixels(it, 0, width, left, top, width, height); it[it.size - 2] = width; it[it.size - 1] = height }
        }
        fun difference(a: Int, b: Int) = maxOf(kotlin.math.abs(android.graphics.Color.red(a) - android.graphics.Color.red(b)),
            kotlin.math.abs(android.graphics.Color.green(a) - android.graphics.Color.green(b)), kotlin.math.abs(android.graphics.Color.blue(a) - android.graphics.Color.blue(b)))
        fun bareShot(): IntArray {
            compose.runOnIdle { shadows = false }
            compose.mainClock.advanceTimeBy(100)
            return shot().also { compose.runOnIdle { shadows = true }; compose.mainClock.advanceTimeBy(100) }
        }
        compose.mainClock.autoAdvance = false
        try {
            val resting = bareShot()
            compose.onNodeWithContentDescription(label).performTouchInput { down(Offset(width * .3f, height * .35f)) }
            compose.mainClock.advanceTimeBy(2000)
            val cast = shot()
            val bare = bareShot()
            val again = shot()
            val width = cast[cast.size - 2]; val height = cast[cast.size - 1]
            val pixels = width * height
            assertEquals("The held frame is settled", 0, (0 until pixels).count { difference(cast[it], again[it]) > 3 })
            fun coverage(image: IntArray) = BooleanArray(pixels) { difference(image[it], backdrop.toArgb()) > 10 }
            val covered = coverage(bare); val restCovered = coverage(resting)
            val grown = (0 until pixels).count { covered[it] && !restCovered[it] }
            assertTrue("The held glass grows past its resting body (grown $grown)", grown > 40)
            // Erode the glass coverage so antialiased silhouette pixels never decide the result.
            fun inside(x: Int, y: Int) = (-3..3).all { dy -> (-3..3).all { dx ->
                val px = x + dx; val py = y + dy; px in 0 until width && py in 0 until height && covered[py * width + px] } }
            assertTrue("The shadow plane draws", (0 until pixels).any { difference(cast[it], bare[it]) > 3 })
            val shaded = (0 until pixels).count { difference(cast[it], bare[it]) > 3 && inside(it % width, it / width) }
            assertEquals("Shadow pixels inside the held glass body", 0, shaded)
            compose.onNodeWithContentDescription(label).performTouchInput { cancel() }
        } finally { compose.mainClock.autoAdvance = true }
    }

    @Test fun interruptedPreparationDoesNotLeaveTheTriggerHidden() {
        var open by mutableStateOf(true)
        var sources by mutableStateOf(false)
        lateinit var preparation: GlassOriginPreparation
        lateinit var anchor: GlassAnchor
        compose.setContent { EngineTheme {
            preparation = rememberGlassOriginPreparation()
            anchor = rememberGlassAnchor()
            Box(Modifier.fillMaxSize().padding(32.dp)) {
                LyricsHeaderActions(open, if (open) 1f else 0f, anchor, preparation,
                    { open = true }, { sources = true }, { open = false; sources = false })
                GlassMenu(sources, anchor, { sources = false }, originPreparation = preparation) {
                    ActionMenuItem("Embedded lyrics", icon = AppIcon.ActionLyrics) { sources = false }
                }
            }
        } }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        try {
            compose.onNodeWithContentDescription("Lyrics sources").performClick()
            compose.mainClock.advanceTimeBy(80)
            // Route through Android, not a semantic onClick: invisible native
            // premeasurement must leave the neighboring Hide control usable.
            val node = compose.onNodeWithContentDescription("Hide lyrics").fetchSemanticsNode()
            val view = (node.root as androidx.compose.ui.platform.ViewRootForTest).view
            val location = IntArray(2)
            compose.runOnUiThread { view.getLocationOnScreen(location) }
            val point = node.boundsInRoot.center + Offset(location[0].toFloat(), location[1].toFloat())
            val automation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation
            val down = android.os.SystemClock.uptimeMillis()
            fun send(action: Int) {
                val event = android.view.MotionEvent.obtain(down, android.os.SystemClock.uptimeMillis(), action, point.x, point.y, 0)
                event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
                try { assertTrue("Native Hide input reaches underlying window", automation.injectInputEvent(event, true)) }
                finally { event.recycle() }
            }
            var held = false
            try {
                send(android.view.MotionEvent.ACTION_DOWN); held = true
                send(android.view.MotionEvent.ACTION_UP); held = false
            } finally { if (held) send(android.view.MotionEvent.ACTION_CANCEL) }
            compose.waitForIdle()
            compose.runOnIdle { assertFalse("Native Hide remains usable before source admission", open) }
            compose.mainClock.advanceTimeBy(4000)
            compose.runOnIdle {
                assertFalse(anchor.hidden)
                assertFalse(anchor.modalPresent)
                assertEquals(0f, preparation.progress, 0f)
            }
            compose.onNodeWithContentDescription("Show lyrics").performClick()
            compose.mainClock.advanceTimeByFrame()
            compose.onNodeWithContentDescription("Lyrics sources").assertExists()
        } finally { compose.mainClock.autoAdvance = true }
    }
}
