package io.github.anastaci1a.localica

import android.os.Build
import androidx.activity.ComponentActivity
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.navigation.GlassAppearance
import io.github.anastaci1a.localica.navigation.GlassRendererMode
import io.github.anastaci1a.localica.ui.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Same moving scene, window and surfaces in both modes. Received frame subsets are
 * emulator timing evidence, not a physical-device smoothness acceptance claim. */
class GlassModeFrameTimingTest {
    @get:Rule val compose = localicaAndroidComposeRule<ComponentActivity>()

    @Test fun compareNormalAndNoneOnTheSameMovingScene() {
        val runtime = GlassRendererRuntime(GlassRendererMode.Normal)
        var frame by mutableIntStateOf(0)
        compose.setContent { CompositionLocalProvider(LocalGlassRendererRuntime provides runtime) { LocalicaTheme {
            CompositionLocalProvider(LocalGlassAppearance provides GlassAppearance()) {
                val source = rememberFrostedBackdrop(Unit, dark = false)
                Box(Modifier.fillMaxSize()) {
                    Canvas(Modifier.fillMaxSize().recordFrostedBackdrop(source)) {
                        drawRect(Color(0xFF718FA5))
                        repeat(40) { i ->
                            val y = ((i * 36f + frame * 9f) % size.height)
                            drawRect(if (i % 2 == 0) Color(0xFFFFCC55) else Color(0xFF7030D0),
                                Offset(0f, y), Size(size.width, 18f))
                        }
                    }
                    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceEvenly) {
                        repeat(8) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                                repeat(3) { LiquidGlassSurface(Modifier.size(64.dp, 48.dp), source,
                                    cornerRadius = 24.dp, pressFeedback = false, shadow = false) }
                            }
                        }
                    }
                }
            }
        } } }
        val phases = JSONArray()
        // Alternate order so a cold first phase does not always penalize Normal.
        for (mode in listOf(GlassRendererMode.Normal, GlassRendererMode.FlatNone,
            GlassRendererMode.FlatNone, GlassRendererMode.Normal)) {
            compose.runOnIdle { runtime.request(mode) }
            repeat(20) { compose.runOnIdle { frame++ }; compose.waitForIdle() }
            NavigationFrameMetricsRecorder.open(compose.activity.window, capacity = 1024).use { recorder ->
                val start = NavigationFrameMetricsRecorder.markNanos()
                repeat(90) { compose.runOnIdle { frame++ }; compose.waitForIdle() }
                assertTrue(recorder.awaitFrameAtOrAfter(start))
                val end = NavigationFrameMetricsRecorder.markNanos()
                val result = recorder.finish(start, end)
                val snapshot = requireNotNull(result.snapshot)
                val frames = (0 until snapshot.storedCount).map { snapshot[it] }
                    .filter { it.frameTimestampNanos in start until end }
                assertTrue("Native timing must contain actual rendered frames", frames.isNotEmpty())
                val times = frames.map { it.totalDurationNanos }.sorted()
                phases.put(JSONObject().put("mode", mode.name).put("count", times.size)
                    .put("medianNanos", times[times.size / 2]).put("p95Nanos", times[(times.size - 1) * 95 / 100])
                    .put("totalDurationNanos", JSONArray(times)))
            }
        }
        val report = JSONObject().put("api", Build.VERSION.SDK_INT).put("scope", "received native frame subset")
            .put("phases", phases)
        android.util.Log.i("GlassModeTiming", report.toString())
        val arguments = InstrumentationRegistry.getArguments()
        val directory = arguments.getString("additionalTestOutputDir")?.let(::File)
            ?: InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir("glass-mode-timing")!!
        directory.mkdirs()
        File(directory, "glass-mode-timing-api-${Build.VERSION.SDK_INT}.json").writeText(report.toString(2))
    }
}
