package io.github.anastaci1a.localica.ui

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.ContentScale
import org.junit.Assert.*
import org.junit.Test

class PlayerExpansionMotionTest {
    @Test fun selectedLyricsReachTheirTargetsBeforeReleaseAndFadeEarlyOnCollapse() {
        val held = playerLyricsMotion(1f, 1f)
        assertEquals(1f, held.layout, 0f)
        assertEquals(1f, held.contentAlpha, 0f)
        assertEquals(0f, held.artworkAlpha, 0f)
        assertEquals(0f, playerLyricsMotion(.78f, 1f).contentAlpha, 0f)
        assertTrue(playerLyricsMotion(.9f, 1f).contentAlpha < .65f)
        for (step in 0..100) {
            val p = step / 100f
            val lyrics = playerLyricsMotion(p, 1f)
            assertEquals(p, lyrics.layout, 0f)
            assertEquals(1f - p, lyrics.artworkAlpha, 0f)
            assertEquals("No low-opacity album actions at $p", 0f, rows(p, 1f).actionsAlpha, 0f)
            val artwork = playerLyricsMotion(p, 0f)
            assertEquals(0f, artwork.layout, 0f)
            assertEquals(0f, artwork.contentAlpha, 0f)
            assertEquals(1f, artwork.artworkAlpha, 0f)
        }
    }

    @Test fun seekAndActionsFollowDistinctRatesBetweenUnchangedTitleAndTransport() {
        // Actual window-space displacement includes the same moving surface top.
        // Title moves slowest; transport initially flies down fastest from full size.
        val miniTop = 560f
        for (step in 84..99) {
            val p = step / 100f
            val titleOffset = (7f - 300f) * (1f - p)
            val transportOffset = (1.6f - 484f) * (1f - p * p * (3f - 2f * p))
            val surfaceTop = miniTop * (1f - p)
            val motion = playerRowMotion(p, titleOffset, transportOffset, 0f)
            val title = surfaceTop + titleOffset
            val seek = surfaceTop + motion.seekOffset
            val actions = surfaceTop + motion.actionsOffset
            val transport = surfaceTop + transportOffset
            assertTrue("Title < seek at $p", title < seek)
            assertTrue("Seek < actions at $p", seek < actions)
            assertTrue("Actions < transport at $p", actions < transport)
            assertTrue("Actions fade before seek at $p", motion.actionsAlpha < motion.seekAlpha)
        }
        val full = rows(1f, 0f)
        assertEquals(0f, full.seekOffset, 0f)
        assertEquals(0f, full.actionsOffset, 0f)
        assertEquals(1f, full.seekAlpha, 0f)
        assertEquals(1f, full.actionsAlpha, 0f)
    }

    @Test fun reversalCancellationAndModeChangesDoNotIntroduceASecondClock() {
        val route = listOf(0f, .4f, .79f, .85f, .97f, 1f, .97f, .85f, .79f, .4f, .85f, 1f, .4f, 0f)
        for (mode in listOf(0f, .25f, .65f, 1f)) {
            val snapshots = (0..100).associate { it / 100f to (playerLyricsMotion(it / 100f, mode) to rows(it / 100f, mode)) }
            for (p in route) assertEquals(snapshots.getValue(p), playerLyricsMotion(p, mode) to rows(p, mode))
            var lastLyrics = 0f
            var lastSeek = 0f
            var lastActions = 0f
            for (step in 0..1000) {
                val p = step / 1000f
                val lyric = playerLyricsMotion(p, mode)
                val row = rows(p, mode)
                assertTrue(lyric.contentAlpha >= lastLyrics)
                assertTrue(row.seekAlpha >= lastSeek)
                assertTrue(row.actionsAlpha >= lastActions)
                assertTrue(lyric.contentAlpha - lastLyrics < .01f)
                assertTrue(row.seekAlpha - lastSeek < .01f)
                assertTrue(row.actionsAlpha - lastActions < .01f)
                lastLyrics = lyric.contentAlpha; lastSeek = row.seekAlpha; lastActions = row.actionsAlpha
            }
        }
    }

    @Test fun rowsRemainBetweenUnchangedReferencesForSelectedLyricsAndReversal() {
        for (mode in listOf(0f, .5f, 1f)) {
            val samples = mutableMapOf<Float, PlayerRowMotion>()
            for (p in listOf(1f, .97f, .91f, .85f, .5f, .2f, 0f, .2f, .5f, .85f, .91f, .97f, 1f)) {
                // Delivered references: the lyrics target itself retains its expansion factor.
                val metadata = 300f + 88f * mode * p
                val title = 7f + (metadata - 7f) * p
                val transportTarget = 484f + 72f * mode * p
                val transport = 1.6f + (transportTarget - 1.6f) * p * p * (3f - 2f * p)
                val titleOffset = title - metadata
                val transportOffset = transport - transportTarget
                val rows = playerRowMotion(p, titleOffset, transportOffset, mode)
                assertTrue(rows.seekOffset >= minOf(titleOffset, transportOffset) - .0001f)
                assertTrue(rows.seekOffset <= maxOf(titleOffset, transportOffset) + .0001f)
                assertTrue(rows.actionsOffset >= minOf(titleOffset, transportOffset) - .0001f)
                assertTrue(rows.actionsOffset <= maxOf(titleOffset, transportOffset) + .0001f)
                assertEquals("Distinct reference mixtures retain their relative separation",
                    (transportOffset - titleOffset) / 3f, rows.actionsOffset - rows.seekOffset, .0001f)
                samples[p]?.let { assertEquals("Reversal retraces the unchanged references", it, rows) }
                samples[p] = rows
            }
        }
    }

    @Test fun rowOpacityIsLinearThroughoutItsVisibleWindow() {
        for (step in 0..20) {
            val fraction = step / 20f
            assertEquals(fraction, rows(.8f + .2f * fraction, 0f).seekAlpha, .000001f)
            assertEquals(fraction, rows(.83f + .17f * fraction, 0f).actionsAlpha, .000001f)
        }
    }

    @Test fun lyricsUsePresentedTitleBoundaryAndSlowerContentWithReversibleGeometry() {
        for (metadata in listOf(184f, 300f, 520f)) {
            val snapshots = mutableMapOf<Float, PlayerLyricsViewport>()
            for (p in listOf(1f, .96f, .92f, .86f, .8f, .86f, .92f, .96f, 1f)) {
                val surface = 560f * (1f - p)
                val title = 7f + (metadata - 7f) * p
                val viewport = playerLyricsViewport(surface, title, metadata, 84f)
                val top = surface + viewport.top
                val content = top + viewport.contentOffset
                assertEquals(maxOf(84f, surface), top, .00001f)
                assertEquals(surface + title - 4f, top + viewport.height, .00005f)
                assertEquals("Content moves at 35% of title displacement", (surface + title - metadata) * .35f,
                    content - 84f, .00001f)
                snapshots[p]?.let { assertEquals(it, viewport) }
                snapshots[p] = viewport
            }
        }
    }

    @Test fun artworkKeepsMiniSizeUntilClearanceAndReversesExactlyWithConstrainedFallback() {
        var compatible = 0
        var constrained = 0
        for (width in listOf(320f, 360f, 412f, 640f)) for (height in listOf(360f, 600f, 800f, 960f)) {
            val virtualHeight = maxOf(height, 608f)
            val floatingArt = minOf(width - 48f, maxOf(virtualHeight - 488f, 96f))
            val ordinary = maxOf(minOf((24f + 52f + floatingArt + 8f + virtualHeight - 304f) / 2f,
                virtualHeight - 392f), 184f)
            val lyricMetadata = maxOf(ordinary, virtualHeight - 304f)
            for (aspect in listOf(1f / 3f, 1f, 3f)) for (background in listOf(false, true)) for (mode in listOf(0f, .5f, 1f)) {
                val fullHeight = if (background) width / aspect else minOf(floatingArt, floatingArt / aspect)
                val fullTop = 76f + (floatingArt - fullHeight) / 2f - 24f * mode
                val titleQuadratic = (lyricMetadata - ordinary) * mode
                val titleTarget = ordinary + titleQuadratic
                val fits = fullTop + fullHeight <= titleTarget
                if (fits) compatible++ else constrained++
                val snapshots = mutableMapOf<Float, Float>()
                var prior = 0f
                for (step in 0..1000) {
                    val p = step / 1000f
                    val size = playerArtworkSizeProgress(p, fullTop, fullHeight, titleTarget, 9.6f, 32f, titleQuadratic)
                    assertTrue("Size never reverses during forward movement", size >= prior)
                    assertTrue("Size remains bounded", size in 0f..1f)
                    val top = 9.6f + (fullTop - 9.6f) * p - 48f * p * (1f - p)
                    val title = 7f + (ordinary + titleQuadratic * p - 7f) * p
                    if (fits) {
                        if (top + 32f > title) assertEquals("Retain mini size until clear", 0f, size, 0f)
                        if (size > 0f) assertTrue("Growing cover stays clear: $width x $height aspect=$aspect background=$background p=$p",
                            top + 32f + (fullHeight - 32f) * size <= title + .0001f)
                    } else assertEquals("Constrained geometry preserves existing animation", p * (.58f + .42f * p), size, 0f)
                    snapshots[p] = size
                    prior = size
                }
                for (step in 1000 downTo 0) {
                    val p = step / 1000f
                    assertEquals("Collapse exactly reverses expansion", snapshots.getValue(p),
                        playerArtworkSizeProgress(p, fullTop, fullHeight, titleTarget, 9.6f, 32f, titleQuadratic))
                }
                assertEquals(0f, snapshots.getValue(0f), 0f)
                assertEquals(1f, snapshots.getValue(1f), 0f)
            }
        }
        assertTrue(compatible > 0)
        assertTrue(constrained > 0)
    }

    @Test fun compatibleNonSquarePixelsRetainMiniScaleAcrossTheSizeGateWithoutCropFitJump() {
        for (source in listOf(Size(96f, 32f), Size(32f, 96f))) {
            val crop = ContentScale.Crop.computeScaleFactor(source, Size(32f, 32f))
            var previous = crop.scaleX
            for (step in 0..100) {
                val fraction = step / 100f
                val frame = if (source.width > source.height) Size(32f + 160f * fraction, 32f + 32f * fraction)
                    else Size(32f + 32f * fraction, 32f + 160f * fraction)
                val scale = playerArtworkContentScale(32f, 1f, false).computeScaleFactor(source, frame)
                assertTrue("Visible artwork never shrinks when its frame begins growing", scale.scaleX >= previous)
                assertTrue("No crop-to-fit pop", scale.scaleX - previous < .02f)
                previous = scale.scaleX
                assertEquals("Fallback keeps original Fit", ContentScale.Fit.computeScaleFactor(source, frame),
                    playerArtworkContentScale(32f, 0f, false).computeScaleFactor(source, frame))
            }
            assertEquals(crop, playerArtworkContentScale(32f, 1f, false).computeScaleFactor(source, Size(32f, 32f)))
        }
    }

    private fun rows(p: Float, mode: Float) = playerRowMotion(p,
        (7f - 300f) * (1f - p), (1.6f - 484f) * (1f - p * p * (3f - 2f * p)), mode)
}
