package io.github.anastaci1a.localica

import android.view.Window
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.browse.BrowserItem
import io.github.anastaci1a.localica.navigation.MediaKind
import io.github.anastaci1a.localica.navigation.MediaRef
import io.github.anastaci1a.localica.ui.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.sin

/** Real-time Window FrameMetrics, identical fixture on the base and candidate branches.
 * The capability probe only supplies the candidate's new full-scene boundary; the base
 * continues to use its shipped list-source overlay. No Compose test clock is involved. */
class DragStackFrameTimeTest {
    @Test fun measureThreeAndFiveSelectedRows() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val fullScene = runCatching { Class.forName("io.github.anastaci1a.localica.ui.SongDragSceneKt") }.isSuccess
        val songs = (0..11).map { BrowserItem(MediaRef(MediaKind.Song, "$it"), "Song $it", "Artist", emptyList()) }
        var hasState = false
        lateinit var state: SongDragState
        lateinit var scope: CoroutineScope
        lateinit var window: Window
        val reports = JSONArray()
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                window = activity.window
                activity.setContent { LocalicaTheme {
                    val grid = rememberLazyGridState()
                    scope = rememberCoroutineScope()
                    state = remember { SongDragState(grid, scope).also { it.items = songs } }
                    val host = remember { SongDragHost() }
                    val page = rememberFrostedBackdrop(Unit, dark = false)
                    SideEffect { hasState = true; host.state = state; host.source = page; host.reducedMotion = false }
                    val scene = if (fullScene && state.drag != null)
                        rememberFrostedBackdrop(state.drag, dark = false, retainRaster = false) else null
                    Box(Modifier.fillMaxSize().onSizeChanged {
                        state.viewportWidth = it.width; state.viewportHeight = it.height
                    }) {
                        Box(Modifier.fillMaxSize().then(if (scene != null) Modifier.recordFrostedBackdrop(scene) else Modifier)) {
                            Canvas(Modifier.fillMaxSize().recordFrostedBackdrop(page)) {
                                drawRect(Color.White)
                                val step = 20.dp.toPx()
                                var x = 0f
                                while (x < size.width) {
                                    drawRect(if ((x / step).toInt() % 2 == 0) Color(.95f, .8f, .65f) else Color(.7f, .85f, .95f),
                                        Offset(x, 0f), Size(step, size.height))
                                    x += step
                                }
                            }
                            LazyVerticalGrid(GridCells.Fixed(1), state = grid) {
                                items(state.rendered(songs), key = { it?.key ?: DropGapKey }) { song ->
                                    Box(Modifier.fillMaxWidth().height(64.dp).padding(12.dp)) { Text(song?.title.orEmpty()) }
                                }
                            }
                        }
                        CompositionLocalProvider(LocalFrostedBackdrop provides scene) { ForegroundSongDragOverlay(host) }
                    }
                } }
            }
            val readyUntil = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
            var ready = false
            while (!ready && System.nanoTime() < readyUntil) {
                scenario.onActivity { ready = hasState && state.bounds().size >= 5 && state.viewportHeight > 0 }
                if (!ready) Thread.sleep(16)
            }
            assertTrue("Five fixture rows are laid out", ready)
            fun motion(frames: Int) {
                val completed = CountDownLatch(1)
                scenario.onActivity {
                    scope.launch {
                        try {
                            repeat(frames) { frame ->
                                withFrameNanos { }
                                val phase = frame * .09f
                                state.move(Offset(state.viewportWidth * (.5f + .12f * sin(phase)),
                                    state.viewportHeight * (.48f + .12f * sin(phase * .7f))))
                            }
                        } finally { completed.countDown() }
                    }
                }
                assertTrue("Real-time drag completes", completed.await(30, TimeUnit.SECONDS))
            }
            for (count in listOf(3, 5)) {
                scenario.onActivity {
                    assertTrue(state.begin(songs.first(), songs.take(count).map { it.key }.toSet(),
                        state.bounds().getValue(songs.first().key).center))
                }
                motion(60)
                for (rep in 1..3) {
                    val recorder = NavigationFrameMetricsRecorder.open(window, capacity = 2048, timeoutMillis = 10000)
                    val start = NavigationFrameMetricsRecorder.markNanos()
                    motion(180)
                    val end = NavigationFrameMetricsRecorder.markNanos()
                    Thread.sleep(300)
                    val result = recorder.finish(start, end)
                    val snapshot = requireNotNull(result.snapshot)
                    val phases = requireNotNull(recorder.lastPhases)
                    val totals = mutableListOf<Double>()
                    val draws = mutableListOf<Double>()
                    for (index in 0 until snapshot.storedCount) {
                        val frame = snapshot[index]
                        if (frame.position == NavigationFrameWindow.Position.IN_WINDOW) {
                            totals += frame.totalDurationNanos / 1e6
                            draws += phases.draw[index] / 1e6
                        }
                    }
                    assertTrue("Measured drag frames exist", totals.size >= 60)
                    fun percentile(values: List<Double>, p: Double) = values.sorted()[((values.size - 1) * p).toInt()]
                    val report = JSONObject().put("selected", count).put("rep", rep).put("frames", totals.size)
                        .put("totalMeanMs", totals.average()).put("totalP50Ms", percentile(totals, .5))
                        .put("totalP90Ms", percentile(totals, .9)).put("drawMeanMs", draws.average())
                        .put("drawP50Ms", percentile(draws, .5)).put("drawP90Ms", percentile(draws, .9))
                        .put("tail", snapshot.tailStatus.name).put("rawTotalMs", JSONArray(totals)).put("rawDrawMs", JSONArray(draws))
                    reports.put(report)
                    android.util.Log.i("DragStackFrameTime", report.toString())
                }
                scenario.onActivity { state.finish(cancel = true, reducedMotion = true) { _, _, _ -> fail("Cancel cannot save") } }
                val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                var ended = false
                while (!ended && System.nanoTime() < until) {
                    scenario.onActivity { ended = state.drag == null }
                    if (!ended) Thread.sleep(16)
                }
                assertTrue("Drag ends before the next sample", ended)
            }
        }
        val args = InstrumentationRegistry.getArguments()
        val root = args.getString("additionalTestOutputDir")?.let(::File)
            ?: requireNotNull(instrumentation.targetContext.getExternalFilesDir(null))
        val output = File(root, "drag-stack-557").apply { mkdirs() }
        File(output, "frame-time-api${android.os.Build.VERSION.SDK_INT}.json").writeText(
            JSONObject().put("api", android.os.Build.VERSION.SDK_INT).put("fullSceneBoundary", fullScene)
                .put("warmupFrames", 60).put("framesPerRep", 180).put("runs", reports).toString(2))
    }
}
