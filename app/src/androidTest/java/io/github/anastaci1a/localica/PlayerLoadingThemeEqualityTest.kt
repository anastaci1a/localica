package io.github.anastaci1a.localica

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.library.ArtworkColors
import io.github.anastaci1a.localica.ui.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Frozen cover body under the formerly applied Material theme, versus the retained draw owner.
 * Both arms share a fixed shimmer clock; no settling frames precede each comparison. */
class PlayerLoadingThemeEqualityTest {
    @get:Rule val compose = localicaComposeRule()
    private val uri = "content://test.perf2/theme-loading"
    @After fun clearColors() { ArtworkColors.publish(emptyMap()) }

    @Test fun firstColdFrameHasOriginalBaseAndRejectsR3() = compare(false)
    @Test fun firstTintedFrameAndRetainedReversalsMatchOriginal() = compare(true)

    private fun compare(known: Boolean) {
        ArtworkColors.publish(if (known) mapOf(uri to 0xFF2A6FB0.toInt()) else emptyMap())
        var mix by mutableFloatStateOf(1f)
        var override by mutableStateOf<Color?>(null)
        var ready by mutableStateOf(false)
        var epoch by mutableIntStateOf(0)
        val shimmer = mutableFloatStateOf(-1f)
        val image = ImageBitmap(32, 32).also {
            Canvas(it).drawRect(0f, 0f, 32f, 32f, Paint().apply { color = Color(0xFFA963B1) })
        }
        compose.mainClock.autoAdvance = false
        compose.setContent { LocalicaTheme {
            GlassPanelTheme(mix) {
                val base = MaterialTheme.colorScheme
                val applied = base.copy(
                    surface = lerp(Color(0xFFFAF8F3), NeutralPalette.bottom, mix),
                    surfaceContainer = lerp(Color(0xFFEEECE7), NeutralPalette.middle, mix),
                    onSurface = lerp(Color.Black, Color.White, mix))
                val theme = remember { PlayerDrawTheme(applied) }
                theme.present(applied)
                CompositionLocalProvider(LocalLoadingMaterialColor provides override,
                    LocalLoadingShimmer provides shimmer, LocalReducedMotion provides false) {
                    Column {
                        listOf("reference", "candidate", "broken-r3").forEach { arm ->
                            key(epoch, arm) {
                                Box(Modifier.size(96.dp).background(Color(0xFF8293A4)).testTag(arm)) {
                                    val visual = PlayerVisual(artwork = image.takeIf { ready }, loading = !ready, loadingUri = uri)
                                    when (arm) {
                                        "reference" -> MaterialTheme(colorScheme = applied) { FrozenCover(visual) }
                                        "candidate" -> CompositionLocalProvider(LocalPlayerDrawTheme provides theme) {
                                            PlayerArtworkFrame(visual, Modifier.fillMaxSize(), RoundedCornerShape(7.dp), ContentScale.Crop)
                                        }
                                        // Deliberately unthemed old body reproduces the R3 failure.
                                        else -> FrozenCover(visual)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } }
        fun pixels(tag: String): IntArray {
            val p = compose.onNodeWithTag(tag).captureToImage().toPixelMap()
            return IntArray(p.width * p.height) { i -> p[i % p.width, i / p.width].toArgb() }
        }
        fun sample(label: String) {
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            assertArrayEquals(label, pixels("reference"), pixels("candidate"))
        }
        fun change(label: String, body: () -> Unit) { compose.runOnIdle(body); sample(label) }
        try {
            sample("first cold admission")
            if (!known) {
                val original = pixels("reference")
                assertTrue("R3 negative control must differ", !original.contentEquals(pixels("broken-r3")))
                assertEquals("Reviewed cold palette base", 0xFF343C47.toInt(), original[original.size / 2])
            }
            change("partial expansion first frame") { mix = .42f; shimmer.floatValue = .15f }
            change("known material override first frame") { override = Color(0xFF98512B); mix = .8f; shimmer.floatValue = .55f }
            change("cold to ready first frame") { ready = true }
            change("retained ready theme reversal") { mix = .2f }
            change("ready to cold first frame") { ready = false }
            change("null override restored first frame") { override = null; mix = 1f; shimmer.floatValue = .4f }
            change("incoming cover new owner") { epoch++; mix = .6f }
            change("reverse incoming cover") { mix = .25f; shimmer.floatValue = .75f }
            change("final ready frame") { ready = true }
        } finally { compose.mainClock.autoAdvance = true }
    }

    /** Frozen 4c3d1f03 cover body, with the irrelevant missing-art branch omitted.
     * ArtworkPlaceholder remains the unchanged production primitive in both arms. */
    @Composable private fun FrozenCover(visual: PlayerVisual) {
        Box(Modifier.fillMaxSize().clip(RoundedCornerShape(7.dp))) {
            androidx.compose.foundation.Canvas(Modifier.matchParentSize()) { drawRect(visual.palette.middle) }
            if (visual.artwork != null) Image(visual.artwork, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else if (visual.loading) ArtworkPlaceholder(true, Modifier.matchParentSize(), showIcon = false,
                tintUris = listOfNotNull(visual.loadingUri))
        }
    }
}
