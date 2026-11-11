package io.github.anastaci1a.localica

import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.rules.ExternalResource
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

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
internal fun ComposeTestRule.awaitSystemImeHardKeyboardPolicy(
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

/**
 * Own the docked system input view before fixture Activities exist. API 36 LatinIME can report
 * onShown and complete an inset animation while hardware-keyboard policy suppresses its input view;
 * every inset is then zero. A preceding keyboard fixture can mask that missing precondition.
 * Pair with [FreshSystemIme] inside this rule so the next bind reads the owned policy immediately.
 * Restore the exact setting after Activities retire, then discard this fixture's keyboard cache.
 */
internal class NativeImePolicy : TestRule {
    override fun apply(base: Statement, description: Description): Statement = object : Statement() {
        override fun evaluate() {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
                instrumentation.uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText().trim() }
            val key = "show_ime_with_hard_keyboard"
            val original = shell("settings get secure $key")
            check(original == "null" || original.matches(Regex("-?[0-9]+"))) {
                "Cannot restore native keyboard policy: $original"
            }
            val selected = shell("settings get secure default_input_method")
            val events = mutableListOf<String>()
            var failure: Throwable? = null
            try {
                shell("settings put secure $key 1")
                check(shell("settings get secure $key") == "1") { "Native keyboard policy must precede Activities" }
                events += "before-activities $key=1 original=$original"
                base.evaluate()
            } catch (caught: Throwable) {
                failure = caught
            } finally {
                try {
                    if (original == "null") shell("settings delete secure $key")
                    else shell("settings put secure $key $original")
                    check(shell("settings get secure $key") == original) { "Restore native keyboard policy" }
                    events += "after-activities $key=$original"
                } catch (caught: Throwable) {
                    val primary = failure
                    if (primary == null) failure = caught else primary.addSuppressed(caught)
                }
                try {
                    restartSystemKeyboard(selected)
                    events += "keyboard-cache-retired selected=$selected"
                } catch (caught: Throwable) {
                    val primary = failure
                    if (primary == null) failure = caught else primary.addSuppressed(caught)
                }
                try {
                    val directory = nativeEvidenceDirectory("popup-ime-policy",
                        instrumentation.targetContext.getExternalFilesDir(null))
                    java.io.File(directory, "class-policy-api${android.os.Build.VERSION.SDK_INT}.txt")
                        .writeText(events.joinToString("\n", postfix = "\n"))
                } catch (caught: Throwable) {
                    val primary = failure
                    if (primary == null) failure = caught else primary.addSuppressed(caught)
                }
            }
            failure?.let { throw it }
        }
    }
}
