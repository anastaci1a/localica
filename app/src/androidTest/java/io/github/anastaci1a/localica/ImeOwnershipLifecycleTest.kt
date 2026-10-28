package io.github.anastaci1a.localica

import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsets as NativeInsets
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestName
import java.io.File

/** Diagnostic cases with real native event witnesses; NOT a product correction. */
class ImeOwnershipLifecycleTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val name = TestName()
    private val events = mutableListOf<String>()
    private var host: View? = null
    private var installed by mutableStateOf(true)
    private var witness: NativeImeOwnershipWitness? = null
    private var screenshotSequence = 0
    private fun output(): File {
        val extra = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
        val root = extra?.takeIf { it.isNotBlank() }?.let(::File) ?: requireNotNull(compose.activity.getExternalFilesDir(null))
        return File(root, "ime-ownership").apply { mkdirs() }
    }
    private fun log(value: String) { events += "${SystemClock.uptimeMillis()} $value" }
    private fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
    ).bufferedReader().use { it.readText().trim() }
    private fun shown() = compose.runOnUiThread {
        val i = host?.rootWindowInsets
        host?.hasWindowFocus() == true && i?.isVisible(NativeInsets.Type.ime()) == true &&
            i.getInsets(NativeInsets.Type.ime()).bottom > 0
    }
    private fun awaitShown() {
        compose.waitUntil(10000) { shown() }
        var dump = ""
        compose.waitUntil(5000) {
            dump = shell("dumpsys -t 2 input_method")
            nativeImeWindowVisible(dump) == true &&
                Regex("\\bmIsInputViewShown=(true|false)\\b").find(dump)?.groupValues?.get(1) == "true"
        }
        File(output(), "${name.methodName}-${++screenshotSequence}-input-method.txt").writeText(dump)
        val bitmap = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        try { File(output(), "${name.methodName}-$screenshotSequence.png").outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        } } finally { bitmap.recycle() }
        compose.runOnUiThread { witness?.snapshot("visible") }
    }
    private fun hide() = compose.runOnUiThread {
        host?.windowInsetsController?.hide(NativeInsets.Type.ime())
    }
    private fun awaitUnfocused() {
        compose.waitUntil(10000) {
            !shown() && compose.onAllNodes(hasSetTextAction() and isFocused()).fetchSemanticsNodes().isEmpty()
        }
    }
    private fun fixture(prepare: ((android.view.WindowInsetsAnimation) -> Unit)? = null) {
        compose.setContent {
            val view = LocalView.current
            SideEffect { host = view }
            if (installed) CompositionLocalProvider(LocalTextEditingObservation provides { log("input $it") }) {
                TextEditingFocusHost {
                    Column(Modifier.fillMaxSize().padding(24.dp)) {
                        BasicTextField("First", {}, Modifier.testTag("first").clearTextFocusOnBack().padding(24.dp))
                        BasicTextField("Second", {}, Modifier.testTag("second").clearTextFocusOnBack().padding(24.dp))
                    }
                }
            }
        }
        compose.runOnUiThread { witness = NativeImeOwnershipWitness(requireNotNull(host), ::log, prepare) }
    }
    private fun controlled(scales: String = "1", block: () -> Unit) {
        check(Build.VERSION.SDK_INT >= 31) { "Isolated native lifecycle experiment requires API31+" }
        val settings = listOf("secure show_ime_with_hard_keyboard", "global animator_duration_scale",
            "global transition_animation_scale", "global window_animation_scale")
        val original = settings.associateWith { shell("settings get $it") }
        check(original.values.all { it == "null" || it.matches(Regex("-?[0-9]+(?:\\.[0-9]+)?")) })
        var primary: Throwable? = null
        try {
            settings.forEach { key ->
                val target = if (key.startsWith("secure")) "1" else scales
                shell("settings put $key $target")
                assertEquals(target.toFloat(), shell("settings get $key").toFloat(), 0f)
            }
            prepareNativeDockedKeyboard(compose, events, ::shell)
            block()
        } catch (error: Throwable) { primary = error; throw error }
        finally {
            var cleanup: Throwable? = null
            fun clean(action: () -> Unit) { try { action() } catch (e: Throwable) {
                if (cleanup == null) cleanup = e else cleanup!!.addSuppressed(e)
            } }
            clean {
                compose.runOnUiThread {
                    installed = false
                    host?.windowInsetsController?.hide(NativeInsets.Type.ime())
                }
                compose.waitForIdle()
                compose.waitUntil(10000) { compose.runOnUiThread {
                    host == null || host?.rootWindowInsets?.isVisible(NativeInsets.Type.ime()) == false
                } }
            }
            clean { compose.runOnUiThread { witness?.dispose(); witness = null } }
            original.forEach { (key, value) -> clean {
                shell(if (value == "null") "settings delete $key" else "settings put $key $value")
                assertEquals("Restored $key", value, shell("settings get $key"))
            } }
            clean {
                val dir = output()
                File(dir, "${name.methodName}.txt").writeText(compose.runOnUiThread { events.toList() }.joinToString("\n"))
                File(dir, "${name.methodName}-ime-logcat.txt").writeText(shell("logcat -d -v threadtime -s ImeTracker:I InputMethodManager:I *:S"))
                File(dir, "${name.methodName}-restored.txt").writeText(original.entries.joinToString("\n") {
                    "${it.key}: expected=${it.value} actual=${shell("settings get ${it.key}")}"
                })
            }
            cleanup?.let { if (primary == null) throw it else primary.addSuppressed(it) }
        }
    }
    private fun transfer() {
        fixture(); compose.onNodeWithTag("first").performClick(); awaitShown()
        compose.onNodeWithTag("second").performClick(); awaitShown()
        compose.onNodeWithTag("second").assertIsFocused()
        compose.runOnUiThread { witness?.snapshot("transferred") }
        hide(); awaitUnfocused()
        assertEquals(1, compose.runOnUiThread { events.count { "endEditing reason=imeObservation" in it } })
    }
    @Test fun alreadyVisibleDockedTransferThenNativeHide() = controlled { transfer() }
    @Test fun zeroAnimationScaleDockedTransferThenNativeHide() = controlled("0") { transfer() }

    @Test fun cancelledBeforeStartDoesNotDonateExposureToReopenedEditor() = controlled {
        var canceled: Int? = null
        fixture { animation ->
            if (canceled == null) {
                canceled = System.identityHashCode(animation)
                log("requested-native-cancel animation=$canceled")
                host?.windowInsetsController?.hide(NativeInsets.Type.ime())
            }
        }
        compose.onNodeWithTag("first").performClick()
        compose.waitUntil(5000) { compose.runOnUiThread { canceled?.let { it in requireNotNull(witness).ended } == true } }
        assertFalse("Coverage requires actual prepare cancellation, not a completed show",
            compose.runOnUiThread { requireNotNull(canceled) in requireNotNull(witness).started })
        val beforeReopen = compose.runOnUiThread { events.count { "endEditing reason=" in it } }
        compose.onNodeWithTag("second").performClick(); awaitShown()
        compose.onNodeWithTag("second").assertIsFocused()
        assertEquals("Cancelled opening must not donate an editing end to the reopened editor", beforeReopen,
            compose.runOnUiThread { events.count { "endEditing reason=" in it } })
        val beforeNativeHide = compose.runOnUiThread { events.count { "endEditing reason=imeObservation" in it } }
        hide(); awaitUnfocused()
        assertEquals("Native hide ends the reopened editor exactly once", beforeNativeHide + 1,
            compose.runOnUiThread { events.count { "endEditing reason=imeObservation" in it } })
        assertEquals(beforeReopen + 1,
            compose.runOnUiThread { events.count { "endEditing reason=" in it } })
    }

    @Test fun nativeBackDuringOpeningIsRecordedBeforeShowEnds() = controlled {
        var id: Int? = null
        fixture { animation -> if (id == null) id = System.identityHashCode(animation) }
        compose.onNodeWithTag("first").performClick()
        compose.waitUntil(5000) { compose.runOnUiThread { id != null } }
        val pendingBeforeInjection = compose.runOnUiThread {
            val pending = requireNotNull(id) !in requireNotNull(witness).ended
            log("inject-native-back animation=$id beforeEnd=$pending")
            pending
        }
        assertTrue("Coverage requires Back requested during opening, not after settling", pendingBeforeInjection)
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.runOnUiThread {
            // Valid native Back may cancel/end this show synchronously before send returns.
            // Record that result; requiring it to remain pending would reject correct cancellation.
            log("native-back-delivered animation=$id ended=${requireNotNull(id) in requireNotNull(witness).ended}")
        }
        awaitUnfocused()
        compose.onNodeWithTag("second").performClick(); awaitShown()
        compose.onNodeWithTag("second").assertIsFocused()
        hide(); awaitUnfocused()
    }
}
