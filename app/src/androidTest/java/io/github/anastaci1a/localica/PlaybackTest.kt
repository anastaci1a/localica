package io.github.anastaci1a.localica

import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.library.Track
import io.github.anastaci1a.localica.playback.PlaybackConnection
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class PlaybackTest {
    @Test fun playsCommonFormatsPausesSeeksAndStopsAtEnd() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use { scenario ->
            lateinit var playback: PlaybackConnection
            instrumentation.runOnMainSync { playback = PlaybackConnection(context) }
            try {
                await { playback.state.value.connected }
                for (extension in listOf("mp3", "m4a", "wav")) {
                    val file = File(context.cacheDir, "test-tone.$extension")
                    instrumentation.context.assets.open("tone.$extension").use { input -> file.outputStream().use(input::copyTo) }
                    val track = Track(Uri.fromFile(file).toString(), "Tone $extension", fileName = file.name, relativePath = file.name)
                    instrumentation.runOnMainSync { playback.play(track) }
                    await { playback.state.value.isPlaying && playback.state.value.durationMs != null }
                    assertNull(playback.state.value.problem)
                    instrumentation.runOnMainSync { playback.toggle() }
                    await { !playback.state.value.isPlaying }
                    instrumentation.runOnMainSync { playback.seekTo(500) }
                    await { playback.state.value.positionMs in 450..600 }
                    assertFalse(playback.state.value.isPlaying)
                    instrumentation.runOnMainSync { playback.toggle() }
                    await { playback.state.value.isPlaying }
                    // Leaving the activity must not release the service's player.
                    scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
                    await { playback.state.value.ended }
                    assertFalse(playback.state.value.isPlaying)
                    assertEquals(track.uri, playback.state.value.trackUri)
                    SystemClock.sleep(250)
                    assertTrue(playback.state.value.ended)
                    scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
                    instrumentation.runOnMainSync { playback.toggle() }
                    await { playback.state.value.isPlaying && !playback.state.value.ended }
                    instrumentation.runOnMainSync { playback.clear() }
                    await { playback.state.value.trackUri == null }
                    file.delete()
                }
                val missing = Track("file:///missing.mp3", "Missing", fileName = "missing.mp3", relativePath = "missing.mp3")
                instrumentation.runOnMainSync { playback.play(missing) }
                await { playback.state.value.problem != null }
                assertFalse(playback.state.value.isPlaying)
            } finally {
                instrumentation.runOnMainSync { playback.clear(); playback.close() }
            }
        }
    }
}

internal fun await(timeoutMs: Long = 10000, condition: () -> Boolean) {
    val deadline = SystemClock.elapsedRealtime() + timeoutMs
    while (!condition()) {
        if (SystemClock.elapsedRealtime() > deadline) throw AssertionError("Condition was not met within $timeoutMs ms")
        SystemClock.sleep(25)
    }
}
