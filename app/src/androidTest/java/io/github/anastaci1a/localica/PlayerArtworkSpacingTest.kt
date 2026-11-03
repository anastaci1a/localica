package io.github.anastaci1a.localica

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import io.github.anastaci1a.localica.localicaComposeRule as createComposeRule
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.library.Track
import io.github.anastaci1a.localica.navigation.*
import io.github.anastaci1a.localica.playback.PlaybackState
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** The artwork sits evenly between the top controls and the title in both layouts (#508). */
class PlayerArtworkSpacingTest {
    @get:Rule val compose = createComposeRule()
    private val track = Track("file:///missing.mp3", "Morning light", artist = "Localica", fileName = "morning.mp3", relativePath = "morning.mp3")
    private val playback = PlaybackState(connected = true, trackId = track.id, trackUri = track.uri, title = track.title, artist = track.artist, durationMs = 125000)

    @Test fun bandCentersTheArtworkAndAllowsNegativeSpacingOnlyEvenly() {
        val band = playerArtworkBand(controlsBottom = 100f, titleTop = 400f, padding = 8f)
        assertEquals(108f, band.top, 0f); assertEquals(284f, band.height, 0f)
        for (height in listOf(50f, 284f, 360f, 700f)) {
            assertEquals(band.topGap(height), band.bottomGap(height), 1e-3f)
        }
        assertTrue(band.topGap(700f) < 0f)
        assertEquals(284f, band.fittingHeight(), 0f)
        assertTrue(band.topGap(band.fittingHeight()) >= 0f)
    }

    private fun measure(layout: ArtworkLayout, width: Int, height: Int): Triple<Float, Float, String> {
        val settings = InterfaceSettings(artworkLayout = layout)
        var density = 1f
        var statusInset = 0f
        compose.setContent { LocalicaTheme { Box(Modifier.requiredSize(width.dp, height.dp)) {
            density = LocalDensity.current.density
            statusInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding().value
            BoxWithConstraints(Modifier.fillMaxSize()) {
                PlayerSurface(playback, true, {}, settings, remember { Animatable(1f) }, {}, {}, {}, {}, {}, {}, track = track)
            }
        } } }
        compose.waitForIdle()
        fun px(tag: String) = compose.onNodeWithTag(tag, useUnmergedTree = true).getUnclippedBoundsInRoot()
            .let { androidx.compose.ui.geometry.Rect(it.left.value * density, it.top.value * density, it.right.value * density, it.bottom.value * density) }
        val player = px("player-surface"); val art = px("player-artwork"); val title = px("player-title")
        val controls = player.top + (statusInset + PlayerTopControlsHeight.value) * density
        val padding = PlayerArtworkEdgePadding * density
        val top = art.top - (controls + padding)
        val bottom = (title.top - padding) - art.bottom
        return Triple(top, bottom, "density=$density status=$statusInset player=$player art=$art title=$title controls=$controls")
    }

    private fun check(layout: ArtworkLayout, width: Int, height: Int) {
        val (top, bottom, detail) = measure(layout, width, height)
        // Layout rounds the artwork's and the title's offsets to whole pixels.
        assertEquals("$layout ${width}x$height top $top bottom $bottom $detail", top, bottom, 2f)
        if (layout == ArtworkLayout.Floating) {
            assertTrue("The sharp cover never overlaps the controls: $top $detail", top >= -1f)
            assertTrue("The sharp cover never overlaps the title: $bottom $detail", bottom >= -1f)
        }
    }

    @Test fun floatingLayoutIsEvenOnAShortViewport() = check(ArtworkLayout.Floating, 360, 560)
    @Test fun floatingLayoutIsEvenOnATallViewport() = check(ArtworkLayout.Floating, 360, 900)
    @Test fun fullBackgroundLayoutIsEvenOnAShortViewport() = check(ArtworkLayout.FullBackground, 360, 560)
    @Test fun fullBackgroundLayoutIsEvenOnATallViewport() = check(ArtworkLayout.FullBackground, 360, 900)
}
