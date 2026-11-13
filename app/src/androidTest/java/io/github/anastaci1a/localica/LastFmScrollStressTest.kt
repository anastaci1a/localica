package io.github.anastaci1a.localica

import android.content.Intent
import android.os.Build
import android.os.Debug
import android.os.SystemClock
import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import io.github.anastaci1a.localica.browse.BrowsePreferences
import io.github.anastaci1a.localica.navigation.BrowseRoute
import io.github.anastaci1a.localica.browse.BrowseStyle
import io.github.anastaci1a.localica.browse.Presentation
import io.github.anastaci1a.localica.lastfm.FileLastFmStore
import io.github.anastaci1a.localica.lastfm.LastFmSnapshot
import io.github.anastaci1a.localica.lastfm.LastFmTrack
import io.github.anastaci1a.localica.lastfm.SavedLastFm
import io.github.anastaci1a.localica.library.FolderSelection
import io.github.anastaci1a.localica.library.LibraryRepository
import io.github.anastaci1a.localica.library.LibraryStore
import io.github.anastaci1a.localica.library.ScanResult
import io.github.anastaci1a.localica.library.Track
import io.github.anastaci1a.localica.navigation.GlassAppearance
import io.github.anastaci1a.localica.navigation.GlassRendererMode
import io.github.anastaci1a.localica.navigation.InterfacePreferences
import io.github.anastaci1a.localica.navigation.InterfaceSettings
import io.github.anastaci1a.localica.navigation.MainTab
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.math.ceil

/**
 * #779 stress fixture: an operator-scale library (13,313 songs) and Last.fm archive (15,000 songs,
 * about 75,000 scrobbles) run through the REAL application pipeline (MainActivity, LibraryViewModel,
 * LastFmController, Room store, archive file), then a 5-per-row gallery is flung while the process's
 * GC counters, frame times and a stack sampler are recorded.
 *
 * Opt-in measurement: pass `-e lastfmScroll true`. Modes come from instrumentation arguments:
 * `lastfm=on|off|none` (archive active, archive kept but switched off, no archive),
 * `photos=on|off` (online consent for artist photos), `tab=Albums|Artists|Songs`.
 * The test only fails on harness errors (fixture missing, list missing); numbers go to evidence.
 */
class LastFmScrollStressTest {
    private companion object {
        const val TAG = "LastFmScroll779"
        const val TRACKS = 13_313
        const val PER_ALBUM = 10
        const val ALBUMS_PER_ARTIST = 6
        const val REMOTE_SONGS = 15_000
        const val REMOTE_IN_LIBRARY = 13_000
        const val ROOT = "stress-779"
        const val FLINGS = 6
        const val JANK_NANOS = 16_700_000L
    }

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private val app get() = context.applicationContext as LocalicaApplication
    private fun arg(name: String, default: String) = InstrumentationRegistry.getArguments().getString(name)?.takeIf { it.isNotBlank() } ?: default

    // ---- fixture -------------------------------------------------------------------------------

    /**
     * `-e libraryJsonl <device path>`: one JSON object per line with title, artist, album, albumArtist, durationMs,
     * track, date, genre (private tag data pushed by the harness, never written into evidence). Otherwise synthetic.
     */
    private fun libraryTracks(): List<Track> {
        val path = arg("libraryJsonl", "")
        if (path.isEmpty()) return syntheticTracks()
        val lines = File(path).readLines().filter { it.isNotBlank() }
        require(lines.isNotEmpty()) { "Empty library file $path" }
        return lines.mapIndexed { n, line ->
            val json = JSONObject(line)
            fun text(key: String) = json.optString(key).takeIf { it.isNotBlank() }
            val title = text("title") ?: "Untitled ${n + 1}"
            Track("real779://track/$n", title, artist = text("artist"), album = text("album"),
                durationMs = json.optLong("durationMs").takeIf { it > 0 }, fileName = "$n.m4a", relativePath = "real/$n.m4a",
                id = "real779:track:$n", albumArtist = text("albumArtist"),
                trackNumber = text("track")?.substringBefore('/')?.toIntOrNull(), year = text("date")?.take(4)?.toIntOrNull(), genre = text("genre"))
        }
    }

    private fun syntheticTracks(): List<Track> = (0 until TRACKS).map { n ->
        val album = n / PER_ALBUM
        val artist = album / ALBUMS_PER_ARTIST
        val artistName = "Synthetic Artist ${artist.toString().padStart(3, '0')}"
        Track("stress779://track/$n", "Orbit Moss ${n.toString().padStart(5, '0')}", artist = artistName,
            album = "Synthetic Album ${album.toString().padStart(4, '0')}", durationMs = 180_000L + (n % 120) * 1000L,
            fileName = "$n.mp3", relativePath = "artist-$artist/album-$album/$n.mp3", id = "stress779:track:$n",
            albumArtist = artistName, trackNumber = n % PER_ALBUM + 1, year = 1990 + n % 35, genre = "Synthetic Electronic")
    }

    /** 13,000 archive songs match library songs exactly; 2,000 are absent. Plays average five (about 75k). */
    private fun archive(tracks: List<Track>): LastFmSnapshot {
        val remote = ArrayList<LastFmTrack>(REMOTE_SONGS)
        for (i in 0 until REMOTE_SONGS) {
            val plays = 1L + (i.toLong() * 7919L % 9L)
            if (i < REMOTE_IN_LIBRARY) {
                val track = tracks[i]
                remote += LastFmTrack(track.artist.orEmpty(), track.title, plays, track.album, durationMs = track.durationMs)
            } else remote += LastFmTrack("Absent Artist ${i % 97}", "Absent Song $i", plays, "Absent Album ${i % 331}")
        }
        return LastFmSnapshot("stress779", 1_700_000_000_000L, remote, localBaseline = emptyMap(), refreshBoundarySeconds = 1_700_000_000L)
    }

    private fun archiveFile() = File(context.filesDir, "lastfm-snapshot.json")

    // ---- measurement ---------------------------------------------------------------------------

    private class GcStats(val count: Long, val timeMs: Long, val allocated: Long, val freed: Long,
        val blockingCount: Long, val blockingTimeMs: Long) {
        companion object {
            private fun stat(name: String) = Debug.getRuntimeStat(name)?.toLongOrNull() ?: 0L
            fun now() = GcStats(stat("art.gc.gc-count"), stat("art.gc.gc-time"), stat("art.gc.bytes-allocated"),
                stat("art.gc.bytes-freed"), stat("art.gc.blocking-gc-count"), stat("art.gc.blocking-gc-time"))
        }
        operator fun minus(other: GcStats) = GcStats(count - other.count, timeMs - other.timeMs, allocated - other.allocated,
            freed - other.freed, blockingCount - other.blockingCount, blockingTimeMs - other.blockingTimeMs)
        fun json() = JSONObject().put("gcCount", count).put("gcTimeMs", timeMs).put("bytesAllocated", allocated)
            .put("bytesFreed", freed).put("blockingGcCount", blockingCount).put("blockingGcTimeMs", blockingTimeMs)
    }

    /** Poor man's profiler: which app frames are on RUNNABLE threads while the gallery is flung. */
    private class Sampler : Thread("stress779-sampler") {
        @Volatile var running = true
        val frames = HashMap<String, Int>()
        val threads = HashMap<String, Int>()
        var samples = 0
        private val idle = setOf("nativePollOnce", "wait", "park", "sleep", "nativeSleep", "epollWait", "accept", "read0", "waitForNextFrame")
        override fun run() {
            while (running) {
                val all = Thread.getAllStackTraces()
                samples++
                for ((thread, stack) in all) {
                    if (thread === this || stack.isEmpty() || thread.state != State.RUNNABLE) continue
                    // Native waits report RUNNABLE; they are idle, not work.
                    val top = stack[0].methodName
                    if (top in idle || stack[0].className == "java.lang.Object" || stack[0].className.endsWith("Unsafe")) continue
                    val name = thread.name
                    threads[name] = (threads[name] ?: 0) + 1
                    val own = stack.filter { it.className.startsWith("io.github.anastaci1a") }.take(4)
                    val key = name + " | " + (if (own.isEmpty()) "${stack[0].className}.${stack[0].methodName}"
                        else own.joinToString(" < ") { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" })
                    frames[key] = (frames[key] ?: 0) + 1
                }
                SystemClock.sleep(15)
            }
        }
    }

    private class Frames(val count: Int, val p50: Double, val p90: Double, val p99: Double, val max: Double, val over: Int) {
        fun json() = JSONObject().put("frames", count).put("p50Ms", p50 / 1e6).put("p90Ms", p90 / 1e6).put("p99Ms", p99 / 1e6)
            .put("maxMs", max / 1e6).put("over16_7ms", over)
    }

    private fun flingPass(window: android.view.Window, label: String, sampler: Sampler?): Pair<GcStats, Frames> {
        val width = device.displayWidth; val height = device.displayHeight
        val x = width / 2; val low = (height * .80f).toInt(); val high = (height * .28f).toInt()
        val recorder = NavigationFrameMetricsRecorder.open(window, capacity = 8192, timeoutMillis = 10_000)
        Runtime.getRuntime().gc(); SystemClock.sleep(400)
        val before = GcStats.now()
        val start = NavigationFrameMetricsRecorder.markNanos()
        sampler?.start()
        repeat(FLINGS) { device.swipe(x, low, x, high, 8); SystemClock.sleep(650) }
        repeat(FLINGS) { device.swipe(x, high, x, low, 8); SystemClock.sleep(650) }
        val end = NavigationFrameMetricsRecorder.markNanos()
        sampler?.running = false; sampler?.join()
        val after = GcStats.now()
        SystemClock.sleep(300)
        val result = recorder.finish(start, end)
        val snapshot = checkNotNull(result.snapshot) { "$label: recorder returned no snapshot" }
        val totals = ArrayList<Double>()
        for (i in 0 until snapshot.storedCount) {
            val frame = snapshot[i]
            if (frame.position == NavigationFrameWindow.Position.IN_WINDOW) totals += frame.totalDurationNanos.toDouble()
        }
        assertTrue("$label: no frames recorded (received=${snapshot.receivedCount})", totals.isNotEmpty())
        val sorted = totals.sorted()
        fun at(p: Double) = sorted[(ceil(p * sorted.size).toInt() - 1).coerceIn(0, sorted.size - 1)]
        val frames = Frames(sorted.size, at(.5), at(.9), at(.99), sorted.last(), sorted.count { it > JANK_NANOS })
        return (after - before) to frames
    }

    // ---- scenario ------------------------------------------------------------------------------

    @Test fun galleryFlingWithArchive() {
        assumeTrue("Opt-in stress measurement: pass -e lastfmScroll true", arg("lastfmScroll", "false") == "true")
        val lastfm = arg("lastfm", "on"); val photos = arg("photos", "off") == "on"; val tab = MainTab.valueOf(arg("tab", "Albums"))
        val label = "lastfm-$lastfm-photos-${if (photos) "on" else "off"}-${tab.name.lowercase()}-api${Build.VERSION.SDK_INT}"
        val interfacePreferences = InterfacePreferences(context)
        val originalInterface = interfacePreferences.read()
        val browsePreferences = BrowsePreferences(context)
        val store = LibraryRepository::class.java.getDeclaredField("store").apply { isAccessible = true }.get(app.library) as LibraryStore
        val tracks = libraryTracks()
        val counters = io.github.anastaci1a.localica.diagnostics.WorkCounters
        try {
            // The archive file must exist before MainActivity first touches the lazy controller.
            archiveFile().delete()
            // `-e cold true` discards the persisted match result so this launch pays the one-time streamed match.
            if (arg("cold", "false") == "true") File(context.filesDir, "lastfm-matches.json").delete()
            // `-e archiveFile <device path>`: a previously synced archive (the application's own file format, private
            // operator data pushed by the harness) replaces the synthetic one; only its switches are rewritten.
            val pulled = arg("archiveFile", "")
            if (lastfm != "none") runBlocking {
                val saved = if (pulled.isEmpty()) SavedLastFm("stress779", useCounts = true, snapshot = archive(tracks),
                    active = lastfm == "on", keepSyncingInBackground = false)
                else FileLastFmStore(File(pulled)).read().let { it.copy(useCounts = true, active = lastfm == "on", keepSyncingInBackground = false) }
                FileLastFmStore(archiveFile()).write(saved)
                Log.i(TAG, "archive songs=${saved.snapshot?.tracks?.size} scrobbles=${saved.snapshot?.tracks?.sumOf { it.plays }} source=${if (pulled.isEmpty()) "synthetic" else "pulled"}")
            }
            runBlocking { app.onlineMetadataConsent.setAllowed(photos) }
            runBlocking { store.replaceRoot(ScanResult(FolderSelection(ROOT, "Stress 779"), tracks, 0)) }
            interfacePreferences.write(InterfaceSettings(tabs = listOf(MainTab.Home, MainTab.Library, tab),
                expandOnPlay = false, glass = GlassAppearance(renderer = GlassRendererMode.Normal)))
            browsePreferences.write(BrowseRoute(tab), BrowseStyle(presentation = Presentation.Standard, itemsPerRow = 5), false)
            counters.reset()
            val fixtureStart = GcStats.now()
            ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use { scenario ->
                requireNotNull(device.findFresh(By.desc(tab.label), 20_000)) { "Missing tab ${tab.label}" }.click()
                val firstTitle = when (tab) { MainTab.Artists -> "Synthetic Artist 000"; MainTab.Songs -> "Orbit Moss 00000"; else -> "Synthetic Album 0000" }
                if (arg("libraryJsonl", "").isEmpty()) requireNotNull(device.findFresh(By.text(firstTitle), 60_000)) { "Fixture list did not appear ($firstTitle)" }
                else {
                    // Real tags: wait for any row text of the first album, artist or song row instead of a known title.
                    val firstRow = when (tab) { MainTab.Artists -> tracks.mapNotNull { it.artist }; MainTab.Songs -> tracks.map { it.title }; else -> tracks.mapNotNull { it.album } }
                        .filter { it.isNotBlank() }.minByOrNull { it.trim().lowercase() }
                    requireNotNull(device.findFresh(By.textStartsWith(firstRow.orEmpty().take(24)), 120_000)) { "Fixture list did not appear (real library)" }
                }
                SystemClock.sleep(2500) // Let the first catalog, artwork placeholders and any startup work settle.
                var window: android.view.Window? = null
                scenario.onActivity { window = it.window }
                val target = requireNotNull(window)
                val startupGc = GcStats.now() - fixtureStart
                // Warm-up pass, one clean measured pass, one sampled pass.
                flingPass(target, "$label warmup", null)
                val (gcClean, framesClean) = flingPass(target, "$label clean", null)
                val countersClean = counters.snapshot()
                counters.reset()
                val sampler = Sampler()
                val (gcSampled, framesSampled) = flingPass(target, "$label sampled", sampler)
                assertNotNull("Activity disappeared", device.findObject(By.pkg(context.packageName)))
                assertNull("Crash dialog", device.findObject(By.textContains("keeps stopping")))
                val steady = counters.snapshot()
                if (arg("assertSteady", "false") == "true") {
                    // Scrolling must perform no catalog-scale work: no archive match, overlay, library copy, re-read or catalog build.
                    assertTrue("Catalog-scale work ran while scrolling: $steady", steady.values.all { it == 0L })
                }
                val hot = sampler.frames.entries.sortedByDescending { it.value }.take(40)
                val busy = sampler.threads.entries.sortedByDescending { it.value }.take(15)
                val doc = JSONObject().put("label", label).put("api", Build.VERSION.SDK_INT).put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
                    .put("tracks", TRACKS).put("remoteSongs", REMOTE_SONGS).put("lastfm", lastfm).put("photos", photos).put("tab", tab.name)
                    .put("flings", FLINGS * 2).put("startupGc", startupGc.json())
                    .put("clean", JSONObject().put("gc", gcClean.json()).put("frames", framesClean.json()).put("counters", JSONObject(countersClean)))
                    .put("sampled", JSONObject().put("gc", gcSampled.json()).put("frames", framesSampled.json())
                        .put("samples", sampler.samples).put("counters", JSONObject(counters.snapshot()))
                        .put("busyThreads", JSONArray(busy.map { "${it.key}=${it.value}" }))
                        .put("hotFrames", JSONArray(hot.map { "${it.value} ${it.key}" })))
                val directory = nativeEvidenceDirectory("lastfm-scroll-779", context.getExternalFilesDir(null))
                File(directory, "$label.json").writeText(doc.toString(2) + "\n")
                Log.i(TAG, "SUMMARY $label clean: gc=${gcClean.count} gcTime=${gcClean.timeMs}ms alloc=${gcClean.allocated / 1_048_576}MB " +
                    "freed=${gcClean.freed / 1_048_576}MB blocking=${gcClean.blockingCount}/${gcClean.blockingTimeMs}ms " +
                    "frames=${framesClean.count} p50=${"%.1f".format(framesClean.p50 / 1e6)} p90=${"%.1f".format(framesClean.p90 / 1e6)} " +
                    "p99=${"%.1f".format(framesClean.p99 / 1e6)} max=${"%.1f".format(framesClean.max / 1e6)} over16.7=${framesClean.over} counters=$countersClean")
                Log.i(TAG, "SUMMARY $label sampled: gc=${gcSampled.count} alloc=${gcSampled.allocated / 1_048_576}MB samples=${sampler.samples} busy=$busy")
                hot.forEach { Log.i(TAG, "HOT ${it.value} ${it.key}") }
            }
        } finally {
            runCatching { runBlocking { app.onlineMetadataConsent.setAllowed(false) } }
            runCatching { runBlocking { store.removeRoot(ROOT) } }
            archiveFile().delete()
            interfacePreferences.write(originalInterface)
            context.getSharedPreferences("browse-style", 0).edit().clear().commit()
        }
    }
}
