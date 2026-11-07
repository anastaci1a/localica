package io.github.anastaci1a.localica

import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
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

/**
 * Waits until the bound system keyboard has observed a restored `show_ime_with_hard_keyboard`
 * policy (#593). A running IME learns of the change only through a content observer that
 * Android can deliver seconds later. Until then it keeps the previous test's policy: on the API 36
 * hardware-keyboard emulator it shows a full keyboard for the next editor, then collapses it
 * mid-edit when the late notification lands, which an editor reads as the user hiding the keyboard.
 *
 * Restarting the IME instead is not an option for docked-keyboard fixtures: after `am force-stop`
 * the system can take longer than a setup budget to bind a fresh keyboard.
 */
internal fun AndroidComposeTestRule<*, *>.awaitSystemImeHardKeyboardPolicy(
    shell: (String) -> String, showWithHardKeyboard: Boolean,
) {
    // InputMethodService.SettingsObserver: 0 unknown (read at next use), 1 false, 2 true.
    // No observer line means no bound keyboard; the next bind reads the current setting.
    val settled = setOf("0", if (showWithHardKeyboard) "2" else "1")
    var observed: String? = null
    try {
        waitUntil(20_000) {
            observed = Regex("""SettingsObserver\{mShowImeWithHardKeyboard=(\d+)\}""")
                .find(shell("dumpsys -t 2 input_method"))?.groupValues?.get(1)
            observed == null || observed in settled
        }
    } catch (timeout: ComposeTimeoutException) {
        throw AssertionError("The system keyboard never observed show_ime_with_hard_keyboard=" +
            "$showWithHardKeyboard (observer state $observed)", timeout)
    }
}
