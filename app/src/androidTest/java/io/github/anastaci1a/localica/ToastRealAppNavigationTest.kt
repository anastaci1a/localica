package io.github.anastaci1a.localica

import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import io.github.anastaci1a.localica.library.*
import io.github.anastaci1a.localica.navigation.*
import io.github.anastaci1a.localica.playback.PlaybackConnection
import io.github.anastaci1a.localica.playlist.*
import io.github.anastaci1a.localica.queue.*
import io.github.anastaci1a.localica.toast.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.roundToInt
import io.github.anastaci1a.localica.ui.FloatingSurfaceGap

/** Actual MainActivity, app controllers, native media session and physical UiAutomator taps.
 * Only this dedicated debug emulator's fixture queues/lists/files are created and removed. */
class ToastRealAppNavigationTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val app get() = context.applicationContext as LocalicaApplication
    private val device get() = UiDevice.getInstance(instrumentation)
    private fun await(label: String, timeout: Long = 15000, condition: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + timeout
        while (!condition()) {
            if (SystemClock.uptimeMillis() >= end) throw AssertionError("Timed out: $label")
            SystemClock.sleep(16)
        }
    }
    private fun commit(action: ((Boolean) -> Unit) -> Unit) {
        val result = ArrayBlockingQueue<Boolean>(1)
        instrumentation.runOnMainSync { action { result.offer(it) } }
        assertEquals(true, result.poll(15, TimeUnit.SECONDS))
    }
    private fun visible(selector: BySelector): UiObject2 {
        var found: UiObject2? = null
        await("visible native node: $selector") {
            found = device.findObjects(selector).firstOrNull { it.visibleBounds.width() > 0 && it.visibleBounds.height() > 0 }
            found != null
        }
        return requireNotNull(found)
    }
    private fun nativeHold(node: UiObject2) {
        val bounds = node.visibleBounds
        val x = bounds.exactCenterX(); val y = bounds.exactCenterY()
        val automation = instrumentation.uiAutomation
        val down = SystemClock.uptimeMillis()
        fun inject(action: Int, px: Float, py: Float) {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, px, py, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            try { assertTrue(automation.injectInputEvent(event, true)) } finally { event.recycle() }
        }
        inject(MotionEvent.ACTION_DOWN, x, y)
        val heldFrom = SystemClock.uptimeMillis()
        var step = 0
        while (SystemClock.uptimeMillis() - heldFrom < 1100) {
            SystemClock.sleep(16); step++
            inject(MotionEvent.ACTION_MOVE, x + (step % 3 - 1) * 1.5f, y)
        }
        inject(MotionEvent.ACTION_UP, x, y)
    }
    private fun clickableBody(selector: BySelector): UiObject2 {
        var node: UiObject2? = visible(selector)
        repeat(8) {
            val current = requireNotNull(node) { "No clickable native parent for $selector" }
            if (current.isClickable) return current
            node = current.parent
        }
        error("No clickable native body for $selector")
    }
    private fun evidence(label: String) {
        val directory = File(context.getExternalFilesDir(null), "toast-root713").apply { mkdirs() }
        assertTrue(device.takeScreenshot(File(directory, "$label.png")))
        device.dumpWindowHierarchy(File(directory, "$label.xml"))
    }
    @Test fun realNoPlayerQueueAndListToastsNavigateWithoutStartingPlaybackAndShareRaisedQueueRow() {
        val prefix = "toast-root713-${UUID.randomUUID()}"
        val files = mutableListOf<File>()
        fun track(suffix: String, title: String): Track {
            val file = File(context.cacheDir, "$prefix-$suffix.mp3").also { files += it }
            instrumentation.context.assets.open("queue-tone.mp3").use { input -> file.outputStream().use(input::copyTo) }
            return Track(Uri.fromFile(file).toString(), title, id = "$prefix-$suffix", fileName = file.name, relativePath = file.name)
        }
        val baseTrack = track("base", "Toast base song")
        val base = (0 until 100).map { baseTrack.copy(id = "$prefix-base-$it", title = "Toast base ${it.toString().padStart(3, '0')}") }
        val first = track("first", "Toast first added song")
        val second = track("second", "Toast second added song")
        val third = track("third", "Toast third added song")
        val destination = ListeningQueue(id = "$prefix-queue", name = "Toast destination queue",
            entries = base.mapIndexed { index, song -> QueueEntry("$prefix-entry-$index", song) })
        val playlist = PlaylistEdits.append(ManualPlaylist(id = "$prefix-list", name = "Toast destination list"), base)
        val interfacePreferences = InterfacePreferences(context)
        val originalInterface = interfacePreferences.read()
        val queuePreferences = QueuePreferences(context)
        val originalQueueSettings = queuePreferences.read()
        var playback: PlaybackConnection? = null
        try {
            // Seed before MainActivity/PlaybackConnection first resolve their lazy app controllers.
            runBlocking {
                val db = Room.databaseBuilder(context, QueueDatabase::class.java, "queues.db")
                    .addMigrations(QueueDatabase.MIGRATION_1_2, QueueDatabase.MIGRATION_2_3, QueueDatabase.MIGRATION_3_4,
                        QueueDatabase.MIGRATION_4_5, QueueDatabase.MIGRATION_5_6, QueueDatabase.MIGRATION_6_7).build()
                try { val before = db.queues().read(); db.queues().save(before, before.copy(queues = before.queues + destination, activeId = null)) }
                finally { db.close() }
                val lists = Room.databaseBuilder(context, PlaylistDatabase::class.java, "playlists.db")
                    .addMigrations(PlaylistDatabase.MIGRATION_1_2, PlaylistDatabase.MIGRATION_2_3, PlaylistDatabase.MIGRATION_3_4).build()
                try { val before = lists.playlists().read(); lists.playlists().save(before, before + playlist) }
                finally { lists.close() }
            }
            interfacePreferences.write(InterfaceSettings(tabs = listOf(MainTab.Library, MainTab.Playlists, MainTab.Queues),
                reducedMotion = true, expandOnPlay = false))
            queuePreferences.write(originalQueueSettings.copy(switching = QueueSwitch.Resume))
            ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use {
                try {
                instrumentation.runOnMainSync { playback = PlaybackConnection(context) }
                val connection = requireNotNull(playback)
                await("real media connection") { connection.state.value.connected && !connection.queues.state.value.loading && !app.playlists.state.value.loading }
                instrumentation.runOnMainSync { connection.clear() }
                await("true no-player start") { connection.state.value.trackUri == null && app.queues.state.value.library.activeId == null }
                assertTrue(app.queues.state.value.library.queues.any { it.id == destination.id })
                assertNull(app.toasts.state.value.current)
                commit { complete -> app.queues.appendTo(destination.id, listOf(first, second, third).map { SongRow(it.id, it) }, complete) }
                val committed = app.queues.state.value.library
                val command = app.toasts.state.value.current!!.content.tap as ToastCommand.OpenQueue
                assertEquals(destination.id, command.queueId)
                assertEquals(first.id, committed.queues.single { it.id == destination.id }.entries.single { it.id == command.firstAddedEntryId }.track.id)
                evidence("queue-toast-no-player")
                visible(By.res("toast713-body")).click()
                visible(By.desc("Minimize queue"))
                val firstNode = visible(By.text(first.title))
                assertTrue(firstNode.visibleBounds.height() > 0)
                assertNull(connection.state.value.trackUri)
                assertFalse(connection.state.value.isPlaying)
                assertEquals(committed, app.queues.state.value.library)
                assertEquals(QueueSwitch.Resume, app.queues.state.value.settings.switching)
                val originalItemBounds = firstNode.visibleBounds
                val homeNode = clickableBody(By.desc("Return to top"))
                val home = homeNode.visibleBounds
                evidence("queue-original-home")
                val diagnostics = File(File(context.getExternalFilesDir(null), "toast-root713"), "bounds.txt")
                var parent: UiObject2? = homeNode
                repeat(4) { level ->
                    val node = parent
                    if (node != null) { diagnostics.appendText("home[$level] bounds=${node.visibleBounds} desc=${node.contentDescription} click=${node.isClickable} class=${node.className}\n"); parent = node.parent }
                }
                app.toasts.showToast(ToastMessage("Queue row placement", durationMillis = 60000))
                val rowToast = visible(By.res("toast713-body"))
                val unselectedToast = rowToast.visibleBounds
                diagnostics.appendText("toast=$unselectedToast home=$home current=${app.toasts.state.value}\n")
                evidence("queue-baseline-diagnostic")
                assertTrue("Toast and actual Queue control share their baseline: toast=$unselectedToast home=$home", abs(unselectedToast.bottom - home.bottom) <= 2)
                assertEquals("Toast introduces no list inset", originalItemBounds, visible(By.text(first.title)).visibleBounds)
                val rowId = app.toasts.state.value.current!!.id
                nativeHold(visible(By.text(first.title)))
                visible(By.text("1 selected"))
                await("same toast follows actual raised Queue row") {
                    device.findObject(By.res("toast713-body"))?.visibleBounds?.bottom?.let { it < unselectedToast.bottom } == true
                }
                val selectedToast = visible(By.res("toast713-body")).visibleBounds
                assertEquals(rowId, app.toasts.state.value.current!!.id)
                var panel: UiObject2? = visible(By.text("1 selected"))
                repeat(8) { if (panel?.className != "android.widget.ScrollView") panel = panel?.parent }
                assertEquals("Measured native selection occupancy owner", "android.widget.ScrollView", panel?.className)
                val occupancy = requireNotNull(panel) { "Missing native selection occupancy" }.visibleBounds.height()
                val gap = (FloatingSurfaceGap.value * context.resources.displayMetrics.density).roundToInt()
                assertTrue("Toast shares measured Queue selection clearance", abs(selectedToast.bottom - (unselectedToast.bottom - occupancy - gap)) <= 2)
                val selectedItem = visible(By.text(first.title)).visibleBounds
                evidence("queue-selected-raised-toast")
                visible(By.res("toast713-body")).swipe(Direction.LEFT, 1f)
                await("physical toast dismissal") { app.toasts.state.value.current == null }
                assertNull("Return remains hidden under existing selection eligibility", device.findObject(By.desc("Return to top")))
                assertEquals(selectedItem, visible(By.text(first.title)).visibleBounds)
                assertNull(connection.state.value.trackUri)
                evidence("queue-selected-after-toast-retirement")
                device.pressBack()
                val restoredHome = clickableBody(By.desc("Return to top")).visibleBounds
                assertTrue("Clearing selection restores the original control baseline", abs(home.bottom - restoredHome.bottom) <= 2)
                evidence("queue-unselected-control-restored")
                visible(By.desc("Minimize queue")).click()
                visible(By.desc("Collapse player")).click()
                await("no-player after closing display lease") { connection.state.value.trackUri == null && app.queues.state.value.library.activeId == null }
                commit { complete -> app.playlists.append(playlist.id, listOf(first, second, third), complete) }
                val listCommand = app.toasts.state.value.current!!.content.tap as ToastCommand.OpenList
                val savedList = app.playlists.state.value.playlists.single { list -> list.id == playlist.id }
                assertEquals(first.id, savedList.entries.single { entry -> entry.id == listCommand.firstAddedEntryId }.track.id)
                visible(By.res("toast713-body")).click()
                visible(By.text(first.title))
                evidence("list-destination-before-controls")
                visible(By.desc("Playlist actions"))
                assertNull(connection.state.value.trackUri)
                assertNull(app.queues.state.value.library.activeId)
                assertFalse(connection.state.value.isPlaying)
                evidence("list-first-added-no-player")
                } catch (failure: Throwable) {
                    evidence("failure")
                    File(File(context.getExternalFilesDir(null), "toast-root713"), "failure-state.txt").writeText(
                        "playback=${playback?.state?.value} active=${app.queues.state.value.library.activeId} browsed=${app.queues.state.value.browsedId} toast=${app.toasts.state.value}\n")
                    throw failure
                }
            }
        } finally {
            playback?.let { connection -> instrumentation.runOnMainSync { connection.clear(); connection.close() } }
            if (!app.queues.state.value.loading) {
                instrumentation.runOnMainSync { app.queues.removeQueues(setOf(destination.id)) }
                await("remove owned fixture queue") { app.queues.state.value.library.queues.none { it.id == destination.id } }
            }
            if (!app.playlists.state.value.loading && app.playlists.state.value.playlists.any { it.id == playlist.id })
                commit { complete -> app.playlists.delete(playlist.id, complete) }
            interfacePreferences.write(originalInterface)
            queuePreferences.write(originalQueueSettings)
            files.forEach { it.delete() }
        }
    }
}
