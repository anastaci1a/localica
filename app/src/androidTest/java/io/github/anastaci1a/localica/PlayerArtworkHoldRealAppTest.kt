package io.github.anastaci1a.localica

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import io.github.anastaci1a.localica.editing.ArtworkChange
import io.github.anastaci1a.localica.editing.CoverImage
import io.github.anastaci1a.localica.editing.TagCodec
import io.github.anastaci1a.localica.editing.TagPatch
import io.github.anastaci1a.localica.library.Track
import io.github.anastaci1a.localica.navigation.*
import io.github.anastaci1a.localica.playback.PlaybackConnection
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * #575 on the shipped path: the real MainActivity (TagEditorHost, the glass renderer read from preferences,
 * the playback service), a PLAYING MP3 with a real embedded cover and queue neighbours, and a finger-like hold
 * injected through the system input pipeline (not Compose's in-process test injection), so the artwork popup
 * window opens underneath a finger that is still down and still moving slightly. Every configured hold action
 * is exercised; a crash kills the instrumentation process, which fails the run.
 */
class PlayerArtworkHoldRealAppTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val device get() = UiDevice.getInstance(instrumentation)

    @Test fun menuHoldOverPlayingEmbeddedCover() =
        hold(InterfaceSettings(), expectText = "Configure tap action")
    @Test fun menuHoldOverFloatingCoverWithLegacyRenderer() =
        hold(InterfaceSettings(artworkLayout = ArtworkLayout.Floating, glass = GlassAppearance(renderer = GlassRendererMode.Legacy)),
            expectText = "Configure tap action")
    @Test fun menuHoldWithCarouselTransitionAndNoSwipe() =
        hold(InterfaceSettings(artworkTransition = ArtworkTransition.Carousel, artworkSwipe = false), expectText = "Configure tap action")
    @Test fun viewHoldOpensTheArtworkViewer() =
        hold(InterfaceSettings(artworkHold = ArtworkAction.View), expectDescription = "Close artwork")
    @Test fun lyricsHoldOpensLyrics() =
        hold(InterfaceSettings(artworkHold = ArtworkAction.Lyrics), expectDescription = "Hide lyrics")
    @Test fun changeHoldOpensTheArtworkChooser() =
        hold(InterfaceSettings(artworkHold = ArtworkAction.Change), expectText = "Choose artwork")
    @Test fun noneHoldDoesNothing() =
        hold(InterfaceSettings(artworkHold = ArtworkAction.None), expectText = null)

    /** A rich cover, decoded like the operator's files: a photo-like gradient with shapes, embedded as JPEG. */
    private fun coveredTone(name: String, hue: Float): Track {
        val file = File(context.cacheDir, "hold-575-$name.mp3")
        instrumentation.context.assets.open("queue-tone.mp3").use { input -> file.outputStream().use(input::copyTo) }
        val bitmap = Bitmap.createBitmap(900, 900, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(900 * 900) { index ->
            val x = index % 900; val y = index / 900
            android.graphics.Color.HSVToColor(floatArrayOf((hue + x / 900f * 90f) % 360f, .4f + .6f * y / 900f, .35f + .65f * x / 900f))
        }
        bitmap.setPixels(pixels, 0, 900, 0, 0, 900, 900)
        android.graphics.Canvas(bitmap).apply {
            drawCircle(450f, 450f, 260f, android.graphics.Paint().apply { color = android.graphics.Color.HSVToColor(floatArrayOf((hue + 180f) % 360f, .9f, .9f)) })
            drawRect(80f, 620f, 820f, 760f, android.graphics.Paint().apply { color = android.graphics.Color.WHITE })
        }
        val bytes = java.io.ByteArrayOutputStream().also { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it)) }.toByteArray()
        bitmap.recycle()
        TagCodec.edit(file, TagPatch(artwork = ArtworkChange.Replace(CoverImage(bytes, "image/jpeg", 900, 900))))
        return Track(Uri.fromFile(file).toString(), "Hold $name", artist = "Localica", album = "Five seventy five",
            fileName = file.name, relativePath = file.name, id = "hold-575-$name")
    }

    /** Window hierarchy and screenshot into the app's external files, pulled with the evidence. */
    private fun evidence(name: String) {
        val dir = File(context.getExternalFilesDir(null), "hold-575").apply { mkdirs() }
        runCatching { device.dumpWindowHierarchy(File(dir, "$name.xml")) }
        runCatching { device.takeScreenshot(File(dir, "$name.png")) }
    }

    /** A finger: down, a slow sub-slop tremor for the whole hold, then up; real system input, screen coordinates. */
    private fun fingerHold(x: Float, y: Float, durationMs: Long) {
        val automation = instrumentation.uiAutomation
        val down = SystemClock.uptimeMillis()
        fun inject(action: Int, px: Float, py: Float) {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, px, py, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            assertTrue(automation.injectInputEvent(event, true))
            event.recycle()
        }
        inject(MotionEvent.ACTION_DOWN, x, y)
        var step = 0
        while (SystemClock.uptimeMillis() - down < durationMs) {
            SystemClock.sleep(16)
            step++
            inject(MotionEvent.ACTION_MOVE, x + (step % 3 - 1) * 1.5f, y + ((step / 2) % 3 - 1) * 1.5f)
        }
        inject(MotionEvent.ACTION_UP, x, y)
    }

    private fun hold(settings: InterfaceSettings, expectText: String? = null, expectDescription: String? = null) {
        val preferences = InterfacePreferences(context)
        val original = preferences.read()
        context.getSharedPreferences("interface", 0).edit().clear().commit()
        preferences.write(settings)
        val tracks = listOf(coveredTone("before", 20f), coveredTone("current", 200f), coveredTone("after", 300f))
        val current = tracks[1]
        var playback: PlaybackConnection? = null
        try {
            ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use { scenario ->
                instrumentation.runOnMainSync { playback = PlaybackConnection(context) }
                val connection = requireNotNull(playback)
                await { !connection.queues.state.value.loading }
                await { connection.state.value.connected }
                instrumentation.runOnMainSync { connection.clear() }
                await { connection.state.value.trackUri == null }
                instrumentation.runOnMainSync { connection.playFromList(tracks, current.id) }
                await(15000) { connection.state.value.let { it.trackId == current.id && it.isPlaying && it.durationMs != null } }
                assertNull(connection.state.value.problem)
                // Tap the mini-player (its title) with real input, then wait for the full player's header.
                val mini = device.wait(Until.findObject(By.text(current.title)), 15000)
                if (mini == null) evidence("no-mini-player")
                assertNotNull("The mini-player did not appear", mini)
                mini.click()
                if (device.wait(Until.findObject(By.desc("Show lyrics")), 10000) == null) evidence("no-full-player")
                assertNotNull("The full player did not open", device.findObject(By.desc("Show lyrics")))
                // The embedded cover decodes off the main thread and the palette blend settles.
                SystemClock.sleep(2500)
                // The cover is the only large long-clickable surface in the full player.
                device.wait(Until.hasObject(By.longClickable(true)), 5000)
                val artwork = device.findObjects(By.longClickable(true)).maxByOrNull { it.visibleBounds.width() * it.visibleBounds.height() }
                if (artwork == null) evidence("no-artwork")
                val bounds = requireNotNull(artwork) { "No long-clickable artwork" }.visibleBounds
                assertTrue("Artwork bounds $bounds", bounds.width() > 100 && bounds.height() > 100)
                // Upper-middle of the cover: on the cover in both layouts, clear of the title block that overlaps a
                // full-background cover's lower part.
                fingerHold(bounds.exactCenterX(), bounds.top + bounds.height() * .35f, durationMs = 1100)
                val still = scenario.state
                assertTrue("Activity state after the hold: $still", still == androidx.lifecycle.Lifecycle.State.RESUMED ||
                    still == androidx.lifecycle.Lifecycle.State.STARTED)
                when {
                    expectText != null -> {
                        val found = device.wait(Until.findObject(By.text(expectText)), 8000)
                        if (found == null) evidence("after-hold-${settings.artworkHold}")
                        assertNotNull("'$expectText' did not appear after the hold", found)
                    }
                    expectDescription != null -> {
                        val found = device.wait(Until.findObject(By.desc(expectDescription)), 8000)
                        if (found == null) evidence("after-hold-${settings.artworkHold}")
                        assertNotNull("'$expectDescription' did not appear after the hold", found)
                    }
                    else -> {
                        SystemClock.sleep(1500)
                        assertNull(device.findObject(By.text("Configure tap action")))
                    }
                }
                assertNull("The system crash dialog appeared", device.findObject(By.textContains("keeps stopping")))
                assertTrue(connection.state.value.trackId == current.id)
                if (expectText != null || expectDescription != null) {
                    device.pressBack()
                    SystemClock.sleep(600)
                }
                assertNotNull("The player is gone after the hold", device.wait(Until.findObject(By.desc("Show lyrics")), 5000)
                    ?: device.findObject(By.desc("Hide lyrics")))
            }
        } finally {
            playback?.let { connection -> instrumentation.runOnMainSync { connection.clear(); connection.close() } }
            tracks.forEach { File(requireNotNull(Uri.parse(it.uri).path)).delete() }
            context.getSharedPreferences("interface", 0).edit().clear().commit()
            preferences.write(original)
        }
    }
}
