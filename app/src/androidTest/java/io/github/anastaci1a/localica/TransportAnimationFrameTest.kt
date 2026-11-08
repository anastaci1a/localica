package io.github.anastaci1a.localica

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.ui.*
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class TransportAnimationFrameTest {
    @get:Rule val compose = localicaComposeRule(effectContext = object : MotionDurationScale {
        override val scaleFactor = 1f
    })

    private fun same(a: ImageBitmap, b: ImageBitmap, tolerance: Float = 0f) {
        val x = a.toPixelMap()
        val y = b.toPixelMap()
        assertEquals(a.width, b.width)
        assertEquals(a.height, b.height)
        for (j in 0 until a.height) for (i in 0 until a.width)
            assertEquals("Pixel $i,$j", x[i,j].red, y[i,j].red, tolerance)
    }

    @Test fun renderedFramesMirrorCrossfadeSymmetricallyAndWrapWithoutAJump() {
        var phase by mutableFloatStateOf(0f)
        compose.setContent {
            Row {
                for (glyph in listOf(TransportGlyph.Next, TransportGlyph.Previous)) {
                    Canvas(Modifier.size(100.dp).background(Color.White).testTag(glyph.name)) {
                        drawTransportGlyph(glyph, 0f, phase, Color.Black, TransportShapes())
                    }
                }
            }
        }
        val first = compose.onNodeWithTag("Next").captureNativeFrame()
        for (p in listOf(0f, .1f, .25f, .5f, .75f, .9f, .9999f, 1f)) {
            compose.runOnIdle { phase = p }
            val next = compose.onNodeWithTag("Next").captureNativeFrame().toPixelMap()
            val previous = compose.onNodeWithTag("Previous").captureNativeFrame().toPixelMap()
            for (y in 0 until next.height) for (x in 0 until next.width)
                assertEquals("Mirrored at $p $x,$y", next[x,y].red, previous[next.width - 1 - x,y].red, .025f)
        }
        same(first, compose.onNodeWithTag("Next").captureNativeFrame())
        compose.runOnIdle { phase = .99999f }
        same(first, compose.onNodeWithTag("Next").captureNativeFrame(), .015f)
    }

    /** #654: departing and arriving wedges fade through feathered edges instead of a hard clip. */
    @Test fun skipEdgesTaperSymmetricallyAndTheRestFrameIsTheStaticIcon() {
        var phase by mutableFloatStateOf(0f)
        var static by mutableStateOf(false)
        compose.setContent {
            Row {
                for (glyph in listOf(TransportGlyph.Next, TransportGlyph.Previous)) {
                    // The same slot draws both, since API 36's GPU anti-aliasing varies by 1/255 across window positions.
                    Canvas(Modifier.size(100.dp).background(Color.White).testTag(glyph.name)) {
                        if (!static) drawTransportGlyph(glyph, 0f, phase, Color.Black, TransportShapes())
                        // The static icon: both rest wedges, drawn without any mask or clip.
                        else scale(if (glyph == TransportGlyph.Previous) -1f else 1f, 1f) {
                            for (x in listOf(.08f, .48f)) translate(left = x * size.width) {
                                TransportShapes().skipTriangle(this, Color.Black)
                            }
                        }
                    }
                }
            }
        }
        val rest = listOf(TransportGlyph.Next, TransportGlyph.Previous).associateWith {
            compose.onNodeWithTag(it.name).captureNativeFrame()
        }
        compose.runOnIdle { static = true }
        for ((glyph, frame) in rest) same(compose.onNodeWithTag(glyph.name).captureNativeFrame(), frame)
        compose.runOnIdle { static = false }
        // #654's corrected contract keeps visible travel beyond the original canvas. The padded
        // stock/pack render tests in SkipOverflowTaperTest inspect the actual outside fade bands.
        compose.runOnIdle { phase = .5f }
        for (glyph in listOf(TransportGlyph.Next, TransportGlyph.Previous)) {
            val frame = compose.onNodeWithTag(glyph.name).captureNativeFrame().toPixelMap()
            assertTrue("Moving ink reaches the original canvas edge", frame[frame.width - 1, frame.height / 2].red < .9f)
        }
    }

    @Test fun repeatPressesKeepThePresentedFrameAndReducedMotionSnapsMidFlight() {
        var reduced by mutableStateOf(false)
        var calls = 0
        compose.setContent {
            TransportButton(TransportGlyph.Next, false, "Next", { calls++ },
                Modifier.size(80.dp).background(Color.White).testTag("skip"), 64.dp,
                Color.Black, true, reduced)
        }
        compose.mainClock.autoAdvance = false
        val node = compose.onNodeWithTag("skip")
        val rest = node.captureNativeFrame()
        node.performClick()
        compose.mainClock.advanceTimeBy(112)
        repeat(3) {
            val before = node.captureNativeFrame()
            // Touch injection advances event time even with a paused frame clock.
            // Invoke the same activation at one instant to test the actual handoff.
            val instant = compose.mainClock.currentTime
            node.performSemanticsAction(SemanticsActions.OnClick) { it() }
            assertEquals("Activation must not advance the frame under comparison", instant, compose.mainClock.currentTime)
            same(before, node.captureNativeFrame())
            compose.mainClock.advanceTimeBy(48)
        }
        assertEquals(4, calls)
        compose.runOnIdle { reduced = true }
        compose.mainClock.advanceTimeByFrame()
        same(rest, node.captureNativeFrame())
        node.performClick()
        compose.mainClock.advanceTimeBy(64)
        same(rest, node.captureNativeFrame())
        assertEquals(5, calls)
    }

    @Test fun fasterSettingShortensTheActualButtonCycle() {
        var speed by mutableFloatStateOf(4f / 3f)
        compose.setContent {
            CompositionLocalProvider(LocalMotionSpeed provides speed) {
                TransportButton(TransportGlyph.Next, false, "Next", {},
                    Modifier.size(100.dp).background(Color.White).testTag("skip"), 100.dp,
                    Color.Black, true, false)
            }
        }
        compose.mainClock.autoAdvance = false
        val node = compose.onNodeWithTag("skip")
        val rest = node.captureNativeFrame()
        node.performClick()
        compose.mainClock.advanceTimeBy(304)
        assertFalse("Normal cycle is still settling at 304 ms",
            rest.asAndroidBitmap().sameAs(node.captureNativeFrame().asAndroidBitmap()))
        compose.mainClock.advanceTimeBy(160)
        same(rest, node.captureNativeFrame())
        compose.runOnIdle { speed = 2f }
        compose.mainClock.advanceTimeByFrame()
        node.performClick()
        compose.mainClock.advanceTimeBy(304)
        same(rest, node.captureNativeFrame())
    }

    @Test fun configurablePlayPauseEndpointsKeepTheMorphAndSinglePlayContour() {
        var playing by mutableStateOf(false)
        val custom = PlayPauseShapes(
            playOutline = listOf(Offset(.1f,.2f), Offset(.9f,.5f), Offset(.1f,.8f)),
            playLeft = listOf(Offset(.1f,.2f), Offset(.5f,.35f), Offset(.5f,.65f), Offset(.1f,.8f)),
            playRight = listOf(Offset(.5f,.35f), Offset(.9f,.5f), Offset(.9f,.5f), Offset(.5f,.65f)),
            pauseLeft = listOf(Offset(.1f,.2f), Offset(.3f,.2f), Offset(.3f,.8f), Offset(.1f,.8f)),
            pauseRight = listOf(Offset(.7f,.2f), Offset(.9f,.2f), Offset(.9f,.8f), Offset(.7f,.8f)))
        val shapes = TransportShapes(playPause = custom)
        compose.setContent {
            CompositionLocalProvider(LocalTransportShapes provides shapes) {
                Row {
                    TransportButton(TransportGlyph.PlayPause, playing, "Play", {},
                        Modifier.size(80.dp).background(Color.White).testTag("actual"), 80.dp,
                        Color.Black, true, false)
                    Canvas(Modifier.size(80.dp).background(Color.White).testTag("expected")) {
                        drawTransportGlyph(TransportGlyph.PlayPause, if (playing) 1f else 0f, 0f, Color.Black, shapes)
                    }
                }
            }
        }
        same(compose.onNodeWithTag("expected").captureNativeFrame(), compose.onNodeWithTag("actual").captureNativeFrame())
        compose.mainClock.autoAdvance = false
        val play = compose.onNodeWithTag("actual").captureNativeFrame()
        compose.runOnIdle { playing = true }
        compose.mainClock.advanceTimeBy(112)
        val intermediate = compose.onNodeWithTag("actual").captureNativeFrame()
        assertFalse("Existing morph still interpolates", play.asAndroidBitmap().sameAs(intermediate.asAndroidBitmap()))
        compose.mainClock.advanceTimeBy(400)
        same(compose.onNodeWithTag("expected").captureNativeFrame(), compose.onNodeWithTag("actual").captureNativeFrame())
    }

    @Test fun exportMiniAndFullPlayerFrameSequenceAtNormalAndFasterSpeeds() {
        var speed by mutableFloatStateOf(4f / 3f)
        var playing by mutableStateOf(false)
        compose.setContent {
            CompositionLocalProvider(LocalMotionSpeed provides speed) {
                Column(Modifier.background(Color.White).padding(16.dp).testTag("strip")) {
                    for (side in listOf(24, 40)) Row {
                        for (glyph in TransportGlyph.entries) {
                            val iconSide = if (side == 40 && glyph != TransportGlyph.PlayPause) 34 else side
                            TransportButton(glyph, playing, "$side ${glyph.name}", {},
                                Modifier.size(64.dp).testTag("$side-${glyph.name}"), iconSide.dp,
                                Color.Black, true, false)
                        }
                    }
                }
            }
        }
        compose.mainClock.autoAdvance = false
        val root = (InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.takeIf { it.isNotBlank() }?.let(::File)
            ?: requireNotNull(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null)))
            .let { File(it, "transport-571") }.apply { mkdirs() }
        fun save(name: String) {
            val shot = compose.onNodeWithTag("strip").captureNativeFrame()
            File(root, "$name-api${android.os.Build.VERSION.SDK_INT}.png").outputStream().use {
                shot.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
        for ((name, motionSpeed) in listOf("normal" to (4f / 3f), "faster" to 2f)) {
            compose.runOnIdle { speed = motionSpeed; playing = false }
            compose.mainClock.advanceTimeBy(500)
            save("$name-00")
            compose.runOnIdle { playing = true }
            for (side in listOf(24, 40)) for (glyph in listOf(TransportGlyph.Previous, TransportGlyph.Next))
                compose.onNodeWithTag("$side-${glyph.name}").performSemanticsAction(SemanticsActions.OnClick) { it() }
            for (i in 1..16) {
                compose.mainClock.advanceTimeBy(32)
                save("$name-${i.toString().padStart(2, '0')}")
            }
        }
    }
}
