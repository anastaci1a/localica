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
class NoneRealAppWalkTest {
    @Test fun librarySongsAlbumPlayerAndPopupsStayAlive() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val device = UiDevice.getInstance(instrumentation)
        val preferences = InterfacePreferences(context)
        val original = preferences.read()
        val actionPreferences = PlayerActionPreferences(context)
        val originalActions = actionPreferences.read()
        // Seed the application's existing Room store so its live invalidation tracker sees the write.
        // A second Room instance would race the application's already collected library snapshot.
        val app = context.applicationContext as LocalicaApplication
        val store = LibraryRepository::class.java.getDeclaredField("store").apply { isAccessible = true }
            .get(app.library) as LibraryStore
        val root = "none-walk-691"
        val file = File(context.cacheDir, "none-walk-691.mp3")
        instrumentation.context.assets.open("queue-tone.mp3").use { input -> file.outputStream().use(input::copyTo) }
        val track = Track(Uri.fromFile(file).toString(), "None walk song", album = "None walk album",
            artist = "Localica", fileName = file.name, relativePath = file.name, id = root)
        var playback: PlaybackConnection? = null
        fun visible(selector: androidx.test.uiautomator.BySelector): androidx.test.uiautomator.UiObject2 =
            requireNotNull(device.findFresh(selector, 15000)) { "Missing $selector" }
        fun checkpoint(name: String) {
            android.util.Log.i("NoneRealAppWalk", "checkpoint=$name")
            SystemClock.sleep(700)
            assertNotNull("Activity disappeared at $name", device.findObject(By.pkg(context.packageName)))
            assertNull("Crash dialog at $name", device.findObject(By.textContains("keeps stopping")))
            val base = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let(::File)
                ?: context.getExternalFilesDir(null)
            val dir = File(base, "none-real-app-walk").apply { mkdirs() }
            assertTrue(device.takeScreenshot(File(dir, "$name.png")))
            device.dumpWindowHierarchy(File(dir, "$name.xml"))
        }
        try {
            runBlocking { store.replaceRoot(ScanResult(FolderSelection(root, "None walk"), listOf(track), 0)) }
            actionPreferences.write(PlayerActionSettings(slots = mapOf(ActionSlot(0, 0) to PlayerAction.More)))
            preferences.write(InterfaceSettings(tabs = listOf(MainTab.Home, MainTab.Library, MainTab.Songs, MainTab.Albums),
                expandOnPlay = true, glass = GlassAppearance(renderer = GlassRendererMode.None)))
            ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use {
                visible(By.desc("Library")).click()
                visible(By.text("Songs"))
                checkpoint("library")
                visible(By.text("Songs")).click()
                visible(By.text("None walk song"))
                checkpoint("songs")
                visible(By.desc("Albums")).click()
                visible(By.text("None walk album")).click()
                visible(By.text("None walk song"))
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
        } finally {
            playback?.let { connection -> instrumentation.runOnMainSync { connection.clear(); connection.close() } }
            actionPreferences.write(originalActions)
            preferences.write(original)
            runBlocking { store.removeRoot(root) }
            file.delete()
        }
    }
}
