package io.github.anastaci1a.localica.ui

import org.junit.Assert.*
import org.junit.Test

/** The band the expanded artwork is centered in, and the scale that fits the sharp cover to it (#508). */
class PlayerArtworkBandTest {
    @Test fun tallViewportBandFitsAFullSizeCoverWithEqualGaps() {
        val band = playerArtworkBand(controlsBottom = 72f, titleTop = 520f)
        assertEquals(80f, band.top, 0f)
        assertEquals(432f, band.height, 0f)
        val cover = 364f
        assertTrue(cover <= band.fittingHeight())
        assertEquals(band.topGap(cover), band.bottomGap(cover), 1e-3f)
        assertTrue(band.topGap(cover) >= 0f)
        assertEquals(80f + 34f, band.artworkTop(cover), 1e-3f)
    }

    @Test fun compactBandScalesTheCoverDownToKeepBothGaps() {
        // A 600 dp viewport: the band is shorter than the natural 200 dp cover.
        val band = playerArtworkBand(controlsBottom = 72f, titleTop = 240f)
        assertEquals(152f, band.height, 0f)
        val natural = 200f
        val fit = minOf(1f, band.fittingHeight() / natural)
        assertTrue(fit < 1f)
        val scaled = natural * fit
        assertEquals(band.fittingHeight(), scaled, 1e-3f)
        assertEquals(0f, band.topGap(scaled), 1e-3f)
        assertEquals(0f, band.bottomGap(scaled), 1e-3f)
        // Measured from the controls and title themselves, both gaps are the edge padding.
        assertEquals(PlayerArtworkEdgePadding, band.artworkTop(scaled) - 72f, 1e-3f)
        assertEquals(PlayerArtworkEdgePadding, 240f - (band.artworkTop(scaled) + scaled), 1e-3f)
    }

    @Test fun smallerCoverStaysCenteredAndNeverOverlaps() {
        val band = playerArtworkBand(controlsBottom = 72f, titleTop = 240f)
        for (height in listOf(0f, 40f, 100f, band.fittingHeight())) {
            assertEquals(band.topGap(height), band.bottomGap(height), 1e-3f)
            assertTrue(band.topGap(height) >= -1e-3f)
        }
    }

    @Test fun degenerateBandsHaveNoFittingHeight() {
        val inverted = playerArtworkBand(controlsBottom = 300f, titleTop = 200f)
        assertTrue(inverted.height < 0f)
        assertEquals(0f, inverted.fittingHeight(), 0f)
        val touching = playerArtworkBand(controlsBottom = 100f, titleTop = 116f)
        assertEquals(0f, touching.height, 0f)
        assertEquals(0f, touching.fittingHeight(), 0f)
        // A zero fitting height scales the cover to nothing rather than a negative size.
        assertEquals(0f, minOf(1f, inverted.fittingHeight() / 200f), 0f)
    }

    @Test fun customPaddingMovesBothEdges() {
        val band = playerArtworkBand(controlsBottom = 10f, titleTop = 110f, padding = 20f)
        assertEquals(30f, band.top, 0f)
        assertEquals(60f, band.height, 0f)
        assertEquals(90f, band.bottom, 0f)
    }
}
