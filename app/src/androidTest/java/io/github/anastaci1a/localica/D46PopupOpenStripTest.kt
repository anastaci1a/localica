package io.github.anastaci1a.localica

import android.os.Build
import android.view.Window
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import io.github.anastaci1a.localica.ui.*
import io.github.anastaci1a.localica.ui.engine.*
import io.github.anastaci1a.localica.ui.spec.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import kotlin.math.roundToInt

/** Native original images at controlled Engine sampling cadences. The platform
 * compositor is not claimed to refresh at 120 Hz: the Engine clock advances 1/120 s
 * per sample, then the test waits for real native submission at that fixed state.
 * Reuses the existing EngineHarness; all fixture owners borrow its one clock.
 */
class D46PopupOpenStripTest {
    @get:Rule val compose = localicaComposeRule()
    @Test fun openingAtSixtyEngineFramesPerSecond() = strip(60)
    @Test fun openingAtOneTwentyEngineFramesPerSecond() = strip(120)

    private fun strip(hz: Int) {
        lateinit var harness: EngineHarness
        lateinit var anchor: GlassAnchor
        lateinit var preparation: GlassOriginPreparation
        var window: Window? = null
        var open by mutableStateOf(false)
        compose.setContent { EngineTheme(clear = true) {
            val spec = LocalUiSpec.current.spec
            val owned = remember(spec) { EngineHarness(spec = spec) }
            SideEffect { harness = owned }
            DisposableEffect(owned) { onDispose { owned.close() } }
            CompositionLocalProvider(LocalClockOwner provides owned.clock, LocalUiClock provides owned.clock,
                LocalEngineContext provides EngineContext(1f / hz, 1000f, spec.access)) {
                anchor = rememberGlassAnchor()
                preparation = rememberGlassOriginPreparation()
                val backdrop = rememberFrostedBackdrop(Unit)
                Box(Modifier.fillMaxSize().testTag("d46-strip-scene")) {
                    Canvas(Modifier.fillMaxSize().recordFrostedBackdrop(backdrop)) { drawRect(Color(0xFFB7D5DC)) }
                    CompositionLocalProvider(LocalFrostedBackdrop provides backdrop) {
                        Box(Modifier.padding(32.dp)) {
                            LyricsHeaderActions(true, 1f, anchor, preparation, {}, { open = true }, {})
                            GlassMenu(open, anchor, { open = false }, originPreparation = preparation) {
                                val view = androidx.compose.ui.platform.LocalView.current
                                val actual = (view.parent as DialogWindowProvider).window
                                DisposableEffect(actual) {
                                    window = actual
                                    onDispose { if (window === actual) window = null }
                                }
                                ActionMenuItem("Embedded lyrics", icon = AppIcon.ActionLyrics) { open = false }
                            }
                        }
                    }
                }
            }
        } }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        val folder = nativeEvidenceDirectory("d46-popup-strips",
            instrumentation.targetContext.getExternalFilesDir("d46-popup-strips"))
        val output = File(folder, "api${Build.VERSION.SDK_INT}-${hz}hz")
        check(output.isDirectory || output.mkdirs())
        val frames = JSONArray()
        val report = JSONObject().put("api", Build.VERSION.SDK_INT).put("engineHz", hz)
            .put("displayRefreshClaim", false).put("source", "one original composed display per controlled Engine sample")
            .put("frames", frames)
        fun save() = File(output, "frames.json").writeText(report.toString(2))
        var finished = 0
        try {
            compose.runOnUiThread { open = true }
            for (frame in 0 until hz * 2) {
                compose.runOnUiThread { harness.advance(1.0 / hz) }
                // Compose's scheduler applies the new Engine state; it does not
                // advance the fixture's independent, controlled Engine frame source.
                compose.mainClock.advanceTimeByFrame()
                compose.waitForIdle()
                val active = compose.runOnUiThread { window?.takeIf { it.decorView.isAttachedToWindow } }
                fun state() = ShadowDrawState(harness.clock.stampNow.sequence, 0)
                val extras = listOfNotNull(active?.let { popup ->
                    ShadowNativeWindow(popup, popup.decorView, IntOffset(18, 1), popup = {
                        val p = anchor.contact.expansion.value
                        PopupShadowComposition(if (anchor.modalPresent)
                            PopupShadowScrim(p, ((.16f * p) * 255f).roundToInt()) else null)
                    }) { state() }
                })
                val image = ShadowNativeCapture(compose, compose.onNodeWithTag("d46-strip-scene"), extras) {
                    harness.clock.stampNow.sequence
                }.use { capture -> capture.capture(popup = active?.let { popup ->
                    { PopupShadowComposition(higherWindows = listOf(popup)) }
                }) { state() } }
                val bounds = compose.onAllNodesWithTag("glass-morph-surface", useUnmergedTree = true)
                    .fetchSemanticsNodes().singleOrNull()?.boundsInRoot
                val item = compose.runOnUiThread {
                    JSONObject().put("frame", frame).put("engineNanos", harness.now)
                        .put("popupPhase", anchor.contact.expansion.value).put("preparation", preparation.progress)
                        .put("modal", anchor.modalPresent).put("hidden", anchor.hidden)
                        .put("visibleBoundsInWindow", bounds?.toString() ?: JSONObject.NULL)
                        .put("nativeWindows", JSONArray(image.nativeWindows.map { receipt ->
                            JSONObject().put("window", receipt.windowId).put("token", receipt.generation.token)
                                .put("marker", receipt.generation.marker.toString())
                                .put("nativeDrawVsyncNanos", receipt.generation.drawVsyncNanos)
                                .put("engineSequence", receipt.state.sequence).put("hostEpoch", receipt.state.epoch)
                                .put("popupComposition", receipt.popup?.toString() ?: JSONObject.NULL)
                        }))
                }
                File(output, "frame-${frame.toString().padStart(3, '0')}.png").outputStream().use {
                    check(image.image.asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
                }
                frames.put(item); save()
                finished = if (compose.runOnUiThread {
                    anchor.modalPresent && preparation.progress == 1f && anchor.contact.expansion.value == 1f
                }) finished + 1 else 0
                if (finished >= 2) break
            }
            assertTrue("The real native opening reaches its endpoint", finished >= 2)
            report.put("status", "captured-original-native-frames"); save()
        } catch (failure: Throwable) {
            report.put("status", "incomplete").put("failure", failure.toString()); save()
            throw failure
        } finally {
            compose.runOnUiThread { open = false }
            compose.mainClock.autoAdvance = true
        }
    }
}
