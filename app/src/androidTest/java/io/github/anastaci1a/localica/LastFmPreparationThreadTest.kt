package io.github.anastaci1a.localica

import android.os.Looper
import android.os.Trace
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.lastfm.*
import io.github.anastaci1a.localica.library.*
import io.github.anastaci1a.localica.network.NetworkConsent
import io.github.anastaci1a.localica.ui.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** Structural red/green witness; device performance claims require qualified A35 captures. */
class LastFmPreparationThreadTest {
    @get:Rule val compose = localicaComposeRule()
    private class WatchedList<T>(private val values: List<T>) : AbstractList<T>() {
        val mainReads = AtomicInteger()
        override val size get() = values.size
        override fun get(index: Int): T {
            if (Looper.myLooper() == Looper.getMainLooper()) {
                if (mainReads.incrementAndGet() == 1) {
                    Trace.beginSection("LastFm.archive.traversedOnMain"); Trace.endSection()
                }
            }
            return values[index]
        }
    }
    private fun record(name: String, reads: Int) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = nativeEvidenceDirectory("lastfm-preparation", context.getExternalFilesDir(null))
        File(dir, "$name.txt").writeText("main_archive_reads=$reads\nfixture_songs=15000\nfixture_scrobbles=75000\n")
    }
    @Test fun openingLargeArchiveDoesNotTraverseItsTracksOnMain() {
        val remote = WatchedList(List(15000) { LastFmTrack("Artist ${it % 3908}", "Song $it", 5) })
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val controller = LastFmController(object : LastFmStore {
            override suspend fun read() = SavedLastFm("fixture", snapshot = LastFmSnapshot("fixture", 1, remote, emptyMap()))
            override suspend fun write(value: SavedLastFm) { }
        }, LastFmSync(LastFmApi { _, _ -> error("No network") }), flowOf(LibrarySnapshot()), true,
            object : NetworkConsent {
                override val allowed = MutableStateFlow(false)
                override suspend fun setAllowed(value: Boolean) { }
            }, scope)
        try {
            runBlocking { withTimeout(30000) { controller.state.first { !it.loading } } }
            compose.setContent { LocalicaTheme {
                Trace.beginSection("LastFm.firstComposition")
                Column(Modifier.controlVerticalScroll(rememberScrollState())) { LastFmSettings(controller) }
                Trace.endSection()
            } }
            compose.onNodeWithTag("lastfm-tabs").assertExists()
            record("open", remote.mainReads.get())
            assertEquals("Opening must not traverse archive tracks on main", 0, remote.mainReads.get())
        } finally { scope.cancel() }
    }
    @Test fun openingMatchesDoesNotTraverseOrNormalizeTheGroupOnMain() {
        val remote = LastFmTrack("Artist", "Song", 5)
        val reviews = WatchedList(List(15000) { LastFmReview(remote.copy(title = "Song $it"), emptyList()) })
        val matches = LastFmMatches(emptyMap(), reviews)
        compose.setContent { LocalicaTheme {
            Trace.beginSection("LastFm.matches.firstComposition")
            Column(Modifier.controlVerticalScroll(rememberScrollState())) {
                LastFmReviewList(matches, LastFmGroup.REVIEW, emptyList(), { _, _ -> true }, {})
            }
            Trace.endSection()
        } }
        compose.waitUntil(30000) { compose.onAllNodesWithTag("lastfm-list-search").fetchSemanticsNodes().isNotEmpty() }
        record("matches", reviews.mainReads.get())
        assertEquals("Group preparation must stay off main", 0, reviews.mainReads.get())
    }
}
