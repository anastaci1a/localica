package io.github.anastaci1a.localica

import android.graphics.Color
import android.view.Choreographer
import android.view.View
import androidx.activity.ComponentActivity
import androidx.test.core.app.ActivityScenario
import io.github.anastaci1a.localica.diagnostics.DiagnosticsExport
import io.github.anastaci1a.localica.diagnostics.FrameStats
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class FrameStatsTest {
    @Test fun captureIsOptInExportsRealFramesAndStopsWithItsOwner() {
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            val finished = CountDownLatch(1)
            scenario.onActivity { activity ->
                assertFalse(FrameStats.recording.value)
                val view = View(activity)
                activity.setContentView(view)
                view.post {
                    FrameStats.start(activity)
                    var frame = 0
                    val callback = object : Choreographer.FrameCallback {
                        override fun doFrame(frameTimeNanos: Long) {
                            view.setBackgroundColor(if (frame++ % 2 == 0) Color.RED else Color.BLUE)
                            if (frame < 30) Choreographer.getInstance().postFrameCallback(this)
                            else finished.countDown()
                        }
                    }
                    Choreographer.getInstance().postFrameCallback(callback)
                }
            }
            assertTrue("Real display frames arrived", finished.await(20, TimeUnit.SECONDS))
            Thread.sleep(300)
            scenario.onActivity { activity -> FrameStats.stopFor(activity); assertFalse(FrameStats.recording.value) }
            Thread.sleep(300)
            val report = JSONObject(FrameStats.report())
            val window = report.getJSONArray("windows").getJSONObject(0)
            assertTrue(window.getInt("frames") >= 10)
            assertFalse(window.isNull("p50Ms"))
            assertEquals(0L, window.getLong("invalid"))
            scenario.onActivity { activity ->
                val file = DiagnosticsExport.write(activity)
                assertTrue(file.readText().contains("estimatedMissedVsyncs"))
                assertTrue(file.length() < 100_000)
                FrameStats.start(activity)
                FrameStats.stopFor(activity)
                assertFalse(FrameStats.recording.value)
            }
        }
    }
}
