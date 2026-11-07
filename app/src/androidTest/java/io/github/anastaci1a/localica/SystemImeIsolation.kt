package io.github.anastaci1a.localica

import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.rules.ExternalResource

/**
 * Starts a class with a fresh process for the device's selected system keyboard (#638).
 *
 * The system IME is shared device state that outlives every instrumentation, package reset
 * and app process. After a long shard, the API 36 emulator's LatinIME can report its whole
 * window as the IME inset while drawing nothing (the hardware keyboard suppresses its view).
 * Editors that honour IME padding then collapse to zero height. `ime reset` keeps that
 * process; force-stopping it makes the system bind a fresh instance at the next input start.
 *
 * Only a system keyboard is restarted: a test-owned IME from this APK manages its own lifecycle.
 */
internal class FreshSystemIme : ExternalResource() {
    override fun before() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText().trim() }
        val keyboard = shell("settings get secure default_input_method").substringBefore('/')
        if (keyboard.isEmpty() || keyboard == "null" || keyboard == instrumentation.context.packageName) return
        // UiAutomation tokenizes directly, so a package name is a single literal argument.
        check(keyboard.matches(Regex("[A-Za-z0-9_.]+"))) { "Unexpected system keyboard package: $keyboard" }
        shell("am force-stop $keyboard")
    }
}
