package io.github.anastaci1a.localica

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import io.github.anastaci1a.localica.localicaComposeRule as createComposeRule
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.lyrics.*
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Tapping a lyric while paused moves the highlight to it promptly, every time (#518). */
class LyricsPausedSeekTest {
    @get:Rule val compose = createComposeRule()

    @Test fun pausedLyricTapsMoveTheHighlightWithinHalfASecond() {
        val document = LrcParser.parse((0..40).joinToString("\n") { "[00:%02d.98]line %02d".format(it, it) })
        var position by mutableLongStateOf(0L)
        compose.setContent { LyricsEngineTheme(true) { LyricsPanel(null, position, true, { position = it }, Modifier.height(400.dp),
            playing = false, sampledAtMs = 1L, durationMs = 125000,
            result = LyricsResult(listOf(LyricSource("fixture", "Fixture", document)))) } }
        compose.waitUntil(10000) { compose.onAllNodesWithTag("lyric-lines").fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        val selected = SemanticsMatcher.expectValue(SemanticsProperties.Selected, true)
        // Alternate forwards and backwards among the displayed lines so every trial changes the current line.
        for (trial in 0 until 10) {
            val shown = document.lines.map { it.text }.filter { compose.onAllNodesWithText(it).fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodes(hasText(it) and selected).fetchSemanticsNodes().isEmpty() }
            assertTrue("Trial $trial: a displayed line other than the current one", shown.isNotEmpty())
            val text = if (trial % 2 == 0) shown.last() else shown.first()
            compose.onNodeWithText(text).performClick()
            val deadline = System.currentTimeMillis() + 500
            var highlighted = false
            while (!highlighted && System.currentTimeMillis() < deadline) {
                compose.waitForIdle()
                highlighted = compose.onAllNodes(hasText(text) and selected).fetchSemanticsNodes().isNotEmpty()
            }
            assertTrue("Trial $trial: '$text' (position $position) is highlighted within 500 ms", highlighted)
            assertEquals("Trial $trial: exactly one line is current", 1,
                compose.onAllNodes(selected and hasAnyAncestor(hasTestTag("lyric-lines"))).fetchSemanticsNodes().size)
        }
    }
}
