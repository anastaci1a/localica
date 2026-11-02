package io.github.anastaci1a.localica

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** A skip must crossfade the player background without a brightness pop (#507). */
class PlayerSkipBackgroundTest {
    @get:Rule val compose = createComposeRule()
    private val tone = GlassTone(.25f, 2f, true)

    private fun wash(gray: Int) = Bitmap.createBitmap(ArtworkWash.WIDTH, ArtworkWash.HEIGHT, Bitmap.Config.ARGB_8888)
        .apply { eraseColor(android.graphics.Color.rgb(gray, gray, gray)) }
    private fun visual(key: String, gray: Int): PlayerVisual {
        val color = Color(gray / 255f, gray / 255f, gray / 255f)
        return PlayerVisual(palette = PlayerPalette(color, color, color, wash = wash(gray)), contentKey = key)
    }

    @Test fun blurredEdgesNeverSpikeDuringTheFirstFramesOfAnOpacitySkip() = edgesAcrossASkip(true)
    @Test fun blurredEdgesNeverSpikeDuringTheFirstFramesOfABlurThroughSkip() = edgesAcrossASkip(false)

    private fun edgesAcrossASkip(opacityBlend: Boolean) {
        // Mid-brightness covers: the untoned wash differs most from the toned one here.
        var current by mutableStateOf(visual("a", 150))
        var visit by mutableStateOf("first")
        compose.setContent { Box(Modifier.size(160.dp).testTag("blend")) {
            ArtworkBlend(current, Modifier.size(160.dp), 400, opacityBlend = opacityBlend, occurrence = visit) { frame ->
                PlayerBackdrop(frame.palette, Modifier.size(160.dp), 1f, 0f, 1000f, 0f, 0f, 0f, tone)
            }
        } }
        // Only the blurred outer area at the left and right edges, never the middle where the art sits.
        fun edges(): Float {
            val pixels = compose.onNodeWithTag("blend").captureToImage().toPixelMap()
            var sum = 0f; var count = 0
            for (y in 10 until pixels.height - 10 step 13) {
                for (x in (pixels.width * .02f).toInt()..(pixels.width * .1f).toInt() step 5) { sum += pixels[x, y].green; count++ }
                for (x in (pixels.width * .9f).toInt()..(pixels.width * .98f).toInt() step 5) { sum += pixels[x, y].green; count++ }
            }
            return sum / count
        }
        val toneA = tone.channel(150 / 255f); val toneB = tone.channel(110 / 255f)
        assertTrue("The tone must visibly darken the raw wash", 150 / 255f - toneA > .06f)
        compose.waitUntil(10_000) { kotlin.math.abs(edges() - toneA) < .025f }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { current = visual("b", 110); visit = "second" }
        val sequence = mutableListOf<Float>()
        repeat(40) { compose.mainClock.advanceTimeByFrame(); sequence += edges() }
        compose.mainClock.autoAdvance = true
        compose.waitUntil(10_000) { kotlin.math.abs(edges() - toneB) < .025f }
        val ceiling = maxOf(toneA, toneB) + .025f
        assertTrue("Edge brightness spiked above both endpoints ($ceiling): $sequence", sequence.all { it <= ceiling })
        assertTrue("The skip must reach the new cover: $sequence", kotlin.math.abs(sequence.last() - toneB) < .05f)
    }

    @Test fun backgroundFrameWeightsFormAContinuousCrossfade() {
        var current by mutableStateOf(visual("a", 90))
        var visit by mutableStateOf("first")
        val frames = mutableListOf<ArtworkBackgroundFrame>()
        compose.setContent { Box(Modifier.size(80.dp)) {
            ArtworkBlend(current, Modifier.size(80.dp), 400, opacityBlend = true, occurrence = visit,
                onBackgroundFrame = { frames += it }) { frame ->
                PlayerBackdrop(frame.palette, Modifier.size(80.dp), 0f, tone = tone)
            }
        } }
        compose.waitForIdle()
        val next = visual("b", 230)
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { frames.clear(); current = next; visit = "second" }
        repeat(40) { compose.mainClock.advanceTimeByFrame() }
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        val transitions = frames.filter { it.transitioning }
        assertTrue("A skip animates", transitions.size > 3)
        var previousIncoming = -1f
        for (frame in transitions) {
            assertEquals("Weights always sum to one", 1f, frame.roles.sumOf { it.weight.toDouble() }.toFloat(), .002f)
            val incoming = frame.roles.filter { it.visual.contentKey == "b" }.sumOf { it.weight.toDouble() }.toFloat()
            assertTrue("The incoming weight only grows ($previousIncoming then $incoming)", incoming >= previousIncoming - .001f)
            previousIncoming = incoming
        }
        assertTrue("The first transition frame still shows the outgoing cover", transitions.first().roles
            .filter { it.visual.contentKey == "a" }.sumOf { it.weight.toDouble() } > .9)
    }

    @Test fun aNewlyMountedConsumerNeverShowsTheRawWashWhileItsToneIsPrepared() {
        val bitmap = wash(200)
        val raw = bitmap.asImageBitmap()
        var mounted by mutableIntStateOf(0)
        var requested by mutableStateOf(tone)
        val published = java.util.concurrent.CopyOnWriteArrayList<Pair<Int, ImageBitmap?>>()
        compose.setContent { key(mounted) {
            val image = remember(bitmap) { bitmap.asImageBitmap() } // A new wrapper for the same pixels.
            val value = rememberToneSnapshot(image, requested)
            SideEffect { published += mounted to value }
        } }
        compose.waitUntil(10_000) { published.any { it.second?.asAndroidBitmap() !== bitmap } }
        val toned = compose.runOnIdle { published.last().second }
        // The same tone mounted anew: found immediately, by pixel identity rather than wrapper identity.
        compose.runOnIdle { mounted = 1 }
        compose.waitForIdle()
        compose.runOnIdle {
            val first = published.first { it.first == 1 }.second
            assertSame("Prepared pixels are reused", toned?.asAndroidBitmap(), first?.asAndroidBitmap())
        }
        // Another tone has none yet: a prepared variant of the same pixels beats the brighter raw image.
        compose.runOnIdle { requested = GlassTone(.25f, 2f, false); mounted = 2 }
        compose.waitForIdle()
        compose.runOnIdle {
            val first = published.first { it.first == 2 }.second
            assertNotSame("Never the raw wash", bitmap, first?.asAndroidBitmap())
            assertNotSame(raw, first)
        }
    }
}
