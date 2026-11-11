package io.github.anastaci1a.localica

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.localicaComposeRule as createComposeRule
import io.github.anastaci1a.localica.lyrics.LrcParser
import io.github.anastaci1a.localica.ui.*
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/**
 * #715: the return-to-current-line arrow hides when the user's manual scroll rests at the measured first or last
 * bound of the lyric list and that bound is where a return would land anyway (the current line is the line the bound
 * centres, so a return cannot move the list). It shows again as soon as a real return would move the list: when the
 * user scrolls away, or when playback moves the current line off the bound. Mid-song and away-from-bound cases keep
 * the arrow. Real touch drags on the real LyricsLines; bounds are read from the list's own scroll-range semantics.
 */
class LyricsReturnBoundsTest {
    @get:Rule val compose = createComposeRule()
    private var position by mutableLongStateOf(0L)
    private val strip = mutableListOf<Pair<String, Bitmap>>()

    /** 40 synced lines, two seconds apart: line i is current from i * 2 s. [wrapped] lines carry enough words to wrap
     *  onto several rows, so their centred rest sits (row - single) / 2 px away from the list bound. */
    private fun lyrics(wrapped: Set<Int> = emptySet()) = LrcParser.parse((0 until Lines).joinToString("\n") { index ->
        val seconds = index * 2
        val text = if (index in wrapped) "Bound line ${index + 1} carries many more words than its neighbours so that it wraps onto further rows"
            else "Bound line ${index + 1}"
        "[%02d:%02d.00]%s".format(seconds / 60, seconds % 60, text)
    })
    private var document = lyrics()

    private fun mount(line: Int, wrapped: Set<Int> = emptySet()) {
        document = lyrics(wrapped)
        assertTrue("The fixture is synced", document.synced)
        position = line * 2_000L + 500L
        compose.setContent { LyricsEngineTheme(false) {
            Box(Modifier.width(360.dp).height(320.dp).testTag(SceneTag)) {
                LyricsLines(document, position, false, {}, Modifier.fillMaxSize())
            }
        } }
        compose.waitForIdle()
        arrowAbsent("on opening (following)")
    }

    private fun range() = compose.onNodeWithTag("lyric-lines").fetchSemanticsNode()
        .config[SemanticsProperties.VerticalScrollAxisRange]
    private fun atTop() = range().value() <= 0f
    private fun atBottom() = range().let { it.value() >= it.maxValue() }

    /** Real drags (each sets the user's manual scroll) until the list's measured top bound is reached. */
    private fun dragToTop() {
        repeat(MaxDrags) { if (atTop()) return; compose.onNodeWithTag("lyric-lines").performTouchInput { swipeDown() }; compose.waitForIdle() }
        assertTrue("the drags must reach the measured top bound", atTop())
    }
    private fun dragToBottom() {
        repeat(MaxDrags) { if (atBottom()) return; compose.onNodeWithTag("lyric-lines").performTouchInput { swipeUp() }; compose.waitForIdle() }
        assertTrue("the drags must reach the measured bottom bound", atBottom())
    }
    /** One short drag; [forward] scrolls toward later lines (the finger moves up). */
    private fun dragAway(forward: Boolean) {
        compose.onNodeWithTag("lyric-lines").performTouchInput {
            val from = bottomCenter.copy(y = height * if (forward) .7f else .3f)
            swipe(from, from.copy(y = height * if (forward) .3f else .7f), 300)
        }
        compose.waitForIdle()
    }
    /** A drag at the bound itself: the list cannot move, but the user has taken over the scroll. */
    private fun dragAgainstBound(top: Boolean) {
        compose.onNodeWithTag("lyric-lines").performTouchInput { if (top) swipeDown() else swipeUp() }
        compose.waitForIdle()
    }

    private fun arrow() = compose.onAllNodesWithContentDescription(ReturnLabel).fetchSemanticsNodes()
    private fun arrowShown(what: String) = assertTrue("$what: the return arrow must show (a return would move the list)", arrow().isNotEmpty())
    private fun arrowAbsent(what: String) = assertTrue("$what: the return arrow must hide (a return would not move the list)", arrow().isEmpty())

    private fun shot(label: String) {
        strip += label to compose.onNodeWithTag(SceneTag).captureNativeFrame().asAndroidBitmap()
    }

    private fun export(case: String) {
        if (strip.isEmpty()) return
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val root = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let(::File)
            ?: requireNotNull(instrumentation.targetContext.getExternalFilesDir(null))
        val cellWidth = strip.maxOf { it.second.width } + 4
        val cellHeight = strip.maxOf { it.second.height } + 28
        val sheet = Bitmap.createBitmap(cellWidth * strip.size, cellHeight, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(sheet).apply { drawColor(android.graphics.Color.WHITE) }
        val paint = android.graphics.Paint().apply { color = android.graphics.Color.BLACK; textSize = 16f; isAntiAlias = true }
        strip.forEachIndexed { index, (label, image) ->
            canvas.drawText(label, (index * cellWidth + 4).toFloat(), 18f, paint)
            canvas.drawBitmap(image, (index * cellWidth).toFloat(), 24f, null)
        }
        File(root, "lyrics-return-bounds-$case-strip.png").outputStream().use { sheet.compress(Bitmap.CompressFormat.PNG, 100, it) }
        File(root, "lyrics-return-bounds-$case.txt").writeText(strip.joinToString("\n", postfix = "\n") { it.first })
    }

    /** The current line is the first line: dragging to the top bound hides the arrow; dragging away shows it again. */
    @Test fun firstLineAtTheTopBoundHidesTheReturn() {
        mount(0)
        shot("open: line 1 current")
        dragAway(forward = true)
        arrowShown("scrolled away from the first line")
        shot("away: arrow shown")
        dragToTop()
        arrowAbsent("manually at the top bound with the first line current")
        shot("top bound: arrow hidden")
        dragAgainstBound(top = true)
        arrowAbsent("dragging against the top bound with the first line current")
        dragAway(forward = true)
        arrowShown("scrolled away from the top bound again")
        shot("away again: arrow shown")
        export("first")
    }

    /** The current line is the last line: dragging to the bottom bound hides the arrow; dragging away shows it again. */
    @Test fun lastLineAtTheBottomBoundHidesTheReturn() {
        mount(Lines - 1)
        shot("open: last line current")
        dragAway(forward = false)
        arrowShown("scrolled away from the last line")
        shot("away: arrow shown")
        dragToBottom()
        arrowAbsent("manually at the bottom bound with the last line current")
        shot("bottom bound: arrow hidden")
        dragAgainstBound(top = false)
        arrowAbsent("dragging against the bottom bound with the last line current")
        dragAway(forward = false)
        arrowShown("scrolled away from the bottom bound again")
        shot("away again: arrow shown")
        export("last")
    }

    /** Resting at the top bound, playback advancing to line 2 makes a real return move the list: the arrow shows. */
    @Test fun advancingOffTheBoundShowsTheReturnAgain() {
        mount(0)
        dragAway(forward = true)
        dragToTop()
        arrowAbsent("at the top bound with the first line current")
        compose.runOnIdle { position = 2_500L }
        compose.waitForIdle()
        arrowShown("at the top bound after the current line advanced to line 2")
    }

    /** A mid-song current line: the bounds are not where a return lands, so the arrow stays at both ends and in between. */
    @Test fun midSongReturnIsUnchangedAtBothBounds() {
        mount(Lines / 2)
        dragAway(forward = true)
        arrowShown("mid-song, scrolled away")
        shot("mid: away")
        dragToTop()
        arrowShown("mid-song current line, list at the top bound")
        shot("mid: top bound, arrow shown")
        dragToBottom()
        arrowShown("mid-song current line, list at the bottom bound")
        shot("mid: bottom bound, arrow shown")
        compose.onNodeWithContentDescription(ReturnLabel).performClick()
        compose.waitForIdle()
        arrowAbsent("after returning")
        export("mid")
    }

    /** #715 (lead decision): a wrapped first line rests (row - single) / 2 px below the top bound when centred, yet at
     *  the first lyric there is no return arrow: the bound tolerance is that line's own rest offset. */
    @Test fun wrappedFirstLineAtTheTopBoundHidesTheReturn() {
        mount(0, wrapped = setOf(0))
        shot("open: wrapped line 1 current")
        dragAway(forward = true)
        arrowShown("scrolled away from the wrapped first line")
        shot("away: arrow shown")
        dragToTop()
        arrowAbsent("manually at the top bound with the wrapped first line current")
        shot("top bound: arrow hidden")
        dragAway(forward = true)
        arrowShown("scrolled away from the top bound again")
        export("wrapped-first")
    }

    /** The same at the bottom bound for a wrapped last line. */
    @Test fun wrappedLastLineAtTheBottomBoundHidesTheReturn() {
        mount(Lines - 1, wrapped = setOf(Lines - 1))
        shot("open: wrapped last line current")
        dragAway(forward = false)
        arrowShown("scrolled away from the wrapped last line")
        shot("away: arrow shown")
        dragToBottom()
        arrowAbsent("manually at the bottom bound with the wrapped last line current")
        shot("bottom bound: arrow hidden")
        dragAway(forward = false)
        arrowShown("scrolled away from the bottom bound again")
        export("wrapped-last")
    }

    /** A wrapped second line at the top bound: the return still moves the list by a whole first row and more. */
    @Test fun wrappedSecondLineAtTheTopBoundKeepsTheReturn() {
        mount(1, wrapped = setOf(1))
        dragAway(forward = true)
        dragToTop()
        arrowShown("the wrapped second line is current and the list rests at the top bound")
    }

    /** The second line is current: the top bound centres line 1, so a return still moves the list. */
    @Test fun secondLineAtTheTopBoundKeepsTheReturn() {
        mount(1)
        dragAway(forward = true)
        dragToTop()
        arrowShown("the second line is current and the list rests at the top bound")
    }

    private companion object {
        const val Lines = 40
        const val MaxDrags = 30
        const val SceneTag = "lyrics-return-bounds-scene"
        const val ReturnLabel = "Return to current line"
    }
}
