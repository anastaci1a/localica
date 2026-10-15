package io.github.anastaci1a.localica

import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.navigation.GlassAppearance
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import kotlin.math.abs

/** Diagnostic for independently fading siblings in the actual bottom-shadow host. */
class MixedShadowOpacityTest {
    @get:Rule val compose = createComposeRule()

    @Test fun stationarySiblingShadowFadesWithoutBorrowingOpaqueSiblingAlpha() {
        var opacity by mutableFloatStateOf(1f)
        var stage by mutableIntStateOf(1)
        lateinit var view: View
        compose.setContent { LocalicaTheme {
            view = LocalView.current
            val group = remember { GlassShadowGroup() }
            CompositionLocalProvider(LocalBottomShadows provides group,
                LocalGlassAppearance provides GlassAppearance(shadowStrength = 1f, shadowSpread = .6f)) {
                Box(Modifier.size(240.dp, 200.dp).background(Color.White)
                    .bottomGlassShadowHost(group).testTag("mixed-shadow")) {
                    listOf(30, 150).forEachIndexed { index, x ->
                        Box(Modifier.offset(x.dp, 60.dp).size(60.dp, 50.dp)
                            .bottomGlassShadow(14.dp, opacity = { if (index == 0) opacity else 1f })
                            .graphicsLayer { alpha = if (index == 0) opacity else 1f }
                            .background(Color.Gray))
                    }
                    PresentationMarker { stage }
                }
            }
        } }
        fun darkness(pixels: PixelMap, center: Int): Float {
            val density = pixels.width / 240f
            var sum = 0f
            var count = 0
            for (y in (115 * density).toInt()..(119 * density).toInt())
                for (x in ((center - 4) * density).toInt()..((center + 4) * density).toInt()) {
                    sum += 1f - pixels[x, y].red
                    count++
                }
            return sum / count
        }
        val samples = listOf(1f, .5f, .05f, 0f).mapIndexed { index, alpha ->
            compose.runOnIdle { opacity = alpha; stage = index + 1 }
            val pixels = capturePresentedPopupPixels(compose, compose.onNodeWithTag("mixed-shadow"), index + 1) { view }
            Triple(alpha, darkness(pixels, 60), darkness(pixels, 180)).also {
                android.util.Log.i("MixedShadowOpacity", "alpha=${it.first} fading=${it.second} opaque=${it.third}")
            }
        }
        val baseline = samples.first().second
        assertTrue("Baseline shadow must be measurable: $samples", baseline > .02f)
        assertTrue("Independent opacity must progressively fade its own shadow: $samples",
            samples[1].second < baseline * .75f && samples[1].second > baseline * .25f &&
                samples[2].second < baseline * .15f && samples[3].second < .01f)
        assertTrue("The stationary opaque sibling must retain its shadow: $samples",
            samples.all { abs(it.third - samples.first().third) < .015f })
    }
}
