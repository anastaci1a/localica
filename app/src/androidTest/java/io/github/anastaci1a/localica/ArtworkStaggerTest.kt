package io.github.anastaci1a.localica

import io.github.anastaci1a.localica.ui.ArtworkStagger
import org.junit.Assert.*
import org.junit.Test

class ArtworkStaggerTest {
    @Test fun burstSpreadsOverFramesAndNeverExceedsTheCap() {
        var clock = 1000L
        val stagger = ArtworkStagger(perFrame = 4, frameMs = 16, maxMs = 96) { clock }
        val waits = List(40) { stagger.next() }
        assertEquals(List(4) { 0L }, waits.take(4))
        assertEquals(List(4) { 16L }, waits.slice(4..7))
        assertEquals(waits, waits.sorted())
        assertTrue("No cover is delayed 100 ms or more", waits.all { it < 100 })
        assertTrue("A visible set spreads over several distinct frames", waits.distinct().size >= 5)
    }

    @Test fun aSeparateResumeAfterQuietStartsAtZeroAgain() {
        var clock = 0L
        val stagger = ArtworkStagger { clock }
        repeat(12) { stagger.next() }
        clock += 500
        assertEquals(0L, stagger.next())
    }

    @Test fun smallResumesAreNotDelayedAtAll() {
        val stagger = ArtworkStagger { 0L }
        assertEquals(List(4) { 0L }, List(4) { stagger.next() })
    }
}
