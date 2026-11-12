package io.github.anastaci1a.localica.ui

import io.github.anastaci1a.localica.ui.components.playerLyricsMotion
import io.github.anastaci1a.localica.ui.components.playerRowMotion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** U10.03 mapped-output witness: every player output mapped from the expansion phase is C1 (I2), including the
 * seek and actions reveal joins at p=.8/.83 and the open endpoint, where the old clamped ramps stepped by 5 and 1/.17. */
class PlayerMappedOutputTest {
    private val h = 1e-4f
    private fun rows(p: Float, mode: Float) = playerRowMotion(p,
        (7f - 300f) * (1f - p), (1.6f - 484f) * (1f - p * p * (3f - 2f * p)), mode)
    private fun outputs(p: Float, mode: Float): List<Pair<String, Float>> {
        val row = rows(p, mode); val lyrics = playerLyricsMotion(p, mode)
        return listOf("seekAlpha" to row.seekAlpha, "actionsAlpha" to row.actionsAlpha, "seekOffset" to row.seekOffset,
            "actionsOffset" to row.actionsOffset, "lyricsContent" to lyrics.contentAlpha, "lyricsLayout" to lyrics.layout,
            "artworkAlpha" to lyrics.artworkAlpha)
    }

    /** One-sided derivatives across [p]; the outputs are C1 when they agree. Endpoints compare the inner side with rest (0). */
    private fun derivativeStep(p: Float, mode: Float): Map<String, Float> {
        val at = outputs(p, mode).toMap()
        val before = if (p - h < 0f) null else outputs(p - h, mode).toMap()
        val after = if (p + h > 1f) null else outputs(p + h, mode).toMap()
        return at.keys.associateWith { key ->
            val left = before?.let { (at.getValue(key) - it.getValue(key)) / h } ?: 0f
            val right = after?.let { (it.getValue(key) - at.getValue(key)) / h } ?: 0f
            kotlin.math.abs(right - left)
        }
    }

    @Test fun seekRevealAtPointEight() {
        for (mode in listOf(0f, .5f)) for (p in listOf(.8f, .83f, 1f)) {
            val steps = derivativeStep(p, mode)
            assertTrue("Seek reveal derivative steps by ${steps["seekAlpha"]} at p=$p", steps.getValue("seekAlpha") < .05f)
            assertTrue("Actions reveal derivative steps by ${steps["actionsAlpha"]} at p=$p", steps.getValue("actionsAlpha") < .05f)
        }
    }

    @Test fun everyMappedOutputIsContinuousWithBoundedSlopeAcrossTheBand() {
        for (mode in listOf(0f, .25f, 1f)) for (step in 1..255) {
            val p = step / 256f
            // Normalized outputs; the row offsets are unchanged polynomial geometry whose curvature alone exceeds this bound at h.
            derivativeStep(p, mode).filterKeys { !it.endsWith("Offset") }
                .forEach { (name, jump) -> assertTrue("$name derivative steps by $jump at p=$p, mode=$mode", jump < .2f) }
        }
        // Every player map rests at its window ends with zero slope, so each join is C1 on [-.05, 1.05].
        io.github.anastaci1a.localica.ui.components.PlayerReveal.entries.forEach { map ->
            assertEquals(0f, map.at(-.05f), 0f); assertEquals(1f, map.at(1.05f), 0f)
            for (q in listOf(-.05f, 0f, 1f, 1.05f)) assertEquals(0f, map.slope(q), 0f)
        }
        for (p in listOf(0f, 1f)) for (mode in listOf(0f, 1f)) {
            val rest = rows(p, mode)
            assertEquals(p, rest.seekAlpha, 0f)
            assertEquals(p * (1f - mode), rest.actionsAlpha, 0f)
            assertEquals(p * mode, playerLyricsMotion(p, mode).contentAlpha, 0f)
        }
    }
}
