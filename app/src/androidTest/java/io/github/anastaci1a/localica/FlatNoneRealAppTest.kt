package io.github.anastaci1a.localica

import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import io.github.anastaci1a.localica.library.*
import io.github.anastaci1a.localica.navigation.*
import io.github.anastaci1a.localica.playback.PlaybackConnection
import io.github.anastaci1a.localica.ui.ActionSlot
import io.github.anastaci1a.localica.ui.PlayerAction
import io.github.anastaci1a.localica.ui.PlayerActionPreferences
import io.github.anastaci1a.localica.ui.PlayerActionSettings
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** MainActivity, real browser recordings, native windows and playback under renderer None (#691). */
class FlatNoneRealAppTest {
    @Test fun librarySongsAlbumPlayerAndPopupsStayAlive() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val device = UiDevice.getInstance(instrumentation)
        val events = java.util.Collections.synchronizedList(mutableListOf<String>())
        instrumentation.runOnMainSync { io.github.anastaci1a.localica.ui.GlassWork.observer = { events.add(it) } }
        val preferences = InterfacePreferences(context)
        val original = preferences.read()
        val actionPreferences = PlayerActionPreferences(context)
        val originalActions = actionPreferences.read()
        // Seed the application's existing Room store so its live invalidation tracker sees the write.
        // A second Room instance would race the application's already collected library snapshot.
        val app = context.applicationContext as LocalicaApplication
        val store = LibraryRepository::class.java.getDeclaredField("store").apply { isAccessible = true }
            .get(app.library) as LibraryStore
        val root = "flat-none-walk-740"
        val file = File(context.cacheDir, "flat-none-walk-740.mp3")
        instrumentation.context.assets.open("queue-tone.mp3").use { input -> file.outputStream().use(input::copyTo) }
        val track = Track(Uri.fromFile(file).toString(), "Flat None walk song", album = "Flat None walk album",
            artist = "Localica", fileName = file.name, relativePath = file.name, id = root)
        var playback: PlaybackConnection? = null
        var walkFailure: Throwable? = null
        fun nativePlaybackEmpty(connection: PlaybackConnection): Boolean {
            var empty = false
            instrumentation.runOnMainSync { empty = connection.queues.playbackSample() == null }
            return empty
        }
        fun visible(selector: androidx.test.uiautomator.BySelector): androidx.test.uiautomator.UiObject2 =
            requireNotNull(device.findFresh(selector, 15000)) { "Missing $selector" }
        fun checkpoint(name: String) {
            android.util.Log.i("FlatNoneRealApp", "checkpoint=$name")
            SystemClock.sleep(700)
            assertEquals("Glass work at $name: $events", emptyList<String>(), events.toList())
            assertNotNull("Activity disappeared at $name", device.findObject(By.pkg(context.packageName)))
            assertNull("Crash dialog at $name", device.findObject(By.textContains("keeps stopping")))
            val base = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let(::File)
                ?: context.getExternalFilesDir(null)
            val dir = File(base, "none-real-app-walk").apply { mkdirs() }
            assertTrue(device.takeScreenshot(File(dir, "$name.png")))
            device.dumpWindowHierarchy(File(dir, "$name.xml"))
        }
        try {
            runBlocking { store.replaceRoot(ScanResult(FolderSelection(root, "Flat None walk"), listOf(track), 0)) }
            actionPreferences.write(PlayerActionSettings(slots = mapOf(ActionSlot(0, 0) to PlayerAction.More)))
            preferences.write(InterfaceSettings(tabs = listOf(MainTab.Home, MainTab.Library, MainTab.Songs, MainTab.Albums),
                expandOnPlay = true, glass = GlassAppearance(renderer = GlassRendererMode.FlatNone)))
            ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use {
                visible(By.desc("Library")).click()
                visible(By.text("Songs"))
                checkpoint("library")
                visible(By.text("Songs")).click()
                visible(By.text("Flat None walk song"))
                checkpoint("songs")
                visible(By.desc("Albums")).click()
                visible(By.text("Flat None walk album"))
                checkpoint("albums")
                visible(By.text("Flat None walk album")).click()
                checkpoint("album-opened")
                visible(By.text("Flat None walk song"))
                checkpoint("album")
                visible(By.desc("Album actions")).click()
                visible(By.text("Select all songs"))
                checkpoint("album-popup")
                device.pressBack()
                instrumentation.runOnMainSync { playback = PlaybackConnection(context) }
                val connection = requireNotNull(playback)
                await(15000) { connection.state.value.connected && !connection.queues.state.value.loading }
                instrumentation.runOnMainSync { connection.playFromList(listOf(track), track.id) }
                visible(By.desc("Collapse player"))
                checkpoint("player")
                visible(By.desc("More actions")).click()
                visible(By.text("Information"))
                checkpoint("player-popup")
                device.pressBack()
                visible(By.desc("Collapse player")).click()
                checkpoint("returned")
            }
        } catch (failure: Throwable) {
            walkFailure = failure
            throw failure
        } finally {
            instrumentation.runOnMainSync { io.github.anastaci1a.localica.ui.GlassWork.observer = null }
            var retired = false
            val retirement = org.json.JSONObject().put("fixture", file.path)
            val cleanupFailure = runCatching { playback?.let { connection ->
                try {
                    instrumentation.runOnMainSync { connection.clear() }
                    // The actor publishes activeId only after its durable save;
                    // the service-owned Main-thread sample proves no native item
                    // remains, even if the remote controller has disconnected.
                    await(15000) { connection.queues.state.value.library.activeId == null &&
                        nativePlaybackEmpty(connection) }
                    val ownedQueues = connection.queues.state.value.library.queues.filter { queue ->
                        queue.entries.isNotEmpty() && queue.entries.all { it.track.id == root }
                    }.map { it.id }.toSet()
                    instrumentation.runOnMainSync { connection.queues.removeQueues(ownedQueues) }
                    await(15000) { connection.queues.state.value.library.queues.none { it.id in ownedQueues } }
                    assertFalse("No retained queue may reference the fixture being deleted",
                        connection.queues.state.value.library.queues.any { queue -> queue.entries.any { it.track.id == root } })
                    retirement.put("durableActiveEmpty", connection.queues.state.value.library.activeId == null)
                        .put("nativeSampleEmpty", nativePlaybackEmpty(connection))
                        .put("controllerConnected", connection.state.value.connected)
                        .put("controllerTrackEmpty", connection.state.value.trackUri == null)
                        .put("controllerProblem", connection.state.value.problem?.name ?: org.json.JSONObject.NULL)
                        .put("removedOwnedQueues", org.json.JSONArray(ownedQueues.toList()))
                        .put("sourceExistedBeforeDelete", file.exists())
                    retired = true
                } finally { instrumentation.runOnMainSync { connection.close() } }
            } }.exceptionOrNull()
            actionPreferences.write(originalActions)
            preferences.write(original)
            runBlocking { store.removeRoot(root) }
            // An undrained player must retain its test-owned source, even on a
            // failing teardown, so a later test cannot inherit a missing-file loop.
            if (retired) file.delete()
            retirement.put("retired", retired).put("sourceExistsAfterCleanup", file.exists())
            val outputRoot = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let(::File)
                ?: context.getExternalFilesDir(null)
            File(outputRoot, "none-real-app-walk").apply { mkdirs() }
                .resolve("fixture-retirement.json").writeText(retirement.toString(2))
            if (cleanupFailure != null) {
                val failure = walkFailure
                if (failure == null) throw cleanupFailure else failure.addSuppressed(cleanupFailure)
            }
        }
    }
}
