package io.github.anastaci1a.localica

import android.view.Window
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.*
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import io.github.anastaci1a.localica.library.LibrarySnapshot
import io.github.anastaci1a.localica.library.Track
import io.github.anastaci1a.localica.navigation.*
import io.github.anastaci1a.localica.playback.PlaybackState
import io.github.anastaci1a.localica.playlist.PlaylistState
import io.github.anastaci1a.localica.ui.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.IdentityHashMap

/** Actual main utility routes, without replacing a native owner or forcing readiness. */
class UtilityPopupReturnTest {
    @get:Rule val compose = localicaComposeRule()

    @Test fun noneSettingsReturnRetiresTheChildAndRestoresRootInput() = route(GlassRendererMode.None, "Settings")
    @Test fun noneScanReturnRetiresTheChildAndRestoresRootInput() = route(GlassRendererMode.None, "Scan music")
    @Test fun ordinaryBackRetiresTheUtilityWindow() = route(GlassRendererMode.Normal, "Back")
    @Test fun outsideDismissalRetiresTheUtilityWindow() = route(GlassRendererMode.Normal, "Outside")
    @Test fun reopenDuringReturnKeepsTheNativeOwner() = route(GlassRendererMode.Normal, "Reopen")
    @Test fun reducedMotionRetiresTheUtilityWindow() = route(GlassRendererMode.Normal, "Back", reduced = true)

    private fun route(mode: GlassRendererMode, action: String, reduced: Boolean = false) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val preferences = InterfacePreferences(context)
        val previous = preferences.read()
        val device = UiDevice.getInstance(instrumentation)
        val windows = mutableListOf<Window>()
        val phases = IdentityHashMap<Window, Float>()
        lateinit var root: android.view.View
        lateinit var home: GlassPopupHome
        lateinit var runtime: GlassRendererRuntime
        val report = JSONObject().put("mode", mode.name).put("action", action).put("reducedMotion", reduced)
            .put("sourceBinding", "prototype-evidence/context.txt + test-input-apks.sha256")
        val output = File(nativeEvidenceDirectory("utility-popup-return", context.getExternalFilesDir(null)),
            "${mode.name}-$action-$reduced.json")
        fun flights(h: GlassPopupHome): List<GlassPopupFlight> = h.flights.toList() + h.children.flatMap(::flights)
        fun advanceUntil(message: String, condition: () -> Boolean) {
            compose.waitUntil(10_000) {
                compose.mainClock.advanceTimeByFrame()
                compose.waitForIdle()
                condition()
            }
            assertTrue(message, condition())
        }
        fun back(window: Window) {
            compose.waitUntil(5000) { window.decorView.hasWindowFocus() }
            assertTrue(device.pressBack())
        }
        fun outside() {
            val origin = IntArray(2).also { compose.runOnUiThread { root.getLocationOnScreen(it) } }
            assertTrue(device.click(origin[0] + root.width / 8, origin[1] + root.height / 3))
        }
        try {
            preferences.write(InterfaceSettings(reducedMotion = reduced, fasterMotion = false,
                glass = GlassAppearance(renderer = mode)))
            val track = Track("file:///missing-667.mp3", "One", artist = "Test", album = "One",
                fileName = "missing-667.mp3", relativePath = "missing-667.mp3")
            compose.setContent { LocalicaTheme {
                root = LocalView.current
                home = requireNotNull(LocalGlassPopupHome.current)
                runtime = requireNotNull(LocalGlassRendererRuntime.current)
                CompositionLocalProvider(LocalGlassMorphWorkObserver provides { kind, window, p ->
                    if (kind == "dialog" && window != null && windows.none { it === window }) windows += window
                    if (kind == "geometry" && window != null && p != null) phases[window] = p
                }) {
                    LocalicaScreen(LibraryUiState(LibrarySnapshot(tracks = listOf(track)), loading = false),
                        PlaybackState(connected = true, trackId = track.id, trackUri = track.id,
                            title = track.title, artist = track.artist, durationMs = 125000),
                        {}, {}, {}, {}, {}, {}, playlist = PlaylistState(loading = false))
                }
            } }
            compose.onNodeWithContentDescription("Library").performClick()
            compose.waitForIdle()
            compose.mainClock.advanceTimeBy(2000)
            compose.waitForIdle()
            compose.runOnUiThread {
                assertEquals(mode, runtime.requested)
                assertEquals(effectiveGlassBackend(mode, android.os.Build.VERSION.SDK_INT), runtime.generation.backend)
                report.put("actualBackend", runtime.generation.backend.name)
            }
            val more = compose.onNodeWithContentDescription("More").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            compose.mainClock.autoAdvance = false
            compose.onNodeWithContentDescription("More").performClick()
            advanceUntil("Utility window is fully open") {
                windows.firstOrNull()?.let { it.decorView.isAttachedToWindow && (phases[it] ?: 0f) >= .999f } == true
            }
            val popup = windows.first()
            compose.waitUntil(5000) { popup.decorView.hasWindowFocus() }
            val flight = compose.runOnUiThread { flights(home).single() }
            val token = compose.runOnUiThread { popup.decorView.windowToken }
            report.put("flightIdentity", System.identityHashCode(flight)).put("windowIdentity", System.identityHashCode(popup))
            when (action) {
                "Settings", "Scan music" -> compose.onNodeWithText(action, substring = false).performClick()
                "Outside" -> outside()
                else -> back(popup)
            }
            if (action == "Reopen") {
                compose.mainClock.advanceTimeBy(32)
                compose.waitForIdle()
                compose.runOnUiThread {
                    assertTrue("Reopen interrupts a real return", popup.decorView.isAttachedToWindow)
                    assertTrue("Return has intermediate geometry", (phases[popup] ?: 1f) in .001f.. .999f)
                }
                val origin = IntArray(2).also { compose.runOnUiThread { root.getLocationOnScreen(it) } }
                assertTrue(device.click(origin[0] + more.center.x.toInt(), origin[1] + more.center.y.toInt()))
                advanceUntil("Reopening returns to the same admitted native window") { (phases[popup] ?: 0f) >= .999f }
                compose.runOnUiThread {
                    assertSame(popup, windows.single())
                    assertSame(token, popup.decorView.windowToken)
                    assertSame(flight, flights(home).single())
                }
                back(popup)
            }
            if (!reduced) {
                advanceUntil("Real utility recording hands off before retirement") { compose.runOnUiThread { flight.handedOff } }
                report.put("observedHandoff", true)
            }
            advanceUntil("Utility native window retires") { !popup.decorView.isAttachedToWindow }
            compose.runOnUiThread {
                assertFalse("The retired flight leaves its source owner", flights(home).any { it === flight })
                assertNull("The retired flight releases its layer reference", flight.layer)
                assertFalse(flight.prepared)
            }
            if (action == "Settings" || action == "Scan music") {
                advanceUntil("Destination window gains native focus") {
                    windows.drop(1).any { it.decorView.isAttachedToWindow && it.decorView.hasWindowFocus() }
                }
                back(windows.last { it.decorView.isAttachedToWindow && it.decorView.hasWindowFocus() })
                advanceUntil("Destination and all descendants retire") { windows.none { it.decorView.isAttachedToWindow } }
            }
            compose.mainClock.advanceTimeBy(2000)
            compose.waitForIdle()
            compose.runOnUiThread { assertTrue("All child flights retire", flights(home).isEmpty()) }
            // A new native window proves that restored root input remains usable.
            compose.onNodeWithContentDescription("More").performClick()
            advanceUntil("Root input opens a fresh utility window") {
                windows.lastOrNull()?.let { it !== popup && it.decorView.isAttachedToWindow && (phases[it] ?: 0f) >= .999f } == true
            }
            outside()
            advanceUntil("Final native window cleanup completes") { windows.none { it.decorView.isAttachedToWindow } }
            compose.runOnUiThread { assertTrue(flights(home).isEmpty()) }
            report.put("status", "passed")
        } catch (failure: Throwable) {
            report.put("status", "failed").put("failure", failure.toString())
            throw failure
        } finally {
            output.writeText(report.toString(2))
            compose.mainClock.autoAdvance = true
            preferences.write(previous)
        }
    }
}
