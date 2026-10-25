package io.github.anastaci1a.localica

import androidx.compose.runtime.*
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.anastaci1a.localica.ui.GlassTone
import io.github.anastaci1a.localica.ui.rememberToneSnapshot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic bitmap identities and controlled completions only; no screenshots/readbacks. */
@RunWith(AndroidJUnit4::class)
class ToneSnapshotContinuityTest {
    @get:Rule val compose = createComposeRule()

    private class Request(val image: ImageBitmap, val tone: GlassTone) {
        val answer = CompletableDeferred<ImageBitmap>()
        val returned = CompletableDeferred<Unit>()
    }

    private inner class Fixture {
        val first = ImageBitmap(2, 2)
        var image by mutableStateOf<ImageBitmap?>(first)
        var tone by mutableStateOf(GlassTone(.25f, 2f, false))
        var actual: ImageBitmap? = null
        val published = mutableListOf<ImageBitmap?>()
        val requests = mutableListOf<Request>()
        // Exercise even a preparer whose owned work finishes after its caller is cancelled.
        val prepare: suspend (ImageBitmap, GlassTone) -> ImageBitmap = { input, curve ->
            val request = Request(input, curve)
            requests += request
            try {
                withContext(NonCancellable) { request.answer.await() }
            } finally {
                request.returned.complete(Unit)
            }
        }

        init {
            compose.setContent {
                val value = rememberToneSnapshot(image, tone, prepare)
                SideEffect { actual = value; published += value }
            }
        }

        fun request(index: Int): Request {
            compose.waitUntil(5000) { requests.size > index }
            return compose.runOnIdle { requests[index] }
        }

        fun complete(request: Request, output: ImageBitmap) {
            compose.runOnIdle { request.answer.complete(output) }
            compose.waitUntil(5000) { request.returned.isCompleted }
            compose.waitForIdle()
        }

        fun assertOutput(expected: ImageBitmap?) {
            compose.runOnIdle { assertSame(expected, actual) }
        }

        fun close() {
            // Release only this fixture's deliberately suspended owned preparations.
            compose.runOnIdle { requests.forEach { it.answer.complete(it.image) } }
            compose.waitUntil(5000) { requests.all { it.returned.isCompleted } }
            compose.waitForIdle()
        }
    }

    @Test fun completedPositiveToneRemainsVisibleThroughoutPendingSourceReplacement() {
        val f = Fixture()
        try {
            val initial = f.request(0)
            f.assertOutput(f.first) // Existing initial-unprepared fallback semantics.
            val prepared = ImageBitmap(2, 2)
            f.complete(initial, prepared)
            f.assertOutput(prepared)
            val next = ImageBitmap(3, 2)
            compose.runOnIdle { f.image = next }
            val replacement = f.request(1)
            assertSame(next, replacement.image)
            assertEquals(initial.tone, replacement.tone)
            f.assertOutput(prepared)
            assertFalse(replacement.answer.isCompleted)
            compose.runOnIdle { assertFalse("No untoned replacement publication", f.published.any { it === next }) }
            val nextPrepared = ImageBitmap(3, 2)
            f.complete(replacement, nextPrepared)
            f.assertOutput(nextPrepared)
        } finally { f.close() }
    }

    @Test fun obsoleteNonCancellablePreparationCannotPublishBeforeOrAfterLatestCompletion() {
        val f = Fixture()
        try {
            val initial = f.request(0)
            val prepared = ImageBitmap(2, 2)
            f.complete(initial, prepared)
            compose.runOnIdle { f.image = ImageBitmap(3, 2) }
            val obsoleteBefore = f.request(1)
            compose.runOnIdle { f.image = ImageBitmap(4, 2) }
            val latest = f.request(2)
            val staleBefore = ImageBitmap(3, 2)
            f.complete(obsoleteBefore, staleBefore)
            f.assertOutput(prepared)
            val latestPrepared = ImageBitmap(4, 2)
            f.complete(latest, latestPrepared)
            f.assertOutput(latestPrepared)
            compose.runOnIdle { f.image = ImageBitmap(5, 2) }
            val obsoleteAfter = f.request(3)
            compose.runOnIdle { f.image = ImageBitmap(6, 2) }
            val newest = f.request(4)
            val newestPrepared = ImageBitmap(6, 2)
            f.complete(newest, newestPrepared)
            val staleAfter = ImageBitmap(5, 2)
            f.complete(obsoleteAfter, staleAfter)
            f.assertOutput(newestPrepared)
            compose.runOnIdle {
                assertFalse(f.published.any { it === staleBefore || it === staleAfter })
            }
        } finally { f.close() }
    }

    @Test fun zeroTrimAndNullRemainImmediateAndRetirePendingPositivePreparations() {
        val f = Fixture()
        try {
            val initial = f.request(0)
            val prepared = ImageBitmap(2, 2)
            f.complete(initial, prepared)
            val next = ImageBitmap(3, 2)
            compose.runOnIdle { f.image = next }
            val pending = f.request(1)
            compose.runOnIdle { f.tone = GlassTone(0f) }
            f.assertOutput(next)
            val stale = ImageBitmap(3, 2)
            f.complete(pending, stale)
            f.assertOutput(next)
            compose.runOnIdle { assertEquals("Zero trim starts no preparation", 2, f.requests.size) }
            compose.runOnIdle { f.image = null }
            f.assertOutput(null)
            compose.runOnIdle { f.tone = GlassTone(.5f, 1f, true) }
            f.assertOutput(null)
            compose.runOnIdle { assertEquals("Null starts no preparation", 2, f.requests.size) }
            val third = ImageBitmap(4, 2)
            compose.runOnIdle { f.image = third }
            val resumed = f.request(2)
            f.assertOutput(third) // Null reset retains the existing initial fallback behavior.
            compose.runOnIdle { f.image = null }
            f.assertOutput(null)
            val afterNull = ImageBitmap(4, 2)
            f.complete(resumed, afterNull)
            f.assertOutput(null)
            compose.runOnIdle { assertFalse(f.published.any { it === stale || it === afterNull }) }
        } finally { f.close() }
    }

    @Test fun changedPositiveToneRetainsCompletedContentAndPublishesOnlyMatchingLatestCurve() {
        val f = Fixture()
        try {
            val initial = f.request(0)
            val prepared = ImageBitmap(2, 2)
            f.complete(initial, prepared)
            val curve = GlassTone(.5f, 2f, true)
            compose.runOnIdle { f.tone = curve }
            val obsolete = f.request(1)
            assertSame(f.first, obsolete.image)
            assertEquals(curve, obsolete.tone)
            f.assertOutput(prepared)
            val finalCurve = GlassTone(.5f, 1f, false)
            compose.runOnIdle { f.tone = finalCurve }
            val current = f.request(2)
            assertEquals(finalCurve, current.tone)
            val stale = ImageBitmap(2, 2)
            f.complete(obsolete, stale)
            f.assertOutput(prepared)
            val changed = ImageBitmap(2, 2)
            f.complete(current, changed)
            f.assertOutput(changed)
            compose.runOnIdle { assertFalse(f.published.any { it === stale }) }
        } finally { f.close() }
    }
}
