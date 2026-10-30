package io.github.anastaci1a.localica

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.navigation.GlassAppearance
import io.github.anastaci1a.localica.navigation.GlassRendererMode
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class GlassPreparationProfileTest {
    @get:Rule val compose = createComposeRule()

    @Test fun actualLegacyStagesReportSizesAliasesCacheAndWorkAcrossChanges() {
        val recorder = GlassPreparationWorkRecorder()
        val runtime = GlassRendererRuntime(GlassRendererMode.Legacy)
        var appearance by mutableStateOf(GlassAppearance(blur = 0f, whiteTint = 0f, contrast = 0f))
        var changed by mutableStateOf(false)
        var mounted by mutableStateOf(true)
        lateinit var source: FrostedBackdrop
        var primary: Throwable? = null
        recorder.phase("clear-initial")
        try {
            compose.setContent {
                CompositionLocalProvider(LocalGlassRendererRuntime provides runtime,
                    LocalGlassPreparationWorkObserver provides recorder) {
                    LocalicaTheme { CompositionLocalProvider(LocalGlassAppearance provides appearance) {
                        if (mounted) Box(Modifier.fillMaxSize()) {
                            source = rememberFrostedBackdrop(changed)
                            Canvas(Modifier.fillMaxSize().recordFrostedBackdrop(source)) {
                                drawRect(if (changed) Color.Blue else Color.Red, size = Size(size.width / 2, size.height))
                                drawRect(if (changed) Color.Red else Color.Blue, topLeft = Offset(size.width / 2, 0f),
                                    size = Size(size.width / 2, size.height))
                            }
                            LiquidGlassSurface(Modifier.align(Alignment.Center).fillMaxWidth().height(160.dp)
                                .testTag("preparation-lens"), source, frostProgress = .5f, refraction = 0f)
                        }
                    } }
                }
            }
            fun awaitPrepared(name: String) {
                compose.waitUntil(15_000) {
                    val snapshot = source.snapshot
                    snapshot != null && recorder.prepared(name, System.identityHashCode(snapshot), appearance.whiteTint) &&
                        (if (appearance.blur == 0f) source.glassFallback == null else source.glassFallback != null)
                }
                compose.onNodeWithTag("preparation-lens").assertIsDisplayed()
            }
            fun inspectScene(expectBlue: Boolean) {
                val pixels = compose.onNodeWithTag("preparation-lens").captureToImage().toPixelMap()
                val color = pixels[pixels.width / 4, pixels.height / 2]
                assertTrue("Retain distinct scene color: $color", if (expectBlue) color.blue > color.red + .12f else color.red > color.blue + .12f)
            }
            awaitPrepared("clear-initial")
            compose.waitUntil(15_000) { recorder.has("clear-initial", "capture-settled") }
            awaitPrepared("clear-initial")
            inspectScene(false)
            fun settings(name: String, next: GlassAppearance) {
                val needsPreparation = next.blur != appearance.blur || next.whiteTint != appearance.whiteTint
                compose.runOnIdle { recorder.phase(name); appearance = next }
                if (needsPreparation) awaitPrepared(name)
                else compose.waitUntil(10_000) { recorder.has(name, "tone-output-endpoints") }
                inspectScene(changed)
            }
            settings("blur-tint-tone", appearance.copy(blur = .3f, whiteTint = .15f, contrast = .25f))
            compose.waitUntil(10_000) { recorder.has("blur-tint-tone", "tone-completed") }
            settings("clear-tint-tone", appearance.copy(blur = 0f))
            settings("clear-raw", appearance.copy(whiteTint = 0f, contrast = 0f))
            settings("clear-tone-again", appearance.copy(whiteTint = 0f, contrast = .25f))
            compose.waitUntil(10_000) { recorder.has("clear-tone-again", "tone-completed") }
            settings("clear-tone-off", appearance.copy(contrast = 0f))
            settings("clear-tone-warm", appearance.copy(contrast = .25f))
            compose.waitUntil(10_000) { recorder.has("clear-tone-warm", "tone-composition-hit") }
            val previous = compose.runOnIdle { source.capture }
            compose.runOnIdle { recorder.phase("changed-source"); changed = true }
            compose.waitUntil(15_000) { source.capture != null && source.capture !== previous }
            awaitPrepared("changed-source")
            compose.waitUntil(15_000) { recorder.has("changed-source", "capture-settled") }
            awaitPrepared("changed-source")
            inspectScene(true)
            compose.runOnIdle { recorder.phase("renderer-request-modern"); runtime.request(GlassRendererMode.Modern) }
            compose.waitForIdle()
            compose.runOnIdle { recorder.phase("renderer-return-legacy"); runtime.request(GlassRendererMode.Legacy) }
            // API26 remains Legacy for both requests; on API36 the epoch boundary owns new preparation.
            if (android.os.Build.VERSION.SDK_INT >= 33) awaitPrepared("renderer-return-legacy")
            inspectScene(true)
        } catch (failure: Throwable) {
            primary = failure
            throw failure
        } finally {
            var followup: Throwable? = null
            fun cleanup(action: () -> Unit) {
                try { action() } catch (failure: Throwable) {
                    val old = followup
                    if (old == null) followup = failure else if (old !== failure) old.addSuppressed(failure)
                }
            }
            cleanup { compose.runOnIdle { recorder.phase("dispose"); mounted = false }; compose.waitForIdle() }
            cleanup {
                val document = recorder.seal(primary)
                    .put("cleanupFailureBeforeSeal", followup?.javaClass?.name ?: org.json.JSONObject.NULL)
                recorder.export(document, nativeEvidenceDirectory("glass-preparation-work",
                    InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null)))
                assertEquals("Do not silently lose measurements", 0, document.getInt("droppedRecords"))
                assertEquals("Metadata conversion must succeed", 0, document.getJSONArray("observationFailures").length())
            }
            followup?.let { failure ->
                val original = primary
                if (original == null) throw failure else if (original !== failure) original.addSuppressed(failure)
            }
        }
    }
}
