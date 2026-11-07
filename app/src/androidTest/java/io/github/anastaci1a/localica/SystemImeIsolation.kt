package io.github.anastaci1a.localica

import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.rules.ExternalResource

/**
 * Force-stops the system keyboard whose component is [selected] (a `package/service` id from
 * `default_input_method`) so the next input start binds a fresh instance. A null, blank or
 * test-owned id is ignored.
 */
internal fun restartSystemKeyboard(selected: String) {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val keyboard = selected.substringBefore('/')
    if (keyboard.isEmpty() || keyboard == "null" || keyboard == instrumentation.context.packageName) return
    // UiAutomation tokenizes directly, so a package name is a single literal argument.
    check(keyboard.matches(Regex("[A-Za-z0-9_.]+"))) { "Unexpected system keyboard package: $keyboard" }
    ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("am force-stop $keyboard"))
        .bufferedReader().use { it.readText() }
}

/**
 * Starts a class with a fresh process for the device's selected system keyboard (#638).
 *
 * The system IME is shared device state that outlives every instrumentation, package reset
 * and app process. After a long shard, the API 36 emulator's LatinIME can report its whole
 * window as the IME inset while drawing nothing (the hardware keyboard suppresses its view).
 * Editors that honour IME padding then collapse to zero height. `ime reset` keeps that
 * process; force-stopping it makes the system bind a fresh instance at the next input start.
 * The known polluter, FloatingImeLifecycleTest, restores the keyboard itself with
 * [restartSystemKeyboard]; this rule guards a class against any other source.
 *
 * Only a system keyboard is restarted: a test-owned IME from this APK manages its own lifecycle.
 */
internal class FreshSystemIme : ExternalResource() {
    override fun before() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val selected = ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand("settings get secure default_input_method"))
            .bufferedReader().use { it.readText().trim() }
        restartSystemKeyboard(selected)
    }
}
