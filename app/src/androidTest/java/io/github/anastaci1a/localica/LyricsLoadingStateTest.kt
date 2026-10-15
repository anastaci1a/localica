package io.github.anastaci1a.localica

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.library.Track
import io.github.anastaci1a.localica.library.TrackLocation
import io.github.anastaci1a.localica.lyrics.*
import io.github.anastaci1a.localica.ui.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

class LyricsLoadingStateTest {
    @get:Rule val compose = createComposeRule()

    private fun lyrics(text: String) = LyricsResult(listOf(LyricSource(text, text, LrcParser.parse(text))))

    private fun assertNoMissingFrames(frames: Int = 24) {
        repeat(frames) {
            compose.mainClock.advanceTimeByFrame()
            compose.onAllNodesWithTag("empty-lyrics", true).assertCountEquals(0)
        }
    }

    @Test fun loadingAndOutgoingLoadingLayersNeverBecomeMissingWhenLyricsArrive() {
        var result by mutableStateOf<LyricsResult?>(null)
        var reducedMotion by mutableStateOf(false)
        compose.setContent { LocalicaTheme {
            LyricsPanel(null, 0, reducedMotion, {}, Modifier.fillMaxWidth().height(360.dp), result = result)
        } }
        compose.mainClock.autoAdvance = false
        try {
            for (reduced in listOf(false, true)) {
                compose.runOnIdle { reducedMotion = reduced; result = null }
                assertNoMissingFrames()
                compose.runOnIdle { result = lyrics("Arriving lyrics") }
                // Inspect every outgoing frame, not only the settled result. A null Crossfade
                // target used to paint a false empty indicator as soon as this read completed.
                assertNoMissingFrames()
                compose.onNodeWithText("Arriving lyrics").assertIsDisplayed()
                compose.runOnIdle { result = lyrics("Another source") }
                assertNoMissingFrames()
                compose.onNodeWithText("Another source").assertIsDisplayed()

                compose.runOnIdle { result = LyricsResult(problem = "The source could not be read.") }
                assertNoMissingFrames()
                compose.runOnIdle { result = lyrics("Readable despite another source failure").copy(problem = "Sidecar unavailable") }
                assertNoMissingFrames()
                compose.onNodeWithText("Readable despite another source failure").assertIsDisplayed()

                for (missing in listOf(LyricsResult())) {
                    compose.runOnIdle { result = missing }
                    compose.mainClock.advanceTimeBy(400)
                    compose.onAllNodesWithTag("empty-lyrics", true).assertCountEquals(1)
                    compose.onNodeWithTag("lyric-lines").assertDoesNotExist()
                    compose.runOnIdle { result = null }
                    assertNoMissingFrames()
                    compose.runOnIdle { result = lyrics("Arriving lyrics") }
                    assertNoMissingFrames()
                    compose.onNodeWithText("Arriving lyrics").assertIsDisplayed()
                }
            }
        } finally { compose.mainClock.autoAdvance = true }
    }

    @Test fun obsoleteTrackAndRevisionCompletionsCannotPublishMissingOrRealLyrics() {
        data class Request(val track: Track, val revision: Long,
            val result: CompletableDeferred<LyricsResult> = CompletableDeferred(),
            val completed: CompletableDeferred<Unit> = CompletableDeferred())
        val requests = CopyOnWriteArrayList<Request>()
        var track by mutableStateOf(Track("file:///first.mp3", "First", fileName = "first.mp3", relativePath = "first.mp3"))
        var revision by mutableLongStateOf(0L)
        compose.setContent { LocalicaTheme {
            val result = rememberLyrics(track, revision) { requestedTrack, requestedRevision ->
                val request = Request(requestedTrack, requestedRevision).also(requests::add)
                // Model a provider read that finishes even after its owner was cancelled.
                withContext(NonCancellable) { request.result.await() }.also { request.completed.complete(Unit) }
            }
            LyricsPanel(track, 0, false, {}, Modifier.fillMaxWidth().height(360.dp), result = result)
        } }
        compose.mainClock.autoAdvance = false
        fun request(index: Int): Request {
            compose.waitUntil(5000) {
                compose.mainClock.advanceTimeByFrame()
                requests.size > index
            }
            return requests[index]
        }
        fun finish(request: Request, result: LyricsResult) {
            request.result.complete(result)
            compose.waitUntil(5000) {
                compose.mainClock.advanceTimeByFrame()
                request.completed.isCompleted
            }
            assertNoMissingFrames()
        }
        try {
            val first = request(0)
            compose.runOnIdle { track = track.copy(id = "second", uri = "file:///second.mp3") }
            val second = request(1)
            assertEquals("second", second.track.id)
            finish(first, LyricsResult())
            compose.onNodeWithTag("lyric-lines").assertDoesNotExist()

            compose.runOnIdle { track = track.copy(id = "third", uri = "file:///third.mp3") }
            val third = request(2)
            finish(second, lyrics("Obsolete song"))
            compose.onNodeWithText("Obsolete song").assertDoesNotExist()

            compose.runOnIdle { revision = 1L }
            val refreshed = request(3)
            assertEquals(third.track, refreshed.track)
            assertEquals(1L, refreshed.revision)
            finish(third, LyricsResult(problem = "Obsolete read failed."))
            finish(refreshed, lyrics("Current lyrics"))
            compose.onNodeWithText("Current lyrics").assertIsDisplayed()

            compose.runOnIdle { revision = 2L }
            val olderRevision = request(4)
            compose.runOnIdle { revision = 3L }
            val currentRevision = request(5)
            finish(olderRevision, lyrics("Obsolete revision"))
            compose.onNodeWithText("Obsolete revision").assertDoesNotExist()
            finish(currentRevision, lyrics("Refreshed lyrics"))
            compose.onNodeWithText("Refreshed lyrics").assertIsDisplayed()

            // A changed URI must reset availability even if the logical song ID stays the same.
            compose.runOnIdle { track = track.copy(uri = "file:///moved.mp3") }
            val moved = request(6)
            assertEquals(currentRevision.track.id, moved.track.id)
            assertNoMissingFrames()
            compose.runOnIdle { track = track.copy(locations = listOf(TrackLocation("folder", track.uri, "moved.mp3"))) }
            val relocated = request(7)
            assertEquals(moved.track.uri, relocated.track.uri)
            finish(moved, LyricsResult())
            relocated.result.complete(LyricsResult())
            compose.waitUntil(5000) {
                compose.mainClock.advanceTimeByFrame()
                compose.onAllNodesWithTag("empty-lyrics", true).fetchSemanticsNodes().size == 1
            }
            compose.mainClock.advanceTimeBy(400)
            compose.onNodeWithTag("lyric-lines").assertDoesNotExist()
        } finally {
            requests.forEach { it.result.complete(LyricsResult()) }
            compose.mainClock.autoAdvance = true
        }
    }
}
