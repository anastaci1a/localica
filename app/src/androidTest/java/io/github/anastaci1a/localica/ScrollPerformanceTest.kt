package io.github.anastaci1a.localica

import android.os.Build
import android.util.Log
import android.view.Choreographer
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import io.github.anastaci1a.localica.browse.*
import io.github.anastaci1a.localica.library.*
import io.github.anastaci1a.localica.navigation.*
import io.github.anastaci1a.localica.playlist.*
import io.github.anastaci1a.localica.ui.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.Random
import kotlin.math.abs
import kotlin.math.ceil

private const val SCROLL_ITEM_PREFIX = "media-"

/**
 * Scroll-performance MEASUREMENT harness. It never asserts on performance numbers; it fails only on
 * harness errors (fixture did not load, gesture target missing, recorder failure, no frames at all).
 *
 * The scenarios run in a plain ComponentActivity (no Compose test rule), so Choreographer, animations and
 * input are real-time, and frame metrics come from NavigationFrameMetricsRecorder on the real window.
 * Each scenario: one warm-up pass, [MEASURED] measured repetitions, then one extra "proxy" pass (no
 * recorder) that samples the semantics tree once per frame to count distinct composed item keys.
 * Artwork: the fixture tracks carry no artwork, so no artwork I/O happens (placeholders only).
 */
class ScrollPerformanceTest {
    private companion object {
        const val TAG = "ScrollPerf"
        const val SEED = 20261031L
        const val MEASURED = 5
        const val ALBUMS = 3000
        const val SONGS = 13000
        const val JANK_NANOS = 16_700_000L

    }

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private val prefs = BrowsePreferences(context)
    private var scenario: ActivityScenario<ComponentActivity>? = null

    @Before fun clearPreferences() {
        context.getSharedPreferences("browse-style", 0).edit().clear().commit()
        context.getSharedPreferences("library-query", 0).edit().clear().commit()
    }

    @After fun teardown() {
        runCatching { scenario?.close() }
        context.getSharedPreferences("browse-style", 0).edit().clear().commit()
        context.getSharedPreferences("library-query", 0).edit().clear().commit()
    }

    // ---- fixtures -------------------------------------------------------------------------------

    private fun albumTracks(): List<Track> {
        val random = Random(SEED)
        val out = ArrayList<Track>()
        for (a in 0 until ALBUMS) {
            val artist = "Artist ${random.nextInt(400).toString().padStart(3, '0')}"
            val album = "Album ${a.toString().padStart(4, '0')} ${WORDS[random.nextInt(WORDS.size)]}"
            val count = 1 + random.nextInt(3)
            for (t in 1..count) {
                val id = "perf:album:$a:$t"
                out += Track(id, "$album track $t", artist = artist, album = album, albumArtist = artist,
                    trackNumber = t, year = 1970 + random.nextInt(55), fileName = "$id.mp3", relativePath = "$a/$t.mp3")
            }
        }
        return out
    }

    private fun songTracks(): List<Track> {
        val random = Random(SEED)
        return (0 until SONGS).map { n ->
            val title = "${WORDS[random.nextInt(WORDS.size)]} ${WORDS[random.nextInt(WORDS.size)]} ${n.toString().padStart(5, '0')}"
            Track("perf:song:$n", title, artist = "Artist ${random.nextInt(400)}", fileName = "$n.mp3", relativePath = "$n.mp3")
        }
    }

    private val WORDS = listOf("Amber", "Birch", "Cinder", "Delta", "Ember", "Fjord", "Glass", "Harbor", "Iris",
        "Juniper", "Kite", "Lumen", "Moss", "Nova", "Opal", "Prism", "Quartz", "Reed", "Slate", "Tide")

    /** Launches the real browser screen and returns once the list and a first item exist. */
    private fun launch(route: BrowseRoute, style: BrowseStyle, tracks: List<Track>): Harness {
        prefs.write(route, style, false)
        val launched = ActivityScenario.launch(ComponentActivity::class.java)
        scenario = launched
        launched.onActivity { activity ->
            activity.setContent { LocalicaTheme {
                BrowserScreen(LibraryUiState(LibrarySnapshot(folders = listOf(FolderSelection("root", "Music")), tracks = tracks), loading = false),
                    PlaylistState(), route, {}, {}, {}, { _, _, _ -> }, null, null, true, false)
            } }
        }
        val harness = Harness(launched, device, style.itemsPerRow ?: 1)
        harness.await("fixture list with items", 120_000) { it.list() != null && it.itemKeys().isNotEmpty() && it.maxScroll() > 1f }
        harness.settle()
        return harness
    }

    // ---- scenarios -------------------------------------------------------------------------------

    @Test fun galleryFiveColumnsFlingUpAndDown() {
        val harness = launch(BrowseRoute(MainTab.Albums),
            BrowseStyle(presentation = Presentation.Standard, itemsPerRow = 5), albumTracks())
        runScenario("gallery5-fling", harness, prepare = { it.scrollToIndex(0); it.settle() }) { h ->
            val bounds = h.listScreenBounds()
            val x = bounds.centerX
            val low = bounds.top + bounds.height * .82f
            val high = bounds.top + bounds.height * .18f
            repeat(6) { h.swipe(x, low, x, high, 8); h.settle() }
            repeat(6) { h.swipe(x, high, x, low, 8); h.settle() }
        }
    }

    @Test fun scrollbarDragTopToBottomToTop() {
        val harness = launch(BrowseRoute(MainTab.Songs), BrowseStyle(), songTracks())
        runScenario("scrollbar-drag", harness, prepare = { it.scrollToIndex(0); it.settle() }) { h ->
            val bar = h.nodeBounds { it.config.getOrNull(SemanticsProperties.TestTag) == "browse-scrollbar" }
            val x = bar.centerX
            val top = bar.top + 8f
            val bottom = bar.bottom - 8f
            h.swipe(x, top, x, bottom, 300); h.settle()
            h.swipe(x, bottom, x, top, 300); h.settle()
        }
    }

    @Test fun returnToTopFromDeepInLargeList() {
        val harness = launch(BrowseRoute(MainTab.Songs), BrowseStyle(), songTracks())
        runScenario("return-to-top", harness, prepare = { h ->
            h.scrollToIndex(SONGS / 2); h.settle()
            h.await("Return to top control", 10_000) { it.returnToTop() != null }
            Thread.sleep(600) // Let the control's entrance settle before the measured click.
        }) { h ->
            val button = checkNotNull(h.returnToTop()) { "Return to top control vanished" }
            h.tap(button.centerX, button.centerY)
            h.await("animated return to the top", 30_000) { it.scrollValue() < 1f }
            h.settle()
        }
    }

    // ---- driver ----------------------------------------------------------------------------------

    private fun runScenario(name: String, h: Harness, prepare: (Harness) -> Unit, act: (Harness) -> Unit) {
        val runs = ArrayList<Run>()
        runs += measuredPass(name, "warmup", 0, h, prepare, act)
        for (rep in 1..MEASURED) runs += measuredPass(name, "measured", rep, h, prepare, act)
        // Proxy pass: sampled once per frame on main; its frame numbers are intentionally not recorded.
        prepare(h)
        val sampler = h.startSampler()
        act(h)
        val proxy = h.stopSampler(sampler)
        val unitsPerItem = h.calibrate()
        val rows = proxy.travelItems / unitsPerItem / h.columns
        val workProxy = if (rows > 0.0) proxy.keys.size / rows else Double.NaN
        writeEvidence(name, JSONObject().put("kind", "proxy").put("rep", 0).put("distinctKeys", proxy.keys.size)
            .put("scrollUnitsTravelled", proxy.travelItems).put("scrollUnitsPerItem", unitsPerItem).put("rowsScrolled", rows).put("columns", h.columns)
            .put("workProxyKeysPerRow", workProxy.takeUnless { it.isNaN() } ?: JSONObject.NULL)
            .put("firstScrollValue", proxy.first.toDouble()).put("lastScrollValue", proxy.last.toDouble()))
        val measured = runs.filter { it.kind == "measured" }
        val med = { f: (Run) -> Double -> median(measured.map(f)) }
        val line = "scenario=$name api=${Build.VERSION.SDK_INT} reps=${measured.size} " +
            "frames(med)=${med { it.frames.toDouble() }.fmt()} " +
            "total p50/p90/p99 ms (med)=${(med { it.total.p50 }).ms()}/${(med { it.total.p90 }).ms()}/${(med { it.total.p99 }).ms()} " +
            "layout p50/p90 ms=${(med { it.layout.p50 }).ms()}/${(med { it.layout.p90 }).ms()} " +
            "draw p50/p90 ms=${(med { it.draw.p50 }).ms()}/${(med { it.draw.p90 }).ms()} " +
            "sync p50/p90 ms=${(med { it.sync.p50 }).ms()}/${(med { it.sync.p90 }).ms()} " +
            "janky(med)=${med { it.janky.toDouble() }.fmt()} jankyOver16.7(med)=${med { it.over.toDouble() }.fmt()} " +
            "tail=${measured.map { it.tail }.distinct()} workProxy(keys/row)=${workProxy.fmt()} " +
            "distinctKeys=${proxy.keys.size} rows=${rows.fmt()} refreshHz=${h.refreshRate()}"
        Log.i(TAG, "SUMMARY $line")
    }

    private class Run(val kind: String, val frames: Long, val total: Pct, val layout: Pct, val draw: Pct, val sync: Pct,
        val janky: Int, val over: Int, val tail: String)
    private class Pct(val p50: Double, val p90: Double, val p99: Double)

    private fun measuredPass(name: String, kind: String, rep: Int, h: Harness,
        prepare: (Harness) -> Unit, act: (Harness) -> Unit): Run {
        prepare(h)
        val recorder = NavigationFrameMetricsRecorder.open(h.window(), capacity = 8192, timeoutMillis = 10_000)
        val start = NavigationFrameMetricsRecorder.markNanos()
        var end = start + 1
        var actFailure: Throwable? = null
        try { act(h) } catch (failure: Throwable) { actFailure = failure }
        end = NavigationFrameMetricsRecorder.markNanos()
        Thread.sleep(300) // Real tail time: frames stamped before `end` that are still being delivered.
        val result = try { recorder.finish(start, end) } catch (failure: Throwable) {
            runCatching { recorder.close() }
            actFailure?.addSuppressed(failure)
            throw actFailure ?: failure
        }
        actFailure?.let { throw it }
        val snapshot = checkNotNull(result.snapshot) { "Recorder returned no snapshot" }
        val phases = checkNotNull(recorder.lastPhases) { "Recorder returned no phase durations" }
        val total = ArrayList<Double>(); val layout = ArrayList<Double>(); val draw = ArrayList<Double>(); val sync = ArrayList<Double>()
        var janky = 0; var over = 0; var deadline = 0
        for (i in 0 until snapshot.storedCount) {
            val frame = snapshot[i]
            if (frame.position != NavigationFrameWindow.Position.IN_WINDOW) continue
            total += frame.totalDurationNanos.toDouble(); layout += phases.layoutMeasure[i].toDouble()
            draw += phases.draw[i].toDouble(); sync += phases.sync[i].toDouble()
            val slow = frame.totalDurationNanos > JANK_NANOS
            val missed = Build.VERSION.SDK_INT >= 31 && frame.exceedsReportedDeadline == true
            if (slow) over++
            if (missed) deadline++
            if (slow || missed) janky++
        }
        assertTrue("$name/$kind$rep produced no frames in its window (received=${snapshot.receivedCount})", total.isNotEmpty())
        val run = Run(kind, total.size.toLong(), pct(total), pct(layout), pct(draw), pct(sync), janky, over,
            snapshot.tailStatus.name)
        fun json(p: Pct) = JSONObject().put("p50Ms", p.p50 / 1e6).put("p90Ms", p.p90 / 1e6).put("p99Ms", p.p99 / 1e6)
        val doc = JSONObject().put("kind", kind).put("rep", rep)
            .put("framesReceived", snapshot.receivedCount).put("framesInWindow", total.size)
            .put("framesStored", snapshot.storedCount).put("bufferOverflow", snapshot.bufferOverflowCount)
            .put("listenerReportedDrops", snapshot.reportedListenerDropCount)
            .put("totalDuration", json(run.total)).put("layoutMeasure", json(run.layout))
            .put("draw", json(run.draw)).put("sync", json(run.sync))
            .put("janky", janky).put("jankyOver16_7ms", over)
            .put("deadlineMisses", if (Build.VERSION.SDK_INT >= 31) deadline else JSONObject.NULL)
            .put("tailStatus", snapshot.tailStatus.name)
            .put("recorderClosedCleanly", result.listenerRemovalConfirmed && result.workerStopped)
            .put("windowMs", (end - start) / 1e6)
            .put("totalDurationsMs", JSONArray(total.map { it / 1e6 }))
        writeEvidence(name, doc)
        Log.i(TAG, "RUN scenario=$name kind=$kind rep=$rep frames=${total.size}/${snapshot.receivedCount} " +
            "p50=${run.total.p50.ms()} p90=${run.total.p90.ms()} p99=${run.total.p99.ms()} janky=$janky tail=${run.tail}")
        return run
    }

    private fun writeEvidence(name: String, document: JSONObject) {
        document.put("scenario", name).put("api", Build.VERSION.SDK_INT).put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
            .put("seed", SEED).put("albums", ALBUMS).put("songs", SONGS)
            .put("clock", "System.nanoTime / FrameMetrics.VSYNC_TIMESTAMP; real clock, no Compose test clock")
            .put("artwork", "none (no artwork I/O)")
        val directory = nativeEvidenceDirectory("scroll-performance", context.getExternalFilesDir(null))
        val safe = name.replace(Regex("[^A-Za-z0-9._-]+"), "_")
        val target = File.createTempFile("$safe-${document.optString("kind")}${document.optInt("rep")}-", ".json", directory)
        target.writeText(document.toString(2) + "\n")
    }

    // ---- statistics ------------------------------------------------------------------------------

    private fun pct(values: List<Double>): Pct {
        val sorted = values.sorted()
        fun at(p: Double) = sorted[(ceil(p * sorted.size).toInt() - 1).coerceIn(0, sorted.size - 1)]
        return Pct(at(.5), at(.9), at(.99))
    }
    private fun median(values: List<Double>): Double {
        val sorted = values.sorted()
        return if (sorted.isEmpty()) Double.NaN else if (sorted.size % 2 == 1) sorted[sorted.size / 2]
            else (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2
    }
    private fun Double.ms() = "%.2f".format(this / 1e6)
    private fun Double.fmt() = if (isNaN()) "n/a" else "%.2f".format(this)

    // ---- device / semantics access ---------------------------------------------------------------

    private class ScreenBounds(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        val centerX get() = (left + right) / 2
        val centerY get() = (top + bottom) / 2
        val height get() = bottom - top
    }

    private class SamplerResult(val keys: Set<String>, val travelItems: Double, val first: Float, val last: Float)

    private class Sampler(private val owner: SemanticsOwner) : Choreographer.FrameCallback {
        @Volatile var running = true
        val keys = HashSet<String>()
        var travel = 0.0
        var first = Float.NaN
        var last = Float.NaN
        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            sample()
            Choreographer.getInstance().postFrameCallback(this)
        }
        fun sample() {
            for (node in owner.getAllSemanticsNodes(mergingEnabled = false)) {
                val tag = node.config.getOrNull(SemanticsProperties.TestTag) ?: continue
                if (tag.startsWith(SCROLL_ITEM_PREFIX)) keys += tag
                else if (tag == "browser-list") {
                    val value = node.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)?.value?.invoke() ?: continue
                    if (first.isNaN()) first = value
                    // Raw semantics scroll units; converted to items with Harness.calibrate().
                    if (!last.isNaN()) travel += abs(value - last).toDouble()
                    last = value
                }
            }
        }
    }

    private class Harness(val scenario: ActivityScenario<ComponentActivity>, val device: UiDevice, val columns: Int) {
        private fun <T> main(block: (ComponentActivity) -> T): T {
            var out: Any? = null
            scenario.onActivity { out = block(it) }
            @Suppress("UNCHECKED_CAST") return out as T
        }

        private fun root(activity: ComponentActivity): ViewRootForTest? {
            fun find(view: View): ViewRootForTest? {
                if (view is ViewRootForTest) return view
                if (view is ViewGroup) for (i in 0 until view.childCount) find(view.getChildAt(i))?.let { return it }
                return null
            }
            return find(activity.window.decorView)
        }

        private fun <T> nodes(block: (List<SemanticsNode>, View) -> T): T = main { activity ->
            val root = checkNotNull(root(activity)) { "No Compose root in the activity" }
            block(root.semanticsOwner.getAllSemanticsNodes(mergingEnabled = false), root.view)
        }

        private fun SemanticsNode.tag() = config.getOrNull(SemanticsProperties.TestTag)

        fun list(): SemanticsNode? = nodes { all, _ -> all.firstOrNull { it.tag() == "browser-list" } }
        fun itemKeys(): List<String> = nodes { all, _ -> all.mapNotNull { it.tag() }.filter { it.startsWith(SCROLL_ITEM_PREFIX) } }
        fun maxScroll(): Float = nodes { all, _ ->
            all.firstOrNull { it.tag() == "browser-list" }?.config?.getOrNull(SemanticsProperties.VerticalScrollAxisRange)?.maxValue?.invoke() ?: 0f
        }
        fun scrollValue(): Float = nodes { all, _ ->
            all.firstOrNull { it.tag() == "browser-list" }?.config?.getOrNull(SemanticsProperties.VerticalScrollAxisRange)?.value?.invoke() ?: Float.MAX_VALUE
        }
        fun window() = main { it.window }
        fun refreshRate(): Float = main {
            @Suppress("DEPRECATION") it.windowManager.defaultDisplay.refreshRate
        }

        /** Semantics scroll units per grid item, measured against a known index jump. */
        fun calibrate(): Double {
            scrollToIndex(0); settle()
            val base = scrollValue()
            scrollToIndex(1000); settle()
            val moved = scrollValue()
            scrollToIndex(0); settle()
            return ((moved - base) / 1000.0).also { check(it > 0.0) { "Cannot calibrate scroll units ($base -> $moved)" } }
        }

        fun scrollToIndex(index: Int) {
            val ok = nodes { all, _ ->
                val action = all.firstOrNull { it.tag() == "browser-list" }?.config?.getOrNull(SemanticsActions.ScrollToIndex)?.action
                action?.invoke(index) ?: false
            }
            assertTrue("ScrollToIndex($index) was not accepted by the list", ok)
        }

        fun nodeBounds(match: (SemanticsNode) -> Boolean): ScreenBounds = checkNotNull(findBounds(match)) { "Gesture target not found" }

        private fun findBounds(match: (SemanticsNode) -> Boolean): ScreenBounds? = nodes { all, view ->
            val node = all.firstOrNull(match) ?: return@nodes null
            val origin = IntArray(2).also(view::getLocationOnScreen)
            val b = node.boundsInRoot
            ScreenBounds(origin[0] + b.left, origin[1] + b.top, origin[0] + b.right, origin[1] + b.bottom)
        }

        fun listScreenBounds() = nodeBounds { it.tag() == "browser-list" }

        fun returnToTop(): ScreenBounds? = findBounds {
            it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("Return to top") == true
        }?.takeIf { it.right > it.left && it.bottom > it.top }

        fun swipe(x0: Float, y0: Float, x1: Float, y1: Float, steps: Int) {
            val before = scrollValue()
            check(device.swipe(x0.toInt(), y0.toInt(), x1.toInt(), y1.toInt(), steps)) { "UiDevice swipe was rejected" }
            Log.i("ScrollPerf", "swipe ($x0,$y0)->($x1,$y1) steps=$steps scroll $before -> ${scrollValue()}")
        }

        fun tap(x: Float, y: Float) {
            val before = scrollValue()
            check(device.click(x.toInt(), y.toInt())) { "UiDevice click was rejected" }
            Log.i("ScrollPerf", "tap ($x,$y) scroll $before -> ${scrollValue()}")
        }

        /** Waits until the list stops moving (fling or ease finished) for a quarter second. */
        fun settle(timeoutMs: Long = 20_000) {
            val deadline = System.nanoTime() + timeoutMs * 1_000_000
            var previous = Float.NaN
            var stable = 0
            val trail = ArrayList<Float>()
            while (System.nanoTime() < deadline) {
                val now = scrollValue()
                if (now == previous) { if (++stable >= 5) return } else stable = 0
                previous = now; if (trail.size < 400) trail += now
                Thread.sleep(50)
            }
            error("List did not settle within ${timeoutMs}ms; every 50ms values (first 60): ${trail.take(60)}")
        }

        fun await(what: String, timeoutMs: Long, condition: (Harness) -> Boolean) {
            val deadline = System.nanoTime() + timeoutMs * 1_000_000
            while (System.nanoTime() < deadline) {
                if (runCatching { condition(this) }.getOrDefault(false)) return
                Thread.sleep(100)
            }
            error("Timed out after ${timeoutMs}ms waiting for $what (scroll=${runCatching { scrollValue() }.getOrNull()})")
        }

        fun startSampler(): Sampler = main { activity ->
            val sampler = Sampler(checkNotNull(root(activity)) { "No Compose root" }.semanticsOwner)
            sampler.sample()
            Choreographer.getInstance().postFrameCallback(sampler)
            sampler
        }

        fun stopSampler(sampler: Sampler): SamplerResult = main {
            sampler.running = false
            sampler.sample()
            SamplerResult(sampler.keys, sampler.travel, sampler.first, sampler.last)
        }
    }
}
