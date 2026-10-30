package io.github.anastaci1a.localica

import android.os.Looper
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import io.github.anastaci1a.localica.browse.BrowseSortSnapshot
import io.github.anastaci1a.localica.library.*
import io.github.anastaci1a.localica.ui.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class CollectionLookupLifecycleTest {
    @get:Rule val compose = createComposeRule()
    private fun track(index: Int) = Track("lookup:$index", "Song $index", album = "Album ${index % 10}",
        fileName = "$index.mp3", relativePath = "$index.mp3")
    private class HeldTracks(val values: List<Track>) : AbstractList<Track>() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val mainReads = AtomicInteger()
        private val held = AtomicBoolean()
        override val size get() = values.size
        override fun get(index: Int): Track {
            if (Looper.myLooper() == Looper.getMainLooper()) mainReads.incrementAndGet()
            if (held.compareAndSet(false, true)) {
                started.countDown()
                check(release.await(10, TimeUnit.SECONDS)) { "Test did not release lookup worker" }
            }
            return values[index]
        }
    }
    private fun await(job: Job) = runBlocking { withTimeout(10_000) { job.join() } }

    @Test fun acceptedRootActionSurvivesIntentionalMoreCloseAndPreparesOffMain() {
        val tracks = HeldTracks(List(500, ::track))
        val snapshot = LibrarySnapshot(tracks = tracks)
        var more by mutableStateOf(true)
        var rootAdmitted by mutableStateOf(true)
        lateinit var handle: CollectionLookupHandle
        var opened = 0
        var publishedOnMain = false
        compose.setContent {
            // The handle belongs to the root, not the intentionally closed More subtree.
            handle = rememberCollectionLookup(snapshot, emptyList(), BrowseSortSnapshot.Default) {
                opened++; publishedOnMain = Looper.myLooper() == Looper.getMainLooper()
            }
            if (more) SideEffect { check(rootAdmitted) }
        }
        lateinit var job: Job
        try {
            compose.runOnIdle { more = false; job = handle.open(PlayerAction.Album, tracks.values[0]) { rootAdmitted } }
            assertTrue(tracks.started.await(5, TimeUnit.SECONDS))
            compose.waitForIdle()
            tracks.release.countDown(); await(job)
            compose.runOnIdle { assertEquals(1, opened); assertTrue(publishedOnMain) }
            assertEquals(0, tracks.mainReads.get())
        } finally { tracks.release.countDown() }
    }

    @Test fun disposedInformationLookupCannotNavigateWhenBlockedWorkerReturns() {
        val tracks = HeldTracks(List(500, ::track))
        val snapshot = LibrarySnapshot(tracks = tracks)
        var shown by mutableStateOf(true)
        lateinit var handle: CollectionLookupHandle
        var opened = 0
        compose.setContent {
            if (shown) handle = rememberCollectionLookup(snapshot, emptyList(), BrowseSortSnapshot.Default) { opened++ }
        }
        lateinit var job: Job
        try {
            compose.runOnIdle { job = handle.open(PlayerAction.Album, tracks.values[0]) { shown } }
            assertTrue(tracks.started.await(5, TimeUnit.SECONDS))
            compose.runOnIdle { shown = false }
            compose.waitForIdle()
            tracks.release.countDown(); await(job)
            compose.runOnIdle { assertEquals(0, opened) }
            assertTrue(job.isCancelled); assertEquals(0, tracks.mainReads.get())
        } finally { tracks.release.countDown() }
    }

    @Test fun replacedSourceCancelsPendingLookupWithoutOpeningItsOldCollection() {
        val tracks = HeldTracks(List(500, ::track))
        var snapshot by mutableStateOf(LibrarySnapshot(tracks = tracks))
        lateinit var handle: CollectionLookupHandle
        var opened = 0
        compose.setContent {
            handle = rememberCollectionLookup(snapshot, emptyList(), BrowseSortSnapshot.Default) { opened++ }
        }
        lateinit var job: Job
        try {
            compose.runOnIdle { job = handle.open(PlayerAction.Album, tracks.values[0]) { true } }
            assertTrue(tracks.started.await(5, TimeUnit.SECONDS))
            compose.runOnIdle { snapshot = LibrarySnapshot(tracks = listOf(track(999))) }
            compose.waitForIdle()
            tracks.release.countDown(); await(job)
            compose.runOnIdle { assertEquals(0, opened) }
            assertTrue(job.isCancelled); assertEquals(0, tracks.mainReads.get())
        } finally { tracks.release.countDown() }
    }
    @Test fun acceptedInformationLinkSurvivesItsOwnDismissal() {
        val tracks = HeldTracks(List(500, ::track))
        val snapshot = LibrarySnapshot(tracks = tracks)
        var shown by mutableStateOf(true)
        var opened = 0
        var actionSawClosedDialog = false
        var job: Job? = null
        compose.setContent {
            LocalicaTheme {
                val lookup = rememberCollectionLookup(snapshot, emptyList(), BrowseSortSnapshot.Default) { opened++ }
                if (shown) SongInformation(tracks.values[0], { lookup.cancel(); shown = false }) { action ->
                    actionSawClosedDialog = !shown
                    job = lookup.open(action, tracks.values[0]) { !shown }
                }
            }
        }
        try {
            compose.onNodeWithTag("song-information-Album").performScrollTo().performClick()
            assertTrue(tracks.started.await(5, TimeUnit.SECONDS))
            compose.runOnIdle { assertTrue(actionSawClosedDialog); assertFalse(shown) }
            tracks.release.countDown(); await(requireNotNull(job))
            compose.runOnIdle { assertEquals(1, opened) }
            assertEquals(0, tracks.mainReads.get())
        } finally { tracks.release.countDown() }
    }

}
