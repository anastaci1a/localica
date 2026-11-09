package io.github.anastaci1a.localica

import android.graphics.Bitmap
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import io.github.anastaci1a.localica.localicaComposeRule as createComposeRule
import androidx.compose.ui.unit.*
import io.github.anastaci1a.localica.navigation.*
import io.github.anastaci1a.localica.playback.PlaybackState
import io.github.anastaci1a.localica.ui.*
import io.github.anastaci1a.localica.ui.engine.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import kotlin.math.roundToInt

class MotionFollowupTest {
    @get:Rule val compose = createComposeRule()

    @Test fun retainedHeaderDrawingHasMonotonePixelEdgesAndExactIntegerEndpoints() {
        val bitmap = Bitmap.createBitmap(40, 32, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.WHITE) }
        val image = bitmap.asImageBitmap()
        val source = Rect(19f, 17f, 67f, 49f)
        val target = Rect(3f, 5f, 91f, 73f)
        fun render(rect: Rect, legacy: Boolean = false): Bitmap {
            val output = Bitmap.createBitmap(96, 80, Bitmap.Config.ARGB_8888)
            CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(output.asImageBitmap()), Size(96f, 80f)) {
                if (legacy) drawImage(image, srcOffset = IntOffset(4, 3), srcSize = IntSize(32, 24),
                    dstOffset = IntOffset(rect.left.toInt(), rect.top.toInt()),
                    dstSize = IntSize(rect.width.toInt(), rect.height.toInt()))
                else drawMotionImage(image, IntOffset(4, 3), IntSize(32, 24), rect)
            }
            return output
        }
        fun bounds(output: Bitmap): Rect {
            var left = output.width; var top = output.height; var right = 0; var bottom = 0
            for (y in 0 until output.height) for (x in 0 until output.width) {
                if (android.graphics.Color.alpha(output.getPixel(x, y)) >= 128) {
                    left = minOf(left, x); top = minOf(top, y); right = maxOf(right, x + 1); bottom = maxOf(bottom, y + 1)
                }
            }
            assertTrue(left < right && top < bottom)
            return Rect(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat())
        }
        try {
            val frames = (0..128).map { step ->
                val p = step / 128f
                fun mix(a: Float, b: Float) = a + (b - a) * p
                val rect = Rect(mix(source.left, target.left), mix(source.top, target.top),
                    mix(source.right, target.right), mix(source.bottom, target.bottom))
                val rendered = render(rect)
                try { bounds(rendered) } finally { rendered.recycle() }
            }
            frames.zipWithNext().forEach { (a, b) ->
                assertTrue("Retained header edges reverse: $a -> $b",
                    b.left <= a.left && b.top <= a.top && b.right >= a.right && b.bottom >= a.bottom)
            }
            assertEquals(source, frames.first()); assertEquals(target, frames.last())
            for (rect in listOf(source, target)) {
                val old = render(rect, legacy = true); val actual = render(rect)
                try {
                    for (y in 0 until old.height) for (x in 0 until old.width)
                        assertEquals("Integer endpoint pixel $rect ($x,$y)", old.getPixel(x, y), actual.getPixel(x, y))
                } finally { old.recycle(); actual.recycle() }
            }
        } finally { bitmap.recycle() }
    }

    @Test fun playerArtworkAndGestureFollowTheSameRoundedGrowthEdgesInBothDirections() {
        val progress = Animatable(0f)
        var layout by mutableStateOf(ArtworkLayout.Floating)
        var aspect by mutableFloatStateOf(1.6f)
        var growth: PlayerArtworkGrowthPresentation? = null
        lateinit var scope: CoroutineScope
        val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.RED) }
        val image = bitmap.asImageBitmap()
        val originalClock = compose.mainClock.autoAdvance
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(2.625f)) {
                LocalicaTheme {
                    CompositionLocalProvider(LocalPlayerArtworkGrowthObserver provides { growth = it }) {
                        BoxWithConstraints(Modifier.requiredSize(280.dp, 620.dp)) {
                            scope = rememberCoroutineScope()
                            val palette = PlayerPalette(Color.DarkGray, Color.DarkGray, Color.DarkGray, aspect = aspect)
                            PlayerSurface(PlaybackState(connected = true, title = "Evening", artist = "Artist", durationMs = 120000),
                                false, {}, InterfaceSettings(artworkLayout = layout, reducedMotion = true), progress,
                                {}, {}, {}, {}, {}, {}, visual = PlayerVisual(image, palette))
                        }
                    }
                }
            }
        }
        // #678 instrumentation (log only): where the method's time goes.
        val methodStart = System.nanoTime()
        var snapNs = 0L; var rectNs = 0L; var samples = 0
        fun rect(tag: String): Rect {
            val t = System.nanoTime()
            val c = compose.onNodeWithTag(tag, true).fetchSemanticsNode().layoutInfo.coordinates
            return compose.runOnUiThread { c.findRootCoordinates().localBoundingBoxOf(c, clipBounds = false) }.also { rectNs += System.nanoTime() - t }
        }
        fun sample(p: Float): Pair<Rect, Rect> {
            val sampleStart = System.nanoTime(); samples++
            compose.runOnIdle {
                val update = scope.launch(start = CoroutineStart.UNDISPATCHED) { progress.snapTo(p) }
                assertTrue(update.isCompleted)
            }
            compose.mainClock.advanceTimeByFrame(); compose.waitForIdle()
            snapNs += System.nanoTime() - sampleStart
            val observed = requireNotNull(growth)
            assertEquals(p, observed.expansion, 0f)
            val t = observed.trajectory[(p * 256).roundToInt()]
            val q = playerArtworkSizeProgress(p, observed.curve)
            val width = 32f + (observed.fullWidth - 32f) * q
            val height = 32f + (observed.fullHeight - 32f) * q
            val raw = Rect(t.artworkLeft * 2.625f, t.artworkTop * 2.625f,
                (t.artworkLeft + width) * 2.625f, (t.artworkTop + height) * 2.625f)
            val expected = motionPixelRect(raw)
            val background = rect("player-background-space")
            val actual = rect("player-artwork").translate(-background.topLeft)
            val gesture = rect("artwork-gesture").translate(-background.topLeft)
            val rounded = Rect(expected.left.toFloat(), expected.top.toFloat(), expected.right.toFloat(), expected.bottom.toFloat())
            fun equal(label: String, a: Rect, b: Rect) {
                assertEquals("$label left p=$p", a.left, b.left, .01f)
                assertEquals("$label top p=$p", a.top, b.top, .01f)
                assertEquals("$label right p=$p", a.right, b.right, .01f)
                assertEquals("$label bottom p=$p", a.bottom, b.bottom, .01f)
            }
            equal("artwork", rounded, actual); equal("gesture", actual, gesture)
            if (p == 0f || p == 1f) android.util.Log.i("MotionEndpoint", "layout=$layout aspect=$aspect p=$p raw=$raw rounded=$rounded oldRight=${raw.left.roundToInt() + (width * 2.625f).roundToInt()} oldBottom=${raw.top.roundToInt() + (height * 2.625f).roundToInt()}")
            return raw to actual
        }
        try {
            compose.waitForIdle()
            for (mode in listOf(ArtworkLayout.Floating, ArtworkLayout.FullBackground)) for (ratio in listOf(1.6f, .625f)) {
                compose.runOnIdle { layout = mode; aspect = ratio }
                compose.waitForIdle()
                val forward = (0..64).map { sample(it / 64f) }
                val reverse = (64 downTo 0).map { sample(it / 64f) }
                forward.zip(reverse.reversed()).forEach { (a, b) -> assertEquals("Collapse retraces rounded expansion", a.second, b.second) }
                fun edges(r: Rect) = listOf(r.left, r.top, r.right, r.bottom)
                forward.zipWithNext().forEach { (a, b) ->
                    for (edge in 0..3) {
                        val delta = edges(b.first)[edge] - edges(a.first)[edge]
                        val moved = edges(b.second)[edge] - edges(a.second)[edge]
                        assertTrue("Rounded edge must not oppose its source trajectory", delta * moved >= -.001f)
                    }
                }
            }
            android.util.Log.i("Trim678", "MotionFollowupTest.playerArtwork totalMs=${(System.nanoTime() - methodStart) / 1_000_000} samples=$samples " +
                "snapMs=${snapNs / 1_000_000} rectMs=${rectNs / 1_000_000}")
        } finally { compose.mainClock.autoAdvance = originalClock }
        // The rendered image is still composition-owned; do not recycle it here.
    }
}
