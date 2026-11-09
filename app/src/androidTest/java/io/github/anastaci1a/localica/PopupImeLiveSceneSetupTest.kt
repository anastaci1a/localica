package io.github.anastaci1a.localica

import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.library.Track
import io.github.anastaci1a.localica.playback.PlaybackConnection
import io.github.anastaci1a.localica.ui.ActionSlot
import io.github.anastaci1a.localica.ui.PlayerAction
import io.github.anastaci1a.localica.ui.PlayerActionPreferences
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Explicit synthetic setup for external, real-MainActivity optical capture. No Compose test clock. */
class PopupImeLiveSceneSetupTest {
    @Test fun preparePausedPlayerForExternalCapture() {
        assumeTrue("Opt-in synthetic player setup; not a production-data test",
            InstrumentationRegistry.getArguments().getString("popupImeCaptureSetup") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val app = context.applicationContext as LocalicaApplication
        assertFalse(app.tagEditing.state.value.busy)
        assertNull(app.tagEditing.state.value.recovery)
        val file = File(context.filesDir, "ime-optical-fixture.mp3")
        instrumentation.context.assets.open("tagged.mp3").use { input -> file.outputStream().use(input::copyTo) }
        val track = Track(Uri.fromFile(file).toString(), "IME optical fixture", artist = "Localica",
            album = "Native keyboard capture", fileName = file.name, relativePath = file.name)
        val actions = PlayerActionPreferences(context)
        actions.write(actions.read().place(PlayerAction.Edit, ActionSlot(0, 0)))
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use {
            lateinit var playback: PlaybackConnection
            instrumentation.runOnMainSync { playback = PlaybackConnection(context) }
            try {
                fun await(message: String, condition: () -> Boolean) {
                    val deadline = SystemClock.uptimeMillis() + 15000
                    while (!condition() && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(25)
                    assertTrue(message, condition())
                }
                await("Native controller connects") { playback.state.value.connected && !playback.queues.state.value.loading }
                playback.playFromList(listOf(track), track.id)
                await("Synthetic file reaches the real native player") { playback.state.value.trackUri == track.uri }
                instrumentation.runOnMainSync { playback.queues.playbackRequested(false) }
                await("Capture begins paused") { !playback.state.value.isPlaying }
                assertEquals(track.id, playback.queues.state.value.library.active?.entries?.firstOrNull()?.track?.id)
            } finally { instrumentation.runOnMainSync { playback.close() } }
        }
    }
}
