package io.github.anastaci1a.localica

import android.os.Build
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import io.github.anastaci1a.localica.lyrics.*
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import io.github.anastaci1a.localica.browse.*
import io.github.anastaci1a.localica.diagnostics.FrameStats
import io.github.anastaci1a.localica.home.PinPreferences
import io.github.anastaci1a.localica.library.*
import io.github.anastaci1a.localica.navigation.*
import io.github.anastaci1a.localica.playback.PlaybackState
import io.github.anastaci1a.localica.playlist.PlaylistState
import io.github.anastaci1a.localica.queue.*
import io.github.anastaci1a.localica.ui.*
import org.json.JSONObject
import org.junit.Test
import org.junit.Assume.assumeTrue
import java.io.File
import java.util.Random

/** Opt-in real-clock UI measurements. No Compose rule, virtual time, audio decoding or media I/O. */
class UiPerformanceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private val args = InstrumentationRegistry.getArguments()
    private val seed = 600L
    private val tracks by lazy {
        val random = Random(seed)
        List(2000) { n -> Track("perf:$n", "Song ${n.toString().padStart(4, '0')}",
            artist = "Artist ${n / 100}", albumArtist = "Artist ${n / 100}", album = "Album ${n / 20}",
            trackNumber = n % 20 + 1, year = 1980 + random.nextInt(40), durationMs = 180_000,
            fileName = "$n.mp3", relativePath = "fixture/$n.mp3") }
    }
    private val library by lazy { LibraryUiState(LibrarySnapshot(
        folders = listOf(FolderSelection("fixture", "Fixture")), tracks = tracks), loading = false) }
    private val queue by lazy { ListeningQueue(id = "fixture", name = "Fixture", createdAt = 0,
        entries = tracks.mapIndexed { n, track -> QueueEntry("entry:$n", track) }) }
    private val glassMode = GlassRendererMode.FlatNone
    private var effectiveMode = "unobserved"
    private val events = mutableListOf<JSONObject>()
    private val windows = mutableSetOf<Window>() // main thread only
    private var scenario: ActivityScenario<ComponentActivity>? = null
    private var activity: ComponentActivity? = null
    private var tone by mutableFloatStateOf(.4f)
    private var lyrics by mutableStateOf(LyricsResult())

    @Test fun performanceRendererUsesExplicitFlatNone() {
        org.junit.Assert.assertEquals(GlassRendererMode.FlatNone, glassMode)
    }

    @Test fun measurePureUiScenarios() {
        assumeTrue("Pass uiPerformance=true on an owned disposable test device", args.getString("uiPerformance") == "true")
        val repetitions = (args.getString("uiPerformanceRepetitions")?.toInt() ?: 3).also { require(it in 1..10) }
        val protocolLegacy = args.getString("uiPerformanceProtocolLegacy") == "true"
        check(!protocolLegacy || args.getString("uiPerformanceSource") == "dba7f1203be022b60060a8c602224f862630405e")
        val warmup = args.getString("uiPerformanceWarmup") != "false"
        val firstRep = args.getString("uiPerformanceStartRep")?.toInt() ?: 0
        check(if (warmup) firstRep == 0 else firstRep in 1..5)
        val filter = Regex(args.getString("uiPerformanceScenario") ?: ".*")
        // Preserve developer/device preferences even when a measurement throws.
        val stores = listOf("interface", "browse-style", "library-query", "pins")
            .associateWith { context.getSharedPreferences(it, 0).all.toMap() }
        try {
            context.getSharedPreferences("interface", 0).edit().clear()
                .putString("tabs", "Songs,Albums,Home").putString("glass-renderer", glassMode.name)
                .putFloat("glass-blur", 0f).putFloat("tab-lens-blur", 0f).putBoolean("glass-live-scroll", false).commit()
            PinPreferences(context).write(tracks.take(30).map { MediaRef(MediaKind.Song, it.id) })
            var measuredScenarios = 0
            fun measure(name: String, launch: () -> Unit, prepare: () -> Unit = {}, action: () -> Unit) {
                if (!filter.matches(name)) return
                measuredScenarios++
                repeat(repetitions + if (warmup) 1 else 0) { index ->
                    val rep = firstRep + index
                    try {
                        // Every repetition, including isolated trace passes, starts from the same styles.
                        listOf("browse-style", "library-query").forEach {
                            check(context.getSharedPreferences(it, 0).edit().clear().commit())
                        }
                        launch(); prepare(); awaitStableScene(); events.clear()
                        main { activity ->
                            check(io.github.anastaci1a.localica.navigation.InterfacePreferences(context).read().glass.renderer == GlassRendererMode.FlatNone)
                            check(effectiveMode == "FlatNone") { "Effective scene backend: $effectiveMode" }
                            if (!protocolLegacy) FrameStats.setScenarioTag(name)
                            FrameStats.start(activity)
                            windows.forEach { FrameStats.attach(it) }
                        }
                        val cookie = name.hashCode()
                        if (Build.VERSION.SDK_INT >= 29) android.os.Trace.beginAsyncSection("ui600:$name:$rep", cookie)
                        else android.os.Trace.beginSection("ui600:$name:$rep")
                        try { action(); Thread.sleep(500) } finally {
                            if (Build.VERSION.SDK_INT >= 29) android.os.Trace.endAsyncSection("ui600:$name:$rep", cookie)
                            else android.os.Trace.endSection()
                            main { FrameStats.stop() }
                        }
                        Thread.sleep(300)
                        val report = JSONObject(FrameStats.report()).put("scenario", name).put("rep", rep)
                            .put("warmup", warmup && rep == 0).put("protocolLegacy", protocolLegacy).put("seed", seed).put("songs", tracks.size)
                            .put("api", Build.VERSION.SDK_INT).put("glassMode", glassMode.name)
                            .put("glassFallback", false).put("requestedGlass", "FlatNone").put("observedSceneBackend", effectiveMode)
                            .put("scriptVersion", "stage0-flat-none-v1").put("actions", org.json.JSONArray(events))
                            .put("artwork", "placeholders; no media I/O")
                            .put("source", args.getString("uiPerformanceSource") ?: "unspecified")
                        check(report.getString("effectiveGlass") == "FlatNone")
                        if (!protocolLegacy) check(report.getString("source") == BuildConfig.SOURCE_SHA && report.getString("buildSource") == BuildConfig.SOURCE_SHA)
                        check(!report.getBoolean("windowLimitReached"))
                        check(!report.getBoolean("timeLimitReached")) { "$name exceeded the capture time limit" }
                        val frames = report.getJSONArray("windows")
                        check((0 until frames.length()).all { frames.getJSONObject(it).getInt("frames") > 0 }) { "$name has an empty window" }
                        if (name in setOf("popup-open-close", "back-during-popup-retract"))
                            check(frames.length() >= 2) { "$name missing modal window" }
                        val directory = nativeEvidenceDirectory("ui-performance", context.getExternalFilesDir(null))
                        File(directory, "$name-$rep-api${Build.VERSION.SDK_INT}.json").writeText(report.toString(2))
                    } finally {
                        if (activity != null) main { FrameStats.stop(); it.setContent {} }
                        scenario?.close(); scenario = null; activity = null; windows.clear()
                    }
                }
            }
            for (size in ListSize.densityOrder) measure("list-${size.name.lowercase()}",
                { launchBrowse(BrowseStyle(listSize = size), if (size == ListSize.Tracklist)
                    BrowseRoute(MainTab.Queues, MediaRef(MediaKind.Queue, queue.id), queue.name) else BrowseRoute(MainTab.Songs)) }) { scroll() }
            for (columns in 1..5) measure("gallery-$columns", { launchBrowse(BrowseStyle(
                presentation = Presentation.Standard, itemsPerRow = columns)) }) { scroll() }
            measure("subview-scroll", {
                val album = LibraryViews.groups(library.snapshot, SongListView.Albums, LibraryQuery()).first()
                launchBrowse(BrowseStyle(listSize = ListSize.Large),
                    BrowseRoute(MainTab.Albums, MediaRef(MediaKind.Album, album.id), album.title))
            }) { scroll() }
            measure("carousel", { launchApp(home = true) }) {
                val b = bounds { it.tag() == "home-pins" }
                repeat(4) { check(swipe((b.right - 30).toInt(), b.center.y.toInt(), (b.left + 30).toInt(), b.center.y.toInt(), 30)); Thread.sleep(300) }
            }
            measure("artist-album-carousel", {
                val artist = LibraryViews.groups(library.snapshot, SongListView.Artists, LibraryQuery()).first()
                launchBrowse(BrowseStyle(), BrowseRoute(MainTab.Artists, MediaRef(MediaKind.Artist, artist.id), artist.title))
            }) {
                val b = bounds { (it.config.getOrNull(SemanticsProperties.HorizontalScrollAxisRange)?.maxValue?.invoke() ?: 0f) > 0f }
                repeat(3) { check(swipe((b.right - 30).toInt(), b.center.y.toInt(), (b.left + 30).toInt(), b.center.y.toInt(), 30)); Thread.sleep(400) }
            }
            measure("queue-drawer", { launchApp() }, { expand() }) {
                clickDescription("Open queue"); awaitTag("browser-list"); scroll(); clickDescription("Minimize queue")
            }
            measure("player-expand-collapse", { launchApp() }) {
                repeat(3) { expand(); clickDescription("Player handle"); Thread.sleep(600) }
            }
            measure("popup-open-close", { launchApp() }) {
                repeat(3) { clickDescription("More"); Thread.sleep(400); pressBack(); Thread.sleep(500) }
            }
            measure("lyrics-open-close", { launchApp() }, {
                expand(); clickDescription("Show lyrics"); Thread.sleep(500); clickDescription("Hide lyrics")
            }) {
                repeat(3) { clickDescription("Show lyrics"); Thread.sleep(500); clickDescription("Hide lyrics") }
            }
            measure("lyrics-content-theme", {
                lyrics = LyricsResult(listOf(LyricSource("fixture", "Fixture", LrcParser.parse(
                    List(100) { "Synthetic lyric line $it" }.joinToString("\n")))))
                launch { LyricsPanel(null, 0, false, {}, Modifier.fillMaxSize(), result = lyrics) }
                awaitTag("lyrics-panel")
            }) {
                repeat(6) { change -> main {
                    tone = if (change % 2 == 0) 1f else 0f
                    lyrics = if (change % 2 == 0) LyricsResult() else LyricsResult(listOf(LyricSource(
                        "fixture", "Fixture", LrcParser.parse(List(100) { "Synthetic lyric line $it" }.joinToString("\n")))))
                }; Thread.sleep(600) }
            }
            measure("search-typing", { launchSearch() }, { awaitEditor() }) {
                for (text in listOf("S", "So", "Song", "Song 01", "Song 019", "")) {
                    val accepted = nodes { all -> all.lastOrNull { it.boundsInWindow.width > 0 && it.boundsInWindow.height > 0 && it.config.getOrNull(SemanticsActions.SetText)?.action != null }
                        ?.config?.getOrNull(SemanticsActions.SetText)?.action
                        ?.invoke(androidx.compose.ui.text.AnnotatedString(text)) ?: false }
                    check(accepted) { "Search editor rejected text" }; milestone("set-text-length:${text.length}"); Thread.sleep(180)
                }
            }
            measure("pinch-sizes", { launchBrowse(BrowseStyle(listSize = ListSize.Smallest)) }) {
                repeat(9) { pinch(expand = true); Thread.sleep(500) }
                repeat(9) { pinch(expand = false); Thread.sleep(500) }
            }
            measure("multi-select-drag", { launchApp() }, { expand(); clickDescription("Open queue") }) {
                val selected = nodes { all -> all.filter { it.tag()?.startsWith("media-entry:") == true }.take(3)
                    .map { it.tag() to (it.config.getOrNull(SemanticsActions.OnLongClick)?.action?.invoke() ?: false) } }
                check(selected.size == 3 && selected.all { it.second }) { "Three selectable rows required" }
                main { check(androidx.lifecycle.ViewModelProvider(it)[SessionSelection::class.java].entries.size == 3) }
                val b = bounds { it.tag() == selected.first().first }
                drag(b.center.x, b.center.y, b.center.y + 230)
                pressBack()
            }
            measure("interrupted-player-drag", { launchApp() }) {
                expand()
                val b = bounds { it.tag() == "player-handle" }
                val before = b.top
                var outbound = before
                pointerPath(b.center.x, b.center.y + 50, listOf(b.center.y + 270, b.center.y + 130), afterSegment = { segment ->
                    if (segment == 0) {
                        awaitCondition("Player did not follow outward contact") { bounds { it.tag() == "player-handle" }.top > before + 10 }
                        outbound = bounds { it.tag() == "player-handle" }.top
                    }
                }) {
                    awaitCondition("Player did not follow interrupted drag; before=$b") { bounds { it.tag() == "player-handle" }.top.let { it > before + 10 && it < outbound - 10 } }
                    milestone("player-drag-reversed")
                }
                awaitStableScene()
            }
            measure("back-during-popup-retract", { launchApp(tabs = "Songs,Albums") }, { clickDescription("Albums") }) {
                check(nodes { all -> all.any { it.tag() == "page-heading" && it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == "Albums" } == true } })
                milestone("back-start-albums")
                clickDescription("More"); awaitTag("glass-morph-surface")
                lateinit var returning: Window
                main { owner -> returning = windows.single { it !== owner.window && it.decorView.isAttachedToWindow } }
                injectBack(); milestone("first-back-dismiss")
                awaitCondition("Returning popup did not release input before retirement") {
                    var released = false
                    main { released = returning.decorView.isAttachedToWindow &&
                        returning.attributes.flags and android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0 }
                    released
                }
                injectBack(); milestone("second-back-during-retained-window")
                awaitCondition("Returning popup did not retire") {
                    var attached = true; main { attached = returning.decorView.isAttachedToWindow }; !attached
                }
                awaitStableScene()
                check(nodes { all -> all.any { it.tag() == "page-heading" &&
                    it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == "Songs" } == true } }) { "Back destination did not receive input" }
                milestone("back-destination-songs")
            }
            measure("queue-pull-during-player-collapse", { launchApp() }, { expand() }) {
                val b = bounds { it.tag() == "player-handle" }
                val origin = b.top
                var collapsed = origin
                // Current behaviour: Player owns the upward pull interrupting collapse.
                // Stage 2 (U10) decides whether a Queue takeover should exist.
                pointerPath(b.center.x, b.center.y + 50, listOf(b.center.y + 270, b.center.y + 90), afterSegment = { segment ->
                    if (segment == 0) {
                        awaitCondition("Collapse never moved the player; before=$b") { bounds { it.tag() == "player-handle" }.top > origin + 10 }
                        collapsed = bounds { it.tag() == "player-handle" }.top
                        milestone("player-collapse-contact")
                    }
                }) {
                    awaitCondition("Player did not reverse upward") { bounds { it.tag() == "player-handle" }.top < collapsed - 10 }
                    milestone("upward-pull-routed-to-player")
                }
                awaitStableScene()
            }
            measure("drag-start-end-in-list", { launchApp() }, { expand(); clickDescription("Open queue") }) {
                val tag = nodes { all -> all.first { it.tag()?.startsWith("media-entry:") == true }.tag()!! }
                check(nodes { all -> all.first { it.tag() == tag }.config.getOrNull(SemanticsActions.OnLongClick)?.action?.invoke() == true })
                main { check(androidx.lifecycle.ViewModelProvider(it)[SessionSelection::class.java].entries.size == 1) }
                val b = bounds { it.tag() == tag }
                drag(b.center.x, b.center.y, b.center.y + 160)
                awaitAbsent("drag-stack"); milestone("list-drag-retired")
            }
            check(measuredScenarios > 0) { "Scenario filter matched nothing: $filter" }
        } finally {
            stores.forEach { (name, values) ->
                val edit = context.getSharedPreferences(name, 0).edit().clear()
                values.forEach { (key, value) -> when (value) {
                    is String -> edit.putString(key, value); is Boolean -> edit.putBoolean(key, value)
                    is Int -> edit.putInt(key, value); is Long -> edit.putLong(key, value); is Float -> edit.putFloat(key, value)
                    is Set<*> -> { @Suppress("UNCHECKED_CAST") edit.putStringSet(key, value as Set<String>) }
                } }; edit.commit()
            }
        }
    }

    private fun launchBrowse(style: BrowseStyle, route: BrowseRoute = BrowseRoute(MainTab.Songs)) {
        BrowsePreferences(context).write(route, style, false)
        check(BrowsePreferences(context).read(route).listSize == style.listSize) { "Requested list size is unsupported by fixture route" }
        launch { BrowserScreen(library, PlaylistState(), route, {}, {}, {}, { _, _, _ -> }, null, null, true, false,
            queueState = QueueUiState(QueueLibrary(listOf(queue), queue.id), loading = false)) }
        awaitTag("browser-list")
    }
    private fun launchSearch() {
        BrowsePreferences(context).write(BrowseRoute(MainTab.Songs), BrowseStyle(), false)
        launch {
            var route by remember { mutableStateOf(BrowseRoute(MainTab.Songs, search = true, globalSearch = true)) }
            BrowserScreen(library, PlaylistState(), route, { route = it }, {}, {}, { _, _, _ -> }, null, null, true, false)
        }
    }
    private fun launchApp(home: Boolean = false, tabs: String? = null) {
        context.getSharedPreferences("interface", 0).edit().putString("tabs", tabs ?: if (home) "Home" else "Songs").commit()
        BrowsePreferences(context).write(BrowseRoute(MainTab.Songs), BrowseStyle(), false)
        launch { LocalicaScreen(library, PlaybackState(connected = true, trackId = tracks[0].id,
            trackUri = "fixture", title = tracks[0].title, durationMs = 180_000), {}, {}, {}, {}, {}, {},
            queues = QueueUiState(QueueLibrary(listOf(queue), queue.id), loading = false)) }
        awaitTag(if (home) "home-pins" else "browser-list")
    }
    private fun launch(content: @Composable () -> Unit) {
        tone = .4f
        scenario = ActivityScenario.launch(ComponentActivity::class.java)
        requireNotNull(scenario).onActivity { activity = it }
        main { activity ->
            windows += activity.window
            activity.setContent {
                val runtime = remember { GlassRendererRuntime(glassMode) }
                SideEffect { effectiveMode = runtime.generation.backend.name }
                CompositionLocalProvider(LocalGlassRendererRuntime provides runtime,
                    LocalGlassMorphWorkObserver provides { _, window, _ ->
                        if (window != null) {
                            check(runtime.requested == GlassRendererMode.FlatNone && runtime.generation.backend == GlassBackend.FlatNone)
                            windows += window; FrameStats.attach(window)
                        }
                    }, LocalBrowseObscuredInset provides 96.dp) { LocalicaTheme(tone, content) }
            }
        }
    }
    // ActivityScenario.onActivity waits for global idle, which search/loading animations need not reach.
    private fun main(block: (ComponentActivity) -> Unit) {
        val task = java.util.concurrent.FutureTask { block(requireNotNull(activity)) }
        check(android.os.Handler(android.os.Looper.getMainLooper()).post(task))
        try { task.get(30, java.util.concurrent.TimeUnit.SECONDS) }
        catch (failure: java.util.concurrent.ExecutionException) { throw requireNotNull(failure.cause) }
    }
    private fun SemanticsNode.tag() = config.getOrNull(SemanticsProperties.TestTag)
    private fun <T> nodes(block: (List<SemanticsNode>) -> T): T {
        var result: T? = null
        main {
            fun roots(view: View): List<ViewRootForTest> = if (view is ViewRootForTest) listOf(view)
                else if (view is ViewGroup) (0 until view.childCount).flatMap { roots(view.getChildAt(it)) } else emptyList()
            result = block(windows.filter { it.decorView.isAttachedToWindow }.flatMap { roots(it.decorView) }
                .flatMap { it.semanticsOwner.getAllSemanticsNodes(mergingEnabled = false) })
        }
        @Suppress("UNCHECKED_CAST") return result as T
    }
    private fun awaitEditor() {
        val until = SystemClock.uptimeMillis() + 15_000
        while (SystemClock.uptimeMillis() < until) {
            if (nodes { all -> all.any { it.config.getOrNull(SemanticsActions.SetText)?.action != null && it.boundsInWindow.height > 0 } }) return
            Thread.sleep(50)
        }
        error("Search editor missing")
    }
    private fun awaitTag(tag: String) {
        val until = SystemClock.uptimeMillis() + 30_000
        while (SystemClock.uptimeMillis() < until) {
            if (nodes { all -> all.any { it.tag() == tag && it.boundsInWindow.height > 0 } }) return
            Thread.sleep(50)
        }
        error("Missing $tag")
    }
    private fun click(match: (SemanticsNode) -> Boolean) {
        val until = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < until) {
            val clicked = nodes { all -> all.asReversed().firstNotNullOfOrNull { candidate ->
                if (!match(candidate) || candidate.boundsInWindow.width <= 0 || candidate.boundsInWindow.height <= 0) null
                else {
                    var node: SemanticsNode? = candidate
                    while (node != null && node.config.getOrNull(SemanticsActions.OnClick)?.action == null) node = node.parent
                    node?.config?.getOrNull(SemanticsActions.OnClick)?.action
                }
            }?.invoke() ?: false }
            if (clicked) { milestone("click"); Thread.sleep(650); return }
            Thread.sleep(50)
        }
        error("Click target missing; visible semantics=" + nodes { all -> all.map { it.config.toString() }.joinToString("; ") })
    }
    private fun clickDescription(text: String) { milestone("click-description:$text"); click { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(text) == true } }
    private fun bounds(match: (SemanticsNode) -> Boolean): Rect = nodes { all -> checkNotNull(all.lastOrNull { match(it) && it.boundsInWindow.height > 0 }).boundsInWindow }
    private fun expand() { click { it.config.getOrNull(SemanticsActions.OnClick)?.label == "Expand player" }; Thread.sleep(600) }
    private fun scroll() {
        fun offset() = nodes { all -> all.last { it.tag() == "browser-list" }
            .config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)?.value?.invoke() ?: error("Missing scroll axis") }
        val before = offset()
        val b = bounds { it.tag() == "browser-list" }
        repeat(3) {
            events += JSONObject().put("action", "scroll-swipe").put("x", b.center.x).put("fromY", b.top + b.height * .75).put("toY", b.top + b.height * .25).put("steps", 30).put("uptimeMs", SystemClock.uptimeMillis())
            check(swipe(b.center.x.toInt(), (b.top + b.height * .75).toInt(), b.center.x.toInt(), (b.top + b.height * .25).toInt(), 30))
            Thread.sleep(450)
        }
        check(offset() > before) { "Scroll gesture did not move content" }
    }
    private fun drag(x: Float, y: Float, end: Float) {
        val down = SystemClock.uptimeMillis()
        fun send(action: Int, at: Float) { val e = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, at, 0)
            e.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
            check(instrumentation.uiAutomation.injectInputEvent(e, true)); e.recycle()
            events += JSONObject().put("pointerAction", action).put("x", x).put("y", at).put("uptimeMs", SystemClock.uptimeMillis()) }
        send(MotionEvent.ACTION_DOWN, y); Thread.sleep(650)
        repeat(60) { frame ->
            send(MotionEvent.ACTION_MOVE, y + (end - y) * frame / 59); Thread.sleep(16)
            if (frame == 30) check(nodes { all -> all.any { it.tag() == "drag-stack" } }) { "Drag overlay did not appear" }
        }
        send(MotionEvent.ACTION_UP, end)
    }
    private fun injectBack() {
        val down = SystemClock.uptimeMillis()
        for (action in listOf(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.ACTION_UP)) {
            val event = android.view.KeyEvent(down, SystemClock.uptimeMillis(), action, android.view.KeyEvent.KEYCODE_BACK, 0)
            event.source = android.view.InputDevice.SOURCE_KEYBOARD
            check(instrumentation.uiAutomation.injectInputEvent(event, true))
            events += JSONObject().put("keyAction", action).put("keyCode", android.view.KeyEvent.KEYCODE_BACK).put("uptimeMs", event.eventTime)
        }
    }
    private fun pressBack() { injectBack(); milestone("press-back") }
    private fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, steps: Int): Boolean {
        events += JSONObject().put("action", "swipe").put("fromX", x1).put("fromY", y1).put("toX", x2).put("toY", y2)
            .put("steps", steps).put("uptimeMs", SystemClock.uptimeMillis())
        return device.swipe(x1, y1, x2, y2, steps)
    }
    private fun milestone(name: String) { events += JSONObject().put("milestone", name).put("uptimeMs", SystemClock.uptimeMillis()) }
    private fun awaitCondition(label: String, condition: () -> Boolean) {
        val until = SystemClock.uptimeMillis() + 5_000
        while (SystemClock.uptimeMillis() < until) {
            if (condition()) return
            Thread.sleep(25)
        }
        error(label)
    }
    private fun awaitStableScene() {
        // Stable semantic geometry is a readiness milestone; the legacy 800ms pacing remains a floor.
        Thread.sleep(800)
        val until = SystemClock.uptimeMillis() + 15_000
        var previous = ""; var stable = 0
        while (SystemClock.uptimeMillis() < until) {
            val geometry = nodes { all -> all.filter { it.tag() != null }.joinToString { it.tag() + it.boundsInWindow.toString() } }
            stable = if (geometry == previous && geometry.isNotEmpty()) stable + 1 else 0
            if (stable >= 3) { milestone("semantic-geometry-settled"); return }
            previous = geometry; Thread.sleep(50)
        }
        error("Scene geometry did not settle")
    }
    private fun awaitAbsent(tag: String) {
        val until = SystemClock.uptimeMillis() + 15_000
        while (SystemClock.uptimeMillis() < until) {
            if (nodes { all -> all.none { it.tag() == tag } }) return
            Thread.sleep(25)
        }
        error("Still present: $tag")
    }
    private fun pointerPath(x: Float, y: Float, destinations: List<Float>, afterSegment: (Int) -> Unit = {}, verify: () -> Unit) {
        val down = SystemClock.uptimeMillis()
        fun send(action: Int, at: Float) {
            val now = SystemClock.uptimeMillis()
            val e = MotionEvent.obtain(down, now, action, x, at, 0)
            e.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
            check(instrumentation.uiAutomation.injectInputEvent(e, true)); e.recycle()
            events += JSONObject().put("pointerAction", action).put("x", x).put("y", at).put("uptimeMs", now)
        }
        send(MotionEvent.ACTION_DOWN, y)
        var start = y
        destinations.forEachIndexed { index, to ->
            repeat(12) { step -> send(MotionEvent.ACTION_MOVE, start + (to - start) * (step + 1) / 12); Thread.sleep(16) }
            afterSegment(index); start = to
        }
        verify(); send(MotionEvent.ACTION_UP, start); milestone("pointer-release")
    }
    private fun pinch(expand: Boolean) {
        val b = bounds { it.tag() == "browser-list" }
        val down = SystemClock.uptimeMillis()
        fun send(action: Int, spread: Float, count: Int = 2) {
            val props = Array(count) { n -> MotionEvent.PointerProperties().apply { id = n; toolType = MotionEvent.TOOL_TYPE_FINGER } }
            val coords = Array(count) { n -> MotionEvent.PointerCoords().apply {
                x = b.center.x + (if (n == 0) -1 else 1) * spread; y = b.center.y; pressure = 1f; size = 1f } }
            val e = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, count, props, coords, 0, 0, 1f, 1f, 0, 0,
                android.view.InputDevice.SOURCE_TOUCHSCREEN, 0)
            check(instrumentation.uiAutomation.injectInputEvent(e, true)); e.recycle()
            events += JSONObject().put("pointerAction", action).put("pointerCount", count).put("centerX", b.center.x)
                .put("y", b.center.y).put("spread", spread).put("uptimeMs", SystemClock.uptimeMillis())
        }
        val small = b.width * .08f; val large = b.width * .38f
        val from = if (expand) small else large; val to = if (expand) large else small
        send(MotionEvent.ACTION_DOWN, from, 1)
        send(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), from)
        repeat(40) { send(MotionEvent.ACTION_MOVE, from + (to - from) * it / 39); Thread.sleep(16) }
        send(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), to)
        send(MotionEvent.ACTION_UP, to, 1)
    }
}
