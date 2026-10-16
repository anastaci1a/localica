package io.github.anastaci1a.localica

import android.content.Intent
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/** No Compose test rule: the real window Recomposer must own the system-scale observer. */
class EffectiveMotionWindowTest {
    private data class Sample(val scale: Float?, val reduced: Boolean, val shape: GlassShape)

    @Test fun nativeWindowPolicyUpdatesHeldBodyAndPreservesExplicitAppPreference() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val original = Settings.Global.getString(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE)
        fun validScale(value: String) = value.matches(Regex("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?")) &&
            value.toFloatOrNull()?.let { it.isFinite() && it >= 0f } == true
        require(original == null || validScale(original)) { "Cannot safely restore original animator scale: $original" }
        val sample = AtomicReference<Sample?>()
        val recordRelease = AtomicBoolean(false)
        val releasePeak = AtomicReference(0f)
        var appReduced by mutableStateOf(false)
        var contact by mutableFloatStateOf(0f)
        var pressure by mutableFloatStateOf(0f)
        fun update(block: () -> Unit) = instrumentation.runOnMainSync(block)
        fun await(label: String, predicate: (Sample) -> Boolean) {
            val deadline = SystemClock.uptimeMillis() + 5000
            while (SystemClock.uptimeMillis() < deadline) {
                if (sample.get()?.let(predicate) == true) return
                SystemClock.sleep(20)
            }
            fail("$label; last window observation=${sample.get()}")
        }
        fun writeScale(value: String?) {
            // Test-only shell authority; production adds no settings permission or observer.
            require(value == null || validScale(value))
            // executeShellCommand tokenizes directly; shell quotes would be stored literally.
            val command = if (value == null) "settings delete global animator_duration_scale"
                else "settings put global animator_duration_scale $value"
            ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
                .bufferedReader().use { it.readText() }
        }
        fun neutral(s: Sample) = abs(s.shape.strain) < .00001f && abs(s.shape.depth) < .00001f
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use { scenario ->
            var failure: Throwable? = null
            try {
                scenario.onActivity { activity -> activity.setContent {
                    val system = rememberCoroutineScope().coroutineContext[MotionDurationScale]
                    val reduced = rememberEffectiveReducedMotion(appReduced)
                    LocalicaTheme {
                        CompositionLocalProvider(LocalReducedMotion provides reduced) {
                            val shape by rememberGlassBody(position = { 0f }, pressure = { pressure }, contact = { contact })
                            SideEffect {
                                sample.set(Sample(system?.scaleFactor, reduced, shape))
                                if (recordRelease.get()) releasePeak.updateAndGet { maxOf(it, abs(shape.strain), abs(shape.depth)) }
                            }
                            Box(Modifier.size(100.dp))
                        }
                    }
                } }
                await("Actual window must supply MotionDurationScale") { it.scale != null }
                // Change while attached: Compose caches its scale flow between window subscribers.
                // Two distinct values also avoid accepting an accidentally stale initial value.
                writeScale("2")
                await("Window observer receives scale two") { it.scale == 2f && !it.reduced }
                writeScale("1")
                await("Window observer receives scale one") { it.scale == 1f && !it.reduced }
                update { contact = 1f; pressure = -.15f }
                await("Ordinary held contact and edge pressure deform") { !neutral(it) }
                writeScale("0")
                await("System disable resets a held body") { it.scale == 0f && it.reduced && neutral(it) }
                recordRelease.set(true)
                update { contact = 0f; pressure = 0f }
                await("Release under reduction remains neutral") { it.reduced && neutral(it) }
                // Check again after several real frames, not merely the first reset sample.
                SystemClock.sleep(250)
                assertTrue("No transient or delayed release rebound: ${releasePeak.get()}", releasePeak.get() < .00001f)
                recordRelease.set(false)
                update { contact = 1f; pressure = -.15f }
                writeScale("1")
                await("Re-enable resumes ordinary held deformation") { it.scale == 1f && !it.reduced && !neutral(it) }
                update { appReduced = true }
                await("App reduction resets held contact with system enabled") { it.scale == 1f && it.reduced && neutral(it) }
                writeScale("0")
                await("Both requests reduce motion") { it.scale == 0f && it.reduced && neutral(it) }
                writeScale("1")
                await("System re-enable cannot override app preference") { it.scale == 1f && it.reduced && neutral(it) }
                update { appReduced = false }
                await("Both requests cleared restore deformation") { !it.reduced && !neutral(it) }
            } catch (thrown: Throwable) {
                failure = thrown
                throw thrown
            } finally {
                // Restore while the actual observer is still alive, even after assertion failure.
                val restored = runCatching {
                    update { contact = 0f; pressure = 0f }
                    writeScale(original)
                    assertEquals("Restore exact setting, including absence", original,
                        Settings.Global.getString(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE))
                    await("Window observes restored system policy") { it.scale == (original?.toFloatOrNull() ?: 1f) }
                }.exceptionOrNull()
                if (restored != null) { if (failure != null) failure.addSuppressed(restored) else throw restored }
            }
        }
    }
}
