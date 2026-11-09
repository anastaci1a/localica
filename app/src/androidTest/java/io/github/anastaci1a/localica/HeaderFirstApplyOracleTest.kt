package io.github.anastaci1a.localica

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.navigation.GlassAppearance
import io.github.anastaci1a.localica.navigation.GlassRendererMode
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Native negative control: candidate publication is held until AFTER its first draw receipt.
 * Uses real FrostedBackdrop.publish and LiquidGlassSurface, with no production fault hook.
 * This qualifies the oracle, not the complete player or scheduling of its async producer. */
class HeaderFirstApplyOracleTest {
    @get:Rule val compose = localicaComposeRule()

    @Test fun candidateDelayedPublicationIsRejectedEvenWhenLaterPixelsEqual() {
        var candidate by mutableStateOf(false)
        var releaseCandidate by mutableStateOf(false)
        var epoch by mutableIntStateOf(0)
        val observations = mutableListOf<GlassSurfaceDrawObservation>()
        val observer: (GlassSurfaceDrawObservation) -> Unit = { observations += it }
        val image = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply {
            eraseColor(android.graphics.Color.rgb(72, 124, 183))
        }.asImageBitmap()
        compose.mainClock.autoAdvance = false
        compose.setContent {
            LocalicaTheme {
                CompositionLocalProvider(LocalGlassAppearance provides GlassAppearance(renderer = GlassRendererMode.None, contrast = 0f),
                    LocalGlassSurfaceDrawObserver provides observer) {
                    key(candidate, epoch) {
                        val layer = rememberGraphicsLayer()
                        val source = remember(layer) { FrostedBackdrop(layer, dark = false) }
                        val publish = !candidate || releaseCandidate
                        SideEffect {
                            source.readbackRequest = 1
                            if (publish) source.publish(source.rendererGeneration, image, image, 1, null,
                                androidx.compose.ui.unit.IntSize(32, 32))
                        }
                        Box(Modifier.size(160.dp, 100.dp).background(Color(0xFFE1E2E3)).testTag("header-probe-root")) {
                            Box(Modifier.fillMaxSize().recordFrostedBackdrop(source).background(Color(0xFF487CB7)))
                            CompositionLocalProvider(LocalFrostedBackdrop provides source) {
                                Row {
                                    for (role in listOf("collapse", "lyrics")) {
                                        Box(Modifier.size(40.dp).testTag("header-probe-$role")) {
                                            LiquidGlassSurface(Modifier.fillMaxSize(), shadow = false)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        fun receipt(): HeaderFirstApplyReceipt {
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            val owners = listOf("collapse", "lyrics").associateWith {
                compose.onNodeWithTag("header-probe-$it", true).fetchSemanticsNode().boundsInRoot
            }
            return compose.runOnIdle { freezeHeaderFirstApply(observations.toList(), owners) }
        }
        fun pixels(): IntArray {
            val p = compose.onNodeWithTag("header-probe-root").captureToImage().toPixelMap()
            return IntArray(p.width * p.height) { p[it % p.width, it / p.width].toArgb() }
        }
        try {
            val reference = receipt()
            assertTrue("Reference publication must be ready", reference.roles.values.all { it.snapshotPresent && it.published == 1 })
            val expected = pixels()
            compose.runOnIdle { observations.clear(); candidate = true; epoch++ }
            val delayed = receipt()
            assertTrue("Candidate must really withhold publication", delayed.roles.values.all { !it.snapshotPresent && it.published == -1 })
            var rejected = false
            try { assertHeaderFirstApplyEqual(reference, delayed) }
            catch (_: HeaderFirstApplyMismatch) { rejected = true }
            assertTrue("Oracle must reject before candidate publication is released", rejected)
            // Intentionally later pixels are diagnostic for this negative control only.
            compose.runOnIdle { releaseCandidate = true }
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            assertArrayEquals("Equal later pixels cannot repair the rejected first receipt", expected, pixels())
            compose.runOnIdle { observations.clear(); epoch++ }
            val ordinary = receipt()
            assertHeaderFirstApplyEqual(reference, ordinary)
            assertArrayEquals("Positive control", expected, pixels())
        } finally { compose.mainClock.autoAdvance = true }
    }
}
