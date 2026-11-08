package io.github.anastaci1a.localica

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.ui.LocalicaTheme
import io.github.anastaci1a.localica.ui.layout.LastFmMatchSegment
import io.github.anastaci1a.localica.ui.layout.LastFmMatchingBar
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class LastFmMatchingBarTest {
    @get:Rule val compose = localicaComposeRule()
    @Test fun exactRatioZeroEmptyDominantManyAndRtlStripsHaveNoInputOwner() {
        var counts by mutableStateOf(listOf(99, 1, 0))
        var rtl by mutableStateOf(false)
        val green = Color(0xFF00AA44); val yellow = Color(0xFFFFCC00)
        compose.setContent { LocalicaTheme {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
                LocalDensity provides Density(density.density, 2f)) {
                Box(Modifier.width(320.dp).background(Color.White)) {
                    LastFmMatchingBar(counts.mapIndexed { i, count -> LastFmMatchSegment("$i", "Group $i", count, if (i % 2 == 0) green else yellow) })
                }
            }
        } }
        val strip = compose.onNodeWithTag("lastfm-matching-strip")
        strip.assertHasNoClickAction()
        fun pixel(fraction: Float): Color {
            val pixels = strip.captureToImage().toPixelMap()
            return pixels[(pixels.width * fraction).toInt().coerceAtMost(pixels.width - 1), pixels.height / 2]
        }
        assertEquals(green, pixel(.98f)); assertEquals(yellow, pixel(.995f))
        compose.runOnIdle { rtl = true }
        assertEquals(yellow, pixel(.005f)); assertEquals(green, pixel(.02f))
        compose.runOnIdle { counts = List(20) { 1 }; rtl = false }
        repeat(20) { assertEquals(if (it % 2 == 0) green else yellow, pixel((it + .5f) / 20)) }
        compose.runOnIdle { counts = listOf(0, 0) }
        assertEquals(Color.White, pixel(.5f))
        compose.runOnIdle { counts = emptyList() }
        assertEquals(Color.White, pixel(.5f))
    }
}
