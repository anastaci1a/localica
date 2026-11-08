package io.github.anastaci1a.localica

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalView
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.navigation.InterfaceSettings
import io.github.anastaci1a.localica.playback.PlaybackState
import io.github.anastaci1a.localica.ui.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlin.coroutines.EmptyCoroutineContext
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Functional #680 probe. Native screenshots and wall time after Current do not pump the Compose clock. */
class HeaderGlassFreshnessTest {
    private val liveEffects = InstrumentationRegistry.getArguments().getString("glassLiveEffects", "true").toBoolean()
    @get:Rule val compose = localicaComposeRule(if (liveEffects) Dispatchers.Main.immediate else EmptyCoroutineContext)

    @Test fun artworkSwitchWithoutUnrelatedRedraw() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        val reduced = args.getString("glassReduced", "true").toBoolean()
        val root = args.getString("additionalTestOutputDir")?.let(::File)
            ?: requireNotNull(instrumentation.targetContext.getExternalFilesDir(null))
        val output = File(root, "header-glass-680")
        output.mkdirs()
        val polarity = PlayerBackgroundPolarityState()
        val colors = listOf(0x3A0CA3, 0xFFE600, 0xF6F5F3, 0x0B0B0D)
        val visuals = colors.map { color ->
            val bitmap = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF000000.toInt() or color) }
            PlayerVisual(bitmap.asImageBitmap(), runBlocking { playerPalette(bitmap) })
        }
        var index by mutableIntStateOf(0)
        var view: android.view.View? = null
        var latest = "none"
        var samples = 0
        val log = File(output, "receipt.txt")
        log.writeText("sdk=${android.os.Build.VERSION.SDK_INT} reduced=$reduced liveEffects=$liveEffects\n")
        compose.setContent { LocalicaTheme {
            view = LocalView.current
            CompositionLocalProvider(LocalGlassSurfaceDrawObserver provides { receipt ->
                samples++
                latest = "${receipt}"
            }) {
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val progress = remember { Animatable(1f) }
                    PlayerSurface(PlaybackState(title = "Header freshness"), true, {}, InterfaceSettings(reducedMotion = reduced), progress,
                        {}, {}, {}, {}, {}, {}, visual = visuals[index], backgroundPolarity = polarity) {}
                }
            }
        } }
        fun screenshot(name: String) {
            val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
            File(output, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        for (i in visuals.indices) {
            val start = SystemClock.uptimeMillis()
            compose.runOnIdle { index = i }
            compose.waitForIdle()
            compose.waitUntil(8000) {
                val frame = polarity.frame
                polarity.decision.confidence == PlayerBackgroundConfidence.Current && frame != null &&
                    !frame.transitioning && frame.roles.isNotEmpty() && frame.roles.all { it.visual == visuals[i] }
            }
            val current = SystemClock.uptimeMillis()
            log.appendText("index=$i Current after=${current-start}ms\n")
            for (delay in listOf(0L, 200L, 500L, 1000L, 2000L, 4000L)) {
                val wait = current + delay - SystemClock.uptimeMillis()
                if (wait > 0) Thread.sleep(wait)
                screenshot("$i-after-$delay")
                log.appendText("index=$i wall=${SystemClock.uptimeMillis()-current}ms draws=$samples last=$latest\n")
            }
            instrumentation.runOnMainSync { view?.invalidate() }
            Thread.sleep(250)
            screenshot("$i-native-invalidate")
            log.appendText("index=$i afterNativeInvalidate draws=$samples last=$latest\n")
        }
        assertTrue("All transitions reached Current", log.readText().count { it == '\n' } > 4)
    }
}
