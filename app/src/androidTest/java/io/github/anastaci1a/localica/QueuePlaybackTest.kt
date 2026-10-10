package io.github.anastaci1a.localica

import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.library.Track
import io.github.anastaci1a.localica.playback.PlaybackConnection
import io.github.anastaci1a.localica.queue.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class QueuePlaybackTest {
    @Test fun queuesCaptureListsSwitchPausedAndKeepPlaybackDuringEditsAndCancelledCleanup() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val file = File(context.cacheDir, "queue-tone.mp3")
        instrumentation.context.assets.open(file.name).use { input -> file.outputStream().use(input::copyTo) }
        val uri = Uri.fromFile(file).toString()
        val tracks = listOf("First", "Second", "Third").map { Track(uri, it, fileName = file.name, relativePath = file.name, id = it) }
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use {
            lateinit var playback: PlaybackConnection
            instrumentation.runOnMainSync { playback = PlaybackConnection(context) }
            val queues = playback.queues
            try {
                await { playback.state.value.connected && !queues.state.value.loading }
                queues.settings(QueueSettings())
                queues.removeQueues(queues.state.value.library.queues.map { it.id }.toSet())
                await { queues.state.value.library.queues.isEmpty() }
                playback.playFromList(tracks, "Second")
                await { playback.state.value.isPlaying && playback.state.value.trackId == "Second" }
                val firstQueue = queues.state.value.library.active!!
                assertEquals(listOf("First", "Second", "Third"), firstQueue.entries.map { it.track.id })
                instrumentation.runOnMainSync { playback.seekTo(1500); playback.toggle() }
                await { !playback.state.value.isPlaying && playback.state.value.positionMs >= 1400 }
                playback.playFromList(listOf(tracks[0]), "First")
                await { playback.state.value.isPlaying && queues.state.value.library.queues.size == 2 }
                queues.switchTo(firstQueue.id)
                await { playback.state.value.trackId == "Second" && !playback.state.value.isPlaying && playback.state.value.positionMs >= 1400 }
                queues.settings(QueueSettings(songTap = SongTap.Append))
                playback.play(tracks[1])
                await { queues.state.value.library.active!!.entries.size == 4 }
                assertFalse(playback.state.value.isPlaying)
                assertEquals(4, queues.state.value.library.active!!.entries.map { it.id }.toSet().size)
                instrumentation.runOnMainSync { playback.toggle() }
                await { playback.state.value.isPlaying }
                val beforeMove = playback.state.value.positionMs
                queues.moveEntry(firstQueue.entries.last().id, -1)
                await { queues.state.value.library.active!!.entries[1].track.id == "Third" }
                assertEquals("Second", playback.state.value.trackId)
                assertTrue(playback.state.value.isPlaying)
                assertTrue(playback.state.value.positionMs >= beforeMove)
                queues.settings(QueueSettings(maximum = 2))
                playback.play(tracks[0])
                await { queues.state.value.pending != null }
                val activeBeforeCancel = queues.state.value.library.activeId
                queues.cancelCreation()
                await { queues.state.value.pending == null }
                assertEquals(activeBeforeCancel, queues.state.value.library.activeId)
                assertEquals(2, queues.state.value.library.queues.size)
                assertTrue(playback.state.value.isPlaying)
                playback.play(tracks[0])
                await { queues.state.value.pending != null }
                queues.confirmCreation(setOf(queues.state.value.pending!!.suggestedRemoval!!))
                await { queues.state.value.pending == null && queues.state.value.library.activeId != activeBeforeCancel }
                assertEquals(2, queues.state.value.library.queues.size)
                assertTrue(queues.state.value.library.queues.any { it.id == firstQueue.id })
                // Service transport advances inside the queue and saves the selected occurrence.
                queues.switchTo(firstQueue.id)
                await { playback.state.value.trackId == "Second" && !playback.state.value.isPlaying }
                instrumentation.runOnMainSync { playback.next() }
                await { queues.state.value.library.active!!.currentEntryId == queues.state.value.library.active!!.entries.last().id }
                assertEquals("Second", playback.state.value.trackId) // duplicate, different entry
            } finally {
                queues.settings(QueueSettings())
                queues.removeQueues(queues.state.value.library.queues.map { it.id }.toSet())
                await { queues.state.value.library.queues.isEmpty() }
                instrumentation.runOnMainSync { playback.close() }
                file.delete()
            }
        }
    }
}
