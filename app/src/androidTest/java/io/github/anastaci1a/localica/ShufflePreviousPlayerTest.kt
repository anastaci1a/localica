package io.github.anastaci1a.localica

import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import kotlinx.coroutines.test.StandardTestDispatcher
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import io.github.anastaci1a.localica.library.Track
import io.github.anastaci1a.localica.navigation.InterfacePreferences
import io.github.anastaci1a.localica.navigation.InterfaceSettings
import io.github.anastaci1a.localica.playback.PlaybackConnection
import io.github.anastaci1a.localica.queue.*
import io.github.anastaci1a.localica.ui.PlayerActionPreferences
import io.github.anastaci1a.localica.ui.PlayerActionSettings
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Real MainActivity controls, service playback and occurrence receipts for the shuffle-start boundary. */
@OptIn(ExperimentalTestApi::class)
class ShufflePreviousPlayerTest {
    @get:Rule val compose = createEmptyComposeRule(effectContext = StandardTestDispatcher())
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val device get() = UiDevice.getInstance(instrumentation)
    private fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)

    private fun click(description: String) {
        compose.waitUntil(15_000) { compose.onAllNodesWithContentDescription(description).fetchSemanticsNodes().isNotEmpty() }
        val node = compose.onNodeWithContentDescription(description)
        node.assertIsDisplayed().assertIsEnabled()
        compose.nativePointer(node)
    }

    @Test fun fullScreenShufflePreviousRetracesOriginalOrderAndRespectsRepeatAtExhaustion() {
        val interfacePreferences = InterfacePreferences(context)
        val originalInterface = interfacePreferences.read()
        val actionPreferences = PlayerActionPreferences(context)
        val originalActions = actionPreferences.read()
        val file = File.createTempFile("shuffle-previous-", ".mp3", context.cacheDir)
        var playback: PlaybackConnection? = null
        var savedSettings: QueueSettings? = null
        var existingQueues = emptySet<String>()
        try {
            copyTestAsset(instrumentation.context.assets, "queue-tone.mp3", file)
            interfacePreferences.write(InterfaceSettings())
            actionPreferences.write(PlayerActionSettings())
            ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use {
                main { playback = PlaybackConnection(context) }
                val connection = requireNotNull(playback)
                val queues = connection.queues
                await(15_000) { connection.state.value.connected && !queues.state.value.loading }
                savedSettings = queues.state.value.settings
                existingQueues = queues.state.value.library.queues.map { it.id }.toSet()
                fun journey() = requireNotNull(queues.state.value.library.active?.journey)
                fun healthy() {
                    assertNull(connection.state.value.problem)
                    assertNull(queues.state.value.problem)
                }
                fun at(visit: QueueVisit, title: String, playing: Boolean = false) {
                    await(15_000) {
                        healthy()
                        val state = connection.state.value
                        journey().current == visit && state.playbackVisit == visit.token &&
                            state.title == title && !state.isBuffering && state.isPlaying == playing && state.playWhenReady == playing
                    }
                    assertNotNull("Full player stays expanded", device.findFresh(By.desc("Collapse player"), 5000))
                    assertNotNull("Displayed song: $title", device.findFresh(By.text(title), 5000))
                    Log.i("ShufflePreviousProof", "at entry=${visit.entryId} token=${visit.token} cycle=${visit.cycle} title=$title playing=$playing")
                }
                fun seek(position: Long) {
                    var request = 0L
                    main { request = connection.requestSeek(position) }
                    await(15_000) { connection.state.value.seekAcknowledgement?.let { it.requestId == request && it.applied } == true }
                }
                for (repeatMode in listOf(QueueRepeat.Off, QueueRepeat.All, QueueRepeat.One)) {
                    main { queues.settings(QueueSettings()) }
                    await(15_000) { queues.state.value.settings == QueueSettings() }
                    val tracks = (0..6).map { index -> Track(Uri.fromFile(file).toString(), "Shuffle $repeatMode song $index",
                        fileName = file.name, relativePath = file.name, id = "${file.name}:$repeatMode:$index") }
                    main { connection.playFromList(tracks, tracks[3].id) }
                    await(15_000) { healthy(); connection.state.value.trackId == tracks[3].id && connection.state.value.isPlaying }
                    assertNotNull(device.findFresh(By.desc("Collapse player"), 15_000))
                    click("Pause")
                    await(15_000) { !connection.state.value.playWhenReady && !connection.state.value.isPlaying }
                    seek(1000)
                    val queue = requireNotNull(queues.state.value.library.active)
                    val ids = queue.entries.map { it.id }
                    assertEquals(tracks.map { it.id }, queue.entries.map { it.track.id })
                    val anchor = journey().current!!
                    assertEquals(ids[3], anchor.entryId)
                    if (repeatMode != QueueRepeat.Off) {
                        click("Repeat: Off")
                        await(15_000) { queues.state.value.settings.repeat == QueueRepeat.All }
                        if (repeatMode == QueueRepeat.One) {
                            click("Repeat: All")
                            await(15_000) { queues.state.value.settings.repeat == QueueRepeat.One }
                        }
                    }
                    if (repeatMode == QueueRepeat.Off) {
                        click("Play")
                        at(anchor, tracks[3].title, playing = true)
                        seek(1000)
                    }
                    click("Shuffle: off")
                    await(15_000) { queues.state.value.settings.shuffle && journey().modes.shuffle }
                    assertEquals("Shuffle keeps the actual visit", anchor, journey().current)
                    assertTrue("Exercise normal Previous below its restart threshold", connection.state.value.positionMs < 5000)
                    if (repeatMode == QueueRepeat.Off) {
                        val previous = journey().previous!!
                        assertEquals(ids[2], previous.entryId)
                        click("Previous")
                        at(previous, tracks[2].title, playing = true)
                        click("Pause")
                        at(previous, tracks[2].title)
                        click("Next")
                        at(anchor, tracks[3].title)
                        Log.i("ShufflePreviousProof", "PASS playing Previous anchor=$anchor previous=$previous")
                    }
                    for (index in listOf(2, 1)) {
                        val expected = journey().previous!!
                        assertEquals(ids[index], expected.entryId)
                        click("Previous")
                        at(expected, tracks[index].title)
                    }
                    val swipe = journey().previous!!
                    assertEquals(ids[0], swipe.entryId)
                    val cover = device.findObjects(By.longClickable(true)).map { it.visibleBounds }
                        .filter { it.width() > 100 && it.height() > 100 }.maxByOrNull { it.width() * it.height() }
                    assertNotNull("Artwork swipe surface", cover)
                    val bounds = cover!!
                    val y = bounds.top + bounds.height() * 35 / 100
                    assertTrue(device.swipe(bounds.left + bounds.width() / 5, y, bounds.left + bounds.width() * 4 / 5, y, 24))
                    at(swipe, tracks[0].title)
                    for (index in 1..3) {
                        val expected = journey().next!!
                        assertEquals(ids[index], expected.entryId)
                        click("Next")
                        at(expected, tracks[index].title)
                    }
                    assertEquals("Forward retrace returns to the same anchor", anchor, journey().current)
                    val heard = ids.take(4).toMutableSet()
                    repeat(3) {
                        val expected = journey().next!!
                        assertEquals(anchor.cycle, expected.cycle)
                        assertTrue("Remaining shuffle cannot repeat a visited occurrence", heard.add(expected.entryId))
                        click("Next")
                        at(expected, tracks[ids.indexOf(expected.entryId)].title)
                    }
                    assertEquals(ids.toSet(), heard)
                    assertEquals(ids.toSet(), journey().visited)
                    val last = journey().current!!
                    val following = journey().next
                    if (repeatMode == QueueRepeat.All) {
                        assertNotNull(following)
                        assertEquals(last.cycle + 1, following!!.cycle)
                    } else assertNull("Only Repeat All extends the shuffle cycle", following)
                    val duration = requireNotNull(connection.state.value.durationMs)
                    assertTrue(duration > 1000)
                    Log.i("ShufflePreviousProof", "before exhaustion seek: ${connection.state.value}")
                    seek(duration - 250)
                    Log.i("ShufflePreviousProof", "after exhaustion seek: ${connection.state.value}")
                    assertFalse("Exhaustion setup remains paused", connection.state.value.playWhenReady)
                    Log.i("ShufflePreviousProof", "current semantics Play=${compose.onAllNodesWithContentDescription("Play").fetchSemanticsNodes().size} " +
                        "ShuffleOn=${compose.onAllNodesWithContentDescription("Shuffle: on").fetchSemanticsNodes().size}")
                    val refreshedLabels = mutableSetOf<String>()
                    fun refresh(node: android.view.accessibility.AccessibilityNodeInfo) {
                        val old = node.contentDescription?.toString()
                        val live = node.refresh()
                        val label = node.contentDescription?.toString()
                        if (old != null || label != null) Log.i("ShufflePreviousProof", "accessibility refresh old=$old live=$live current=$label")
                        if (live) {
                            label?.let(refreshedLabels::add)
                            for (index in 0 until node.childCount) node.getChild(index)?.let(::refresh)
                        }
                    }
                    instrumentation.uiAutomation.rootInActiveWindow?.let(::refresh)
                    compose.onNodeWithContentDescription("Play").assertIsDisplayed().assertIsEnabled()
                    compose.onNodeWithContentDescription("Shuffle: on").assertIsDisplayed()
                    assertTrue("Fresh accessibility must expose Play", "Play" in refreshedLabels)
                    assertTrue("Fresh accessibility must expose enabled shuffle", "Shuffle: on" in refreshedLabels)
                    click("Play")
                    when (repeatMode) {
                        QueueRepeat.Off -> await(15_000) { healthy(); connection.state.value.ended && !connection.state.value.isPlaying }
                        QueueRepeat.All -> await(15_000) { healthy(); connection.state.value.playbackVisit == following!!.token &&
                            journey().current == following && connection.state.value.isPlaying }
                        QueueRepeat.One -> await(15_000) { healthy(); connection.state.value.playbackVisit == last.token &&
                            connection.state.value.isPlaying && connection.state.value.positionMs < 1000 }
                    }
                    if (repeatMode != QueueRepeat.All) assertEquals(last, journey().current)
                    Log.i("ShufflePreviousProof", "PASS repeat=$repeatMode entries=${ids.size} anchor=${anchor.token} last=${last.token} next=${following?.token}")
                    main { connection.clear() }
                    await(15_000) { connection.state.value.trackUri == null }
                }
            }
        } finally {
            try {
                playback?.let { connection ->
                    val owned = connection.queues.state.value.library.queues.map { it.id }.toSet() - existingQueues
                    main {
                        connection.clear()
                        connection.queues.removeQueues(owned)
                        savedSettings?.let(connection.queues::settings)
                    }
                    await(15_000) { connection.queues.state.value.library.queues.none { it.id in owned } }
                    main { connection.close() }
                }
            } finally {
                interfacePreferences.write(originalInterface)
                actionPreferences.write(originalActions)
                file.delete()
            }
        }
    }
}
