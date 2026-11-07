package io.github.anastaci1a.localica

import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.rules.ExternalResource

/**
 * Restarts the process of the device's selected system keyboard. The system binds a fresh
 * instance at the next input start, and that instance reads the current secure settings.
 *
 * The system IME is shared device state that outlives every instrumentation, package reset
 * and app process. Two leaks need this:
 * - After a long shard, the API 36 emulator's LatinIME can report its whole window as the IME
 *   inset while drawing nothing (#638). `ime reset` keeps that process; force-stopping it doesn't.
 * - A running IME learns of a `show_ime_with_hard_keyboard` change only through a content
 *   observer, which Android can deliver seconds later. A fixture that restores the policy
 *   leaves the next test with a keyboard that still uses the old policy and then switches
 *   mid-test (#593). Call this after restoring the policy so the next editor sees it at once.
 *
 * Only a system keyboard is restarted: a test-owned IME from this APK manages its own lifecycle.
 */
internal fun restartSystemIme() {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText().trim() }
    val keyboard = shell("settings get secure default_input_method").substringBefore('/')
    if (keyboard.isEmpty() || keyboard == "null" || keyboard == instrumentation.context.packageName) return
    // UiAutomation tokenizes directly, so a package name is a single literal argument.
    check(keyboard.matches(Regex("[A-Za-z0-9_.]+"))) { "Unexpected system keyboard package: $keyboard" }
    shell("am force-stop $keyboard")
}

/** Starts a class with a fresh system keyboard process (#638). See [restartSystemIme]. */
internal class FreshSystemIme : ExternalResource() {
    override fun before() = restartSystemIme()
}
