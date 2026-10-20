package io.github.anastaci1a.localica.ui

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

    private fun rows(p: Float, mode: Float) = playerRowMotion(p,
        (7f - 300f) * (1f - p), (1.6f - 484f) * (1f - p * p * (3f - 2f * p)), mode)
}
