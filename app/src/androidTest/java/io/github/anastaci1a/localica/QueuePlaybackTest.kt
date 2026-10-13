package io.github.anastaci1a.localica

import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.library.Track
import io.github.anastaci1a.localica.library.SongListView
import io.github.anastaci1a.localica.playback.PlaybackConnection
import io.github.anastaci1a.localica.queue.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class QueuePlaybackTest {
    @Test fun browsingLeavesPlaybackAloneAndDeletionRestoresMostRecentlyActivatedCursor() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val file = File(context.cacheDir, "queue-tone.mp3")
        instrumentation.context.assets.open(file.name).use { input -> file.outputStream().use(input::copyTo) }
        val tracks = listOf("One", "Two", "Three").map { Track(Uri.fromFile(file).toString(), it, fileName = file.name, relativePath = file.name, id = it) }
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use {
            lateinit var playback: PlaybackConnection
            instrumentation.runOnMainSync { playback = PlaybackConnection(context) }
            val queues = playback.queues
            try {
                await { playback.state.value.connected && !queues.state.value.loading }
                queues.settings(QueueSettings())
                queues.removeQueues(queues.state.value.library.queues.map { it.id }.toSet())
                await { queues.state.value.library.queues.isEmpty() }
                queues.tap(listOf(tracks[0]), 0, sourceName = "Album ◀ Artist")
                await { playback.state.value.isPlaying && playback.state.value.trackId == "One" }
                val first = queues.state.value.library.active!!
                assertEquals("Album ◀ Artist", first.name)
                queues.seek(1500); queues.togglePlayback()
                await { !playback.state.value.isPlaying && playback.state.value.positionMs >= 1400 }
                queues.tap(listOf(tracks[1]), 0)
                await { playback.state.value.isPlaying && playback.state.value.trackId == "Two" }
                val second = queues.state.value.library.active!!
                queues.seek(2500); queues.togglePlayback()
                await { !playback.state.value.isPlaying && playback.state.value.positionMs >= 2400 }
                queues.tap(listOf(tracks[2]), 0)
                await { playback.state.value.isPlaying && playback.state.value.trackId == "Three" }
                val third = queues.state.value.library.active!!
                queues.switchTo(first.id)
                await { queues.state.value.browsedId == first.id }
                assertEquals(third.id, queues.state.value.library.activeId)
                assertEquals("Three", playback.state.value.trackId)
                assertTrue(playback.state.value.isPlaying)
                val appended = java.util.concurrent.atomic.AtomicBoolean(false)
                queues.appendTo(first.id, listOf(io.github.anastaci1a.localica.library.SongRow("extra", tracks[1], "playlist-origin"))) { appended.set(it) }
                await { appended.get() }
                val edited = queues.state.value.library.queues.first { it.id == first.id }
                assertEquals(listOf("One", "Two"), edited.entries.map { it.track.id })
                assertEquals("playlist-origin", edited.entries.last().sourcePlaylistId)
                assertEquals(third.id, queues.state.value.library.activeId)
                assertEquals("Three", playback.state.value.trackId)
                assertTrue("Appending to an inactive destination preserves playback", playback.state.value.isPlaying)
                queues.rename(first.id, "Renamed")
                queues.moveQueue(first.id, 2)
                await { queues.state.value.library.queues.last().id == first.id }
                queues.removeQueues(setOf(third.id))
                await { playback.state.value.isPlaying && playback.state.value.trackId == "Two" }
                assertTrue(playback.state.value.positionMs >= 2400)
                queues.togglePlayback()
                await { !playback.state.value.isPlaying }
                queues.removeQueues(setOf(second.id))
                await { queues.state.value.library.activeId == first.id && playback.state.value.trackId == "One" }
                assertFalse(playback.state.value.isPlaying)
                assertTrue(playback.state.value.positionMs >= 1400)
                assertEquals("Renamed", queues.state.value.library.active!!.name)
                queues.selectEntry(first.entries.single().id)
                await { playback.state.value.isPlaying && playback.state.value.positionMs < 1400 }
            } finally {
                queues.settings(QueueSettings())
                queues.removeQueues(queues.state.value.library.queues.map { it.id }.toSet())
                await { queues.state.value.library.queues.isEmpty() }
                instrumentation.runOnMainSync { playback.close() }; file.delete()
            }
        }
    }
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
                queues.settings(QueueSettings(switching = QueueSwitch.Paused))
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
                queues.settings(QueueSettings(switching = QueueSwitch.Paused, songTap = SongTap.Append))
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
                // Controller estimates can be corrected by a few milliseconds when the service publishes its timeline.
                assertTrue("Position before move: $beforeMove; after: ${playback.state.value.positionMs}",
                    playback.state.value.positionMs >= beforeMove - 50)
                val editPosition = playback.state.value.positionMs
                val editToken = java.util.concurrent.atomic.AtomicReference<QueueController.EditPlayback?>()
                queues.beginFileEdit("Second") { editToken.set(it) }
                await { editToken.get() != null && !playback.state.value.isPlaying }
                val refreshed = java.util.concurrent.atomic.AtomicBoolean(false)
                queues.refreshTrack(tracks[1].copy(title = "Updated title")) { refreshed.set(it) }
                await { refreshed.get() }
                queues.endFileEdit(editToken.get()) {}
                await { playback.state.value.isPlaying && playback.state.value.positionMs >= editPosition - 100 }
                assertEquals(firstQueue.currentEntryId, queues.state.value.library.active!!.currentEntryId)
                assertEquals("Updated title", playback.state.value.title)
                // Upcoming sources must be released too, without losing the current occurrence/cursor.
                val beforeUpcomingEdit = playback.state.value.positionMs
                editToken.set(null)
                queues.beginFileEdit("Third") { editToken.set(it) }
                await { editToken.get() != null && !playback.state.value.isPlaying }
                assertEquals(firstQueue.currentEntryId, editToken.get()!!.entryId)
                queues.endFileEdit(editToken.get()) {}
                await { playback.state.value.isPlaying && playback.state.value.positionMs >= beforeUpcomingEdit - 100 }
                assertEquals("Second", playback.state.value.trackId)
                queues.settings(queues.state.value.settings.copy(preloadNext = false))
                await { !queues.state.value.settings.preloadNext }
                assertFalse(QueuePreferences(context).read().preloadNext)
                queues.settings(QueueSettings(switching = QueueSwitch.Paused, maximum = 2))
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
                queues.settings(QueueSettings(switching = QueueSwitch.Paused, songTap = SongTap.Append,
                    tapOverrides = mapOf(SongListView.Playlists to SongTap.NewQueue)))
                queues.tap(listOf(tracks[0], tracks[1], tracks[0]), 2, SongListView.Playlists)
                await { queues.state.value.library.queues.size == 3 && playback.state.value.trackId == "First" }
                val duplicateQueue = queues.state.value.library.active!!
                assertEquals(duplicateQueue.entries[2].id, duplicateQueue.currentEntryId)
                queues.act(listOf(tracks[2], tracks[1]), SongTap.PlayNext)
                await { queues.state.value.library.active!!.entries.size == 5 }
                assertEquals(listOf("First", "Second", "First", "Third", "Second"),
                    queues.state.value.library.active!!.entries.map { it.track.id })
                assertEquals(duplicateQueue.entries[2].id, queues.state.value.library.active!!.currentEntryId)
            } finally {
                queues.settings(QueueSettings(switching = QueueSwitch.Paused))
                queues.removeQueues(queues.state.value.library.queues.map { it.id }.toSet())
                await { queues.state.value.library.queues.isEmpty() }
                instrumentation.runOnMainSync { playback.close() }
                file.delete()
            }
        }
    }
}
