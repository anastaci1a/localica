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

    @Test fun artworkGrowthStartsImmediatelyAndHasBoundedContinuousVelocityInBothDirections() {
        for (curvature in listOf(0f, .12f, .4f, 1f, 2f)) {
            assertEquals(0f, playerArtworkSizeProgress(-1f, curvature), 0f)
            assertEquals(0f, playerArtworkSizeProgress(0f, curvature), 0f)
            assertEquals(1f, playerArtworkSizeProgress(1f, curvature), 0f)
            assertEquals(1f, playerArtworkSizeProgress(2f, curvature), 0f)
            for (p in listOf(.0001f, .001f, .01f, .05f, .1f)) {
                val growth = playerArtworkSizeProgress(p, curvature)
                assertTrue("No initial hold at $p", growth > 0f)
                assertTrue("Do not hide growth behind excessive curvature", growth >= p * p / 3f)
                assertTrue("Ease starts slower than linear", growth < p)
            }
            val samples = (0..10000).map { playerArtworkSizeProgress(it / 10000f, curvature) }
            for (step in 1..10000) {
                val p = step / 10000f
                val size = samples[step]
                assertTrue(size.isFinite() && size in 0f..1f)
                assertTrue("Growth never reverses", size > samples[step - 1])
                assertTrue("No late endpoint jump", (size - samples[step - 1]) / .0001f < 4.005f)
                assertEquals("Collapse retraces the same curve", size,
                    playerArtworkSizeProgress(p, curvature), 0f)
            }
            for (p in listOf(.01f, .12f, .25f, .4f, .8f, .99f)) {
                val delta = .0001f
                val before = (playerArtworkSizeProgress(p, curvature) -
                    playerArtworkSizeProgress(p - delta, curvature)) / delta
                val after = (playerArtworkSizeProgress(p + delta, curvature) -
                    playerArtworkSizeProgress(p, curvature)) / delta
                assertEquals("No old-clearance splice or velocity kink at $p", before, after, .007f)
            }
            val early = playerArtworkSizeProgress(.001f, curvature) / .001f
            val earlier = playerArtworkSizeProgress(.0001f, curvature) / .0001f
            assertTrue("Initial slope tends to zero", earlier < early / 9f)
            for (p in listOf(0f, .4f, .85f, 1f, .85f, .4f, .85f, 0f))
                assertEquals(samples[(p * 10000).toInt()], playerArtworkSizeProgress(p, curvature), 0f)
        }
        for (curvature in listOf(Float.NaN, Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY))
            assertTrue(playerArtworkSizeProgress(.5f, curvature).isFinite())
        assertEquals(0f, playerArtworkSizeProgress(Float.NaN, 0f), 0f)
    }

    @Test fun artworkCurveUsesTheTitleRightOnlyWhileItsBottomRightTraversesTheTitleBand() {
        val mini = 32f
        val width = 200f
        fun sample(p: Float) = PlayerArtworkTrajectorySample(p, 16f, 18f, 50f, 60f,
            48f + (width - mini) * playerArtworkSizeProgress(p, .75f))
        val curve = playerArtworkGrowthCurve(mini, width, mini, (0..128).map { sample(it / 128f) })
        assertFalse(curve.constrained)
        assertTrue("Geometry actually adjusts curvature", curve.curvature > .75f)
        for (step in 0..8192) {
            val p = step / 8192f
            val point = sample(p)
            val right = point.artworkLeft + mini + (width - mini) * playerArtworkSizeProgress(p, curve)
            assertTrue("Independent dense right-bound check at $p", right <= point.titleRight + .0001f)
        }
        // It is the bottom-right corner and the named RIGHT bound, not rectangle exclusion.
        for (top in listOf(-500f, 500f)) {
            val free = listOf(0f, .25f, .5f, .75f, 1f).map {
                PlayerArtworkTrajectorySample(it, 16f, top, 50f, 60f, 55f)
            }
            assertEquals(PlayerArtworkGrowthCurve(0f, false), playerArtworkGrowthCurve(mini, width, width, free))
        }
    }

    @Test fun artworkCurveRechecksCornersThatEnterTheBandAfterAnotherSampleSlowsGrowth() {
        val start = PlayerArtworkTrajectorySample(0f, 16f, 0f, -1000f, -980f, 1000f)
        val first = PlayerArtworkTrajectorySample(.5f, 16f, 18f, 70f, 100f, 81.6f)
        val entering = PlayerArtworkTrajectorySample(.5f, 16f, 0f, 62f, 70f, 70f)
        val end = start.copy(expansion = 1f)
        assertTrue(32f + 168f * playerArtworkSizeProgress(.5f, 0f) > entering.titleBottom)
        assertTrue(32f + 168f * playerArtworkSizeProgress(.5f, .5f) in entering.titleTop..entering.titleBottom)
        val curve = playerArtworkGrowthCurve(32f, 200f, 200f, listOf(start, first, entering, end))
        assertFalse(curve.constrained)
        assertTrue("The entering sample needs a second, stronger correction", curve.curvature > .8f && curve.curvature <= 2f)
        assertTrue(32f + 168f * playerArtworkSizeProgress(.5f, curve) < entering.titleTop)
        assertTrue(48f + 168f * playerArtworkSizeProgress(.5f, curve) <= first.titleRight)
    }

    @Test fun artworkConstrainedGeometryFallsBackExplicitlyWithoutChangingEndpointsOrDelayingGrowth() {
        val start = PlayerArtworkTrajectorySample(0f, 16f, 0f, 32f, 40f, 300f)
        val end = start.copy(expansion = 1f)
        val cases = listOf(
            listOf(start, end.copy(titleRight = 100f)), // Fixed endpoint collision.
            listOf(start.copy(artworkLeft = -64f), end), // Fixed lower-reference collision.
            listOf(start, start.copy(expansion = .5f, titleRight = 49f), end), // Excessive curvature.
            listOf(start, end.copy(titleBottom = Float.NaN)),
            listOf(start)) // Missing endpoint geometry.
        for (trajectory in cases) {
            val curve = playerArtworkGrowthCurve(32f, 200f, 32f, trajectory)
            assertTrue(curve.constrained)
            for (step in 0..1000) {
                val p = step / 1000f
                assertEquals("Explicit fallback, not a bound guarantee", p * p,
                    playerArtworkSizeProgress(p, curve), 0f)
            }
            assertTrue(playerArtworkSizeProgress(.001f, curve) > 0f)
            assertEquals(0f, playerArtworkSizeProgress(0f, curve), 0f)
            assertEquals(1f, playerArtworkSizeProgress(1f, curve), 0f)
        }
    }

    @Test fun artworkPresentedTrajectoriesRetainEndpointsAndRespectAdmittedTitleBandsBetweenSamples() {
        var admitted = 0
        var constrained = 0
        for (width in listOf(320f, 360f, 412f, 640f)) for (height in listOf(360f, 600f, 800f, 960f)) {
            val virtualHeight = maxOf(height, 608f)
            val floatingArt = minOf(width - 48f, maxOf(virtualHeight - 488f, 96f))
            val ordinary = maxOf(minOf((24f + 52f + floatingArt + 8f + virtualHeight - 304f) / 2f,
                virtualHeight - 392f), 184f)
            val lyricMetadata = maxOf(ordinary, virtualHeight - 304f)
            for (aspect in listOf(1f / 3f, 1f, 3f)) for (background in listOf(false, true))
                for (mode in listOf(0f, .5f, 1f)) for (titleHeight in listOf(14f, 28f)) {
                val fullWidth = if (background) width else minOf(floatingArt, floatingArt * aspect)
                val fullHeight = if (background) width / aspect else minOf(floatingArt, floatingArt / aspect)
                val fullTop = 76f + (floatingArt - fullHeight) / 2f - 24f * mode
                val titleQuadratic = (lyricMetadata - ordinary) * mode
                fun sample(p: Float): PlayerArtworkTrajectorySample {
                    val title = 7f + (ordinary + titleQuadratic * p - 7f) * p
                    val miniTextWidth = maxOf(width - 252f, 40f)
                    return PlayerArtworkTrajectorySample(p, 16f + ((width - fullWidth) / 2f - 16f) * p * p,
                        9.6f + (fullTop - 9.6f) * p - 48f * p * (1f - p), title, title + titleHeight,
                        58f - 26f * p + miniTextWidth + (width - 64f - miniTextWidth) * p)
                }
                val curve = playerArtworkGrowthCurve(32f, fullWidth, fullHeight, (0..128).map { sample(it / 128f) })
                if (curve.constrained) constrained++ else admitted++
                for (step in 0..4096) {
                    val p = step / 4096f
                    val size = playerArtworkSizeProgress(p, curve)
                    val geometry = sample(p)
                    val right = geometry.artworkLeft + 32f + (fullWidth - 32f) * size
                    val bottom = geometry.artworkTop + 32f + (fullHeight - 32f) * size
                    if (!curve.constrained && bottom in geometry.titleTop..geometry.titleBottom)
                        assertTrue("Bottom-right bound $width x $height aspect=$aspect background=$background mode=$mode p=$p",
                            right >= 16f - .0001f && right <= geometry.titleRight + .0001f)
                }
                assertEquals(0f, playerArtworkSizeProgress(0f, curve), 0f)
                assertEquals(1f, playerArtworkSizeProgress(1f, curve), 0f)
                val cropReference = playerArtworkClearance(fullTop, fullHeight, ordinary + titleQuadratic,
                    9.6f, 32f, titleQuadratic)
                assertTrue("The vertical crop reference must never hold growth", playerArtworkSizeProgress(.001f,
                    fullTop, fullHeight, ordinary + titleQuadratic, 9.6f, 32f, titleQuadratic) > 0f)
                assertEquals(playerArtworkSizeProgress(.5f, cropReference), playerArtworkSizeProgress(.5f,
                    fullTop, fullHeight, ordinary + titleQuadratic, 9.6f, 32f, titleQuadratic), 0f)
            }
        }
        assertTrue(admitted > 0)
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
