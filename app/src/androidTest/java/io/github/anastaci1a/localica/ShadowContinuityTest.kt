package io.github.anastaci1a.localica

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.navigation.GlassAppearance
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ShadowContinuityTest {
    @get:Rule val compose = localicaComposeRule()

    @Test fun retainedShadowDoesNotPopWhenMotionStartsOrSettles() {
        var moving by mutableStateOf(false)
        lateinit var shadow: RetainedGlassShadow
        compose.setContent {
            val scope = rememberCoroutineScope()
            shadow = remember { RetainedGlassShadow(scope) }
            Box(Modifier.size(240.dp).background(Color.White).testTag("shadow-scene")
                .drawBehind {
                    val width = 100f
                    val body = androidx.compose.ui.geometry.RoundRect(60.dp.toPx(), 60.dp.toPx(),
                        (60 + width).dp.toPx(), 120.dp.toPx(), androidx.compose.ui.geometry.CornerRadius(20.dp.toPx()))
                    // Popup neighbor exclusion selects the live path even at identical geometry.
                    val exclusion = if (moving) GlassShadowExclusion(emptyList(),
                        androidx.compose.ui.geometry.Offset.Zero, size, 1f, 1f, 0f, 1f) else null
                    with(shadow) { draw(listOf(body to 1f), 1f, 1f, exclusion = exclusion) }
                })
        }
        compose.waitUntil(5000) { shadow.rasterizations > 0 }
        compose.mainClock.autoAdvance = false
        val d = compose.density.density
        val probe = ShadowFrameProbe(Rect(90*d, 122*d, 130*d, 130*d))
        val scene = compose.onNodeWithTag("shadow-scene")
        probe.sample(scene.captureToImage())
        repeat(4) { index ->
            compose.runOnIdle { moving = index % 2 == 0 }
            compose.mainClock.advanceTimeByFrame()
            probe.sample(scene.captureToImage())
        }
        probe.assertContinuous(.008f, "retained/direct handoff without perceptible geometry motion")
    }

    @Test fun buttonAvailabilityDoesNotCutItsShadowAtEitherEndpoint() {
        var enabled by mutableStateOf(true)
        compose.setContent { LocalicaTheme {
            CompositionLocalProvider(LocalGlassAppearance provides GlassAppearance(shadowStrength = 1f, shadowSpread = 1f)) {
                Box(Modifier.size(240.dp).background(Color.White).testTag("shadow-scene"),
                    contentAlignment = androidx.compose.ui.Alignment.Center) {
                    GlassActionButton(R.drawable.ic_more, "Actions", Modifier.width(160.dp).testTag("shadow-button"), enabled) {}
                }
            }
        } }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        val scene = compose.onNodeWithTag("shadow-scene")
        val origin = scene.fetchSemanticsNode().boundsInRoot.topLeft
        val body = compose.onNodeWithTag("shadow-button").fetchSemanticsNode().boundsInRoot.translate(-origin)
        val d = compose.density.density
        val probe = ShadowFrameProbe(Rect(body.center.x-12*d, body.bottom+4*d, body.center.x+12*d, body.bottom+8*d))
        probe.sample(scene.captureToImage())
        for (available in listOf(false, true)) {
            compose.runOnIdle { enabled = available }
            repeat(32) {
                compose.mainClock.advanceTimeByFrame()
                val image = scene.captureToImage()
                probe.sample(image)
                ShadowFrameProbe.assertSpreadEdge(image,
                    Rect(body.center.x-8*d, body.bottom+30*d, body.center.x+8*d, body.bottom+32*d),
                    Rect(body.center.x-8*d, body.bottom+32*d, body.center.x+8*d, body.bottom+34*d), .015f, "button")
            }
        }
        probe.assertContinuous(.025f, "button availability")
        assertTrue("A disabled button retains a faded shadow", probe.luminances.min() < .97f)
    }

    @Test fun seekCancelRetainsItsFullShadowAtRest() {
        val state = SeekCancelTargetState()
        compose.setContent { LocalicaTheme {
            CompositionLocalProvider(LocalGlassAppearance provides GlassAppearance(shadowStrength = 1f, shadowSpread = 1f)) {
                Box(Modifier.size(240.dp).background(Color.White).testTag("shadow-scene")) {
                    SeekCancelTarget(state, false, 48.dp, true)
                }
            }
        } }
        compose.waitForIdle()
        val scene = compose.onNodeWithTag("shadow-scene")
        val root = scene.fetchSemanticsNode().boundsInRoot
        val body = compose.onNodeWithTag("cancel-seek").fetchSemanticsNode().boundsInRoot.translate(-root.topLeft)
        val d = compose.density.density
        val probe = ShadowFrameProbe(Rect(body.center.x - 8*d, body.bottom + 2*d, body.center.x + 8*d, body.bottom + 6*d))
        val image = scene.captureToImage()
        val luminance = probe.sample(image)
        assertTrue("The seek X must cast onto content outside its placement layer: $luminance", luminance < .97f)
        ShadowFrameProbe.assertSpreadEdge(image,
            Rect(body.center.x - 8*d, body.bottom + 30*d, body.center.x + 8*d, body.bottom + 32*d),
            Rect(body.center.x - 8*d, body.bottom + 32*d, body.center.x + 8*d, body.bottom + 34*d), .015f, "seek X")
    }
}
