package io.github.anastaci1a.localica

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.lifecycle.ViewModelProvider
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.library.FolderSelection
import io.github.anastaci1a.localica.library.LibraryDatabase
import io.github.anastaci1a.localica.library.RoomLibraryStore
import io.github.anastaci1a.localica.library.ScanResult
import io.github.anastaci1a.localica.library.Track
import io.github.anastaci1a.localica.navigation.MainTab
import io.github.anastaci1a.localica.ui.LibraryViewModel
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Measurement only (never fails on numbers): launches MainActivity with a seeded 13,313-track
 * library and records main-thread frame metrics for the first five seconds, including the first
 * traversal. The process is already warm (instrumentation shares it), so Application.onCreate and
 * any app singleton already created by an earlier test are not part of this window.
 * Results go to logcat (tag StartupFrameMetrics) and to AGP's additional test output.
 */
class StartupFrameMetricsTest {
    @Test fun libraryTabFirstLaunch() = measure("library-first", listOf(MainTab.Library, MainTab.Songs))
    @Test fun songsTabFirstLaunch() = measure("songs-first", listOf(MainTab.Songs, MainTab.Library))

    private class Milestones {
        val created = AtomicLong(0)
        val skeleton = AtomicLong(0)
        val libraryLoaded = AtomicLong(0)
        val content = AtomicLong(0)
        val libraryEmissions = java.util.concurrent.atomic.AtomicInteger()
        val snapshotIdentities = java.util.concurrent.atomic.AtomicInteger()
        val queueEmissions = java.util.concurrent.atomic.AtomicInteger()
        val playlistEmissions = java.util.concurrent.atomic.AtomicInteger()
    }

    private fun measure(scenario: String, tabs: List<MainTab>) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val application = context.applicationContext as Application
        val catalog = NavigationScaleCatalog(SCALE_AUTHORITY)
        val folder = FolderSelection(catalog.treeUri(NavigationScaleCatalog.ROOT_DOCUMENT_ID), "Startup scale")
        val interfacePreferences = context.getSharedPreferences("interface", Context.MODE_PRIVATE)
        val originalTabs = interfacePreferences.getString("tabs", null)
        val database = Room.databaseBuilder(context, LibraryDatabase::class.java, "library.db")
            .addMigrations(LibraryDatabase.MIGRATION_1_2, LibraryDatabase.MIGRATION_2_3, LibraryDatabase.MIGRATION_3_4,
                LibraryDatabase.MIGRATION_4_5, LibraryDatabase.MIGRATION_5_6).build()
        val store = RoomLibraryStore(database.libraryDao())
        val control = Executors.newSingleThreadExecutor()
        val main = Handler(Looper.getMainLooper())
        val milestones = Milestones()
        val launchActivity = AtomicReference<Activity?>()
        val createdLatch = CountDownLatch(1)
        val queuedLatch = CountDownLatch(1)
        val launchStartRef = AtomicLong(Long.MAX_VALUE)
        // Low-rate main-thread probe (about 1 ms per poll); it stops once content is found.
        val poll = object : Runnable {
            override fun run() {
                val activity = launchActivity.get() ?: return
                val now = System.nanoTime()
                val tags = semanticsTags(activity)
                if (milestones.skeleton.get() == 0L && "browse-loading" in tags) milestones.skeleton.set(now)
                if (milestones.content.get() == 0L && tags.any { it.startsWith("media-") }) milestones.content.set(now)
                if (milestones.content.get() == 0L && now - launchStartRef.get() < CONTENT_TIMEOUT_NANOS) main.postDelayed(this, POLL_MILLIS)
            }
        }
        // Emission observers run off Main; StateFlow conflation means these count delivered values.
        val observers = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default)
        fun observe(owner: LibraryViewModel) {
            observers.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                var last: Any? = null
                owner.library.collect { state ->
                    milestones.libraryEmissions.incrementAndGet()
                    if (state.snapshot !== last) { last = state.snapshot; milestones.snapshotIdentities.incrementAndGet() }
                    if (!state.loading) milestones.libraryLoaded.compareAndSet(0L, System.nanoTime())
                }
            }
            observers.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                owner.playback.queues.state.collect { milestones.queueEmissions.incrementAndGet() }
            }
            observers.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                owner.playlist.collect { milestones.playlistEmissions.incrementAndGet() }
            }
        }
        // Poor-man's profiler: sample Main's stack every 10 ms during the window (test-only; a
        // stack read briefly suspends Main, identical overhead before and after).
        val stackSamples = mutableListOf<Pair<Long, Array<StackTraceElement>>>()
        val sampler = Thread {
            val mainThread = Looper.getMainLooper().thread
            try {
                while (!Thread.currentThread().isInterrupted) {
                    val at = System.nanoTime()
                    val stack = mainThread.stackTrace
                    synchronized(stackSamples) { stackSamples += at to stack }
                    Thread.sleep(SAMPLE_MILLIS)
                }
            } catch (_: InterruptedException) { }
        }
        val callbacks = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                if (activity !is MainActivity || !launchActivity.compareAndSet(null, activity)) return
                milestones.created.set(System.nanoTime())
                // Observe from creation: the test thread's launch() can return seconds later while Main is busy.
                // This callback runs inside super.onCreate, before MainActivity creates its model, so attach
                // right after the launch transaction (front of queue, still ahead of the first traversal).
                main.postAtFrontOfQueue {
                    runCatching { observe(ViewModelProvider(activity)[LibraryViewModel::class.java]) }
                        .onFailure { Log.w(TAG, "Emission observers unavailable", it) }
                    main.post(poll)
                }
                createdLatch.countDown()
                // Hold the launch transaction only until the recorder's registration is queued
                // ahead of the first traversal (bounded; a timeout only loses the first frames).
                queuedLatch.await(5, TimeUnit.SECONDS)
            }
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        }
        try {
            val seedStarted = SystemClock.elapsedRealtime()
            runBlocking {
                store.replaceRoot(ScanResult(folder, List(NavigationScaleCatalog.TRACK_COUNT) { index ->
                    val album = index / 12
                    val artist = album / 4
                    Track(catalog.treeAccessUri(index, NavigationScaleCatalog.ROOT_DOCUMENT_ID), "Song ${index % 12 + 1} of album $album",
                        artist = "Artist $artist", album = "Album $album", durationMs = 180_000L + index % 97 * 1000L,
                        fileName = "track-$index.mp3", relativePath = "Artist $artist/Album $album/track-$index.mp3",
                        id = catalog.canonicalUri(index), albumArtist = "Artist $artist", trackNumber = index % 12 + 1,
                        year = 1970 + album % 50, genre = "Genre ${artist % 20}")
                }, 0))
            }
            val seededMs = SystemClock.elapsedRealtime() - seedStarted
            interfacePreferences.edit().putString("tabs", tabs.joinToString(",") { it.name }).commit()
            application.registerActivityLifecycleCallbacks(callbacks)

            val recorderFuture = control.submit<NavigationFrameMetricsRecorder> {
                check(createdLatch.await(30, TimeUnit.SECONDS)) { "MainActivity was not created" }
                NavigationFrameMetricsRecorder.openBeforeFirstTraversal(requireNotNull(launchActivity.get()).window,
                    capacity = 4096, timeoutMillis = 10_000) { queuedLatch.countDown() }
            }
            val launchStart = NavigationFrameMetricsRecorder.markNanos()
            val windowEnd = launchStart + WINDOW_NANOS
            launchStartRef.set(launchStart)
            sampler.start()
            ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use { _ ->
                val recorder = recorderFuture.get(30, TimeUnit.SECONDS)
                val activity = requireNotNull(launchActivity.get())
                while (System.nanoTime() < windowEnd) SystemClock.sleep(20)
                sampler.interrupt(); sampler.join(5_000)
                val drained = control.submit<Boolean> { recorder.awaitFrameAtOrAfter(windowEnd) }.get(30, TimeUnit.SECONDS)
                val result = control.submit<NavigationFrameMetricsRecorder.Result> { recorder.finish(launchStart, windowEnd) }
                    .get(30, TimeUnit.SECONDS)
                val deadline = SystemClock.elapsedRealtime() + CONTENT_TIMEOUT_NANOS / 1_000_000
                while (milestones.content.get() == 0L && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(20)
                observers.cancel()
                val refresh = activity.windowManager.defaultDisplay.refreshRate.takeIf { it > 1f } ?: 60f
                report(scenario, tabs, seededMs, launchStart, milestones, result, drained, 1_000_000_000.0 / refresh,
                    synchronized(stackSamples) { stackSamples.toList() })
                assertNotNull("Recorder produced a snapshot", result.snapshot)
            }
        } finally {
            application.unregisterActivityLifecycleCallbacks(callbacks)
            observers.cancel()
            sampler.interrupt()
            queuedLatch.countDown()
            control.shutdownNow()
            interfacePreferences.edit().also { editor ->
                if (originalTabs == null) editor.remove("tabs") else editor.putString("tabs", originalTabs)
            }.commit()
            runCatching { runBlocking { store.removeRoot(folder.uri) } }
            database.close()
        }
    }

    /** Busy-Main histogram: innermost app frame and top frame per sample; idle = waiting in the looper. */
    private fun profile(samples: List<Pair<Long, Array<StackTraceElement>>>, launchStart: Long): List<String> {
        val inWindow = samples.filter { it.first - launchStart in 0 until WINDOW_NANOS }
        fun frame(element: StackTraceElement) = "${element.className}.${element.methodName}:${element.lineNumber}"
        val busy = inWindow.filterNot { (_, stack) -> stack.firstOrNull()?.let {
            it.className == "android.os.MessageQueue" && it.methodName == "nativePollOnce" } == true }
        val app = busy.groupingBy { (_, stack) -> stack.firstOrNull { it.className.startsWith("io.github.anastaci1a") }?.let(::frame) ?: "(no app frame)" }.eachCount()
        val top = busy.groupingBy { (_, stack) -> stack.take(2).joinToString(" < ") { frame(it) } }.eachCount()
        // Outermost app frame shows which entry point (composition, service, collector) owns the time.
        val entry = busy.groupingBy { (_, stack) -> stack.lastOrNull { it.className.startsWith("io.github.anastaci1a") }?.let(::frame) ?: "(no app frame)" }.eachCount()
        val perSecond = (0 until (WINDOW_NANOS / 1_000_000_000L).toInt()).map { second ->
            busy.count { (it.first - launchStart) / 1_000_000_000L == second.toLong() } }
        return listOf("profile samples=${inWindow.size} busy=${busy.size} intervalMs=$SAMPLE_MILLIS busyPerSecond=$perSecond") +
            app.entries.sortedByDescending { it.value }.take(40).map { "profile.app ${it.value} ${it.key}" } +
            entry.entries.sortedByDescending { it.value }.take(25).map { "profile.entry ${it.value} ${it.key}" } +
            top.entries.sortedByDescending { it.value }.take(25).map { "profile.top ${it.value} ${it.key}" }
    }

    private fun semanticsTags(activity: Activity): Set<String> {
        val tags = HashSet<String>()
        fun visit(view: View) {
            if (view is ViewRootForTest) {
                fun walk(node: SemanticsNode) {
                    node.config.getOrNull(SemanticsProperties.TestTag)?.let(tags::add)
                    node.children.forEach(::walk)
                }
                walk(view.semanticsOwner.unmergedRootSemanticsNode)
            }
            if (view is ViewGroup) for (index in 0 until view.childCount) visit(view.getChildAt(index))
        }
        activity.window.peekDecorView()?.let(::visit)
        return tags
    }

    private fun report(scenario: String, tabs: List<MainTab>, seededMs: Long, launchStart: Long, milestones: Milestones,
        result: NavigationFrameMetricsRecorder.Result, drained: Boolean, budgetNanos: Double,
        samples: List<Pair<Long, Array<StackTraceElement>>>) {
        val snapshot = result.snapshot
        val frames = if (snapshot == null) emptyList() else (0 until snapshot.storedCount).map { snapshot[it] }
            .filter { it.position == NavigationFrameWindow.Position.IN_WINDOW }
        val durations = frames.map { it.totalDurationNanos }.sorted()
        fun percentile(p: Double) = if (durations.isEmpty()) Double.NaN
            else durations[((durations.size - 1) * p).toInt().coerceIn(0, durations.size - 1)] / 1e6
        val janky = frames.count { frame -> frame.deadlineDurationNanos?.let { frame.totalDurationNanos > it }
            ?: (frame.totalDurationNanos > budgetNanos) }
        fun since(value: Long) = if (value == 0L) "n/a" else "%.1f".format((value - launchStart) / 1e6)
        val first = frames.minByOrNull { it.frameTimestampNanos }
        val firstFrameEnd = first?.let { (it.frameTimestampNanos + it.totalDurationNanos - launchStart) / 1e6 }
        val summary = "scenario=$scenario api=${Build.VERSION.SDK_INT} tabs=${tabs.joinToString(",")} tracks=${NavigationScaleCatalog.TRACK_COUNT} " +
            "seedMs=$seededMs windowMs=${WINDOW_NANOS / 1_000_000} frames=${frames.size} " +
            "p50Ms=%.2f p90Ms=%.2f p99Ms=%.2f longestMs=%.2f janky=%d budgetMs=%.2f ".format(percentile(.5), percentile(.9),
                percentile(.99), (durations.lastOrNull() ?: 0L) / 1e6, janky, budgetNanos / 1e6) +
            "firstFrameEndMs=${firstFrameEnd?.let { "%.1f".format(it) } ?: "n/a"} createdMs=${since(milestones.created.get())} " +
            "skeletonMs=${since(milestones.skeleton.get())} libraryLoadedMs=${since(milestones.libraryLoaded.get())} " +
            "firstContentMs=${since(milestones.content.get())} pollMs=$POLL_MILLIS drained=$drained " +
            "libraryEmissions=${milestones.libraryEmissions.get()} snapshotIdentities=${milestones.snapshotIdentities.get()} " +
            "queueEmissions=${milestones.queueEmissions.get()} playlistEmissions=${milestones.playlistEmissions.get()} " +
            "received=${snapshot?.receivedCount} listenerDrops=${snapshot?.reportedListenerDropCount} overflow=${snapshot?.bufferOverflowCount}"
        val lines = mutableListOf("summary $summary")
        frames.sortedBy { it.frameTimestampNanos }.forEachIndexed { index, frame ->
            lines += "frame[$index] startMs=%.1f totalMs=%.2f deadlineMs=%s".format((frame.frameTimestampNanos - launchStart) / 1e6,
                frame.totalDurationNanos / 1e6, frame.deadlineDurationNanos?.let { "%.2f".format(it / 1e6) } ?: "n/a")
        }
        lines += profile(samples, launchStart)
        lines.forEach { Log.i(TAG, it) }
        runCatching {
            val directory = nativeEvidenceDirectory("startup-frame-metrics", null)
            File(directory, "$scenario-api${Build.VERSION.SDK_INT}.txt").writeText(lines.joinToString("\n", postfix = "\n"))
        }.onFailure { Log.w(TAG, "No additional test output directory; logcat only", it) }
    }

    private companion object {
        const val TAG = "StartupFrameMetrics"
        const val SCALE_AUTHORITY = "io.github.anastaci1a.localica.startup.scale"
        const val WINDOW_NANOS = 5_000_000_000L
        const val CONTENT_TIMEOUT_NANOS = 30_000_000_000L
        const val POLL_MILLIS = 50L
        const val SAMPLE_MILLIS = 10L
    }
}
