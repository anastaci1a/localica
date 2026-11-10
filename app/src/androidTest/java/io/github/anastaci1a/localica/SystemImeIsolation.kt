package io.github.anastaci1a.localica

import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
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

/**
 * Owns the native-keyboard policy for a class whose measured subject is the system IME's inset
 * animation (#786): the soft keyboard is shown beside the emulator's hardware keyboard and the
 * animator scales are 1, so the platform dispatches [androidx.core.view.WindowInsetsAnimationCompat]
 * callbacks instead of jumping to the final inset. Earlier classes in a long shard restore these
 * settings, or leave them, at their own pace; the class must not inherit either state.
 * Place it before [FreshSystemIme] so the restarted keyboard reads this policy at its first bind.
 */
internal class NativeImePolicy : TestRule {
    private val settings = listOf("secure show_ime_with_hard_keyboard", "global animator_duration_scale",
        "global transition_animation_scale", "global window_animation_scale")
    private fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command))
        .bufferedReader().use { it.readText().trim() }

    override fun apply(base: Statement, description: Description): Statement = object : Statement() {
        override fun evaluate() {
            val original = linkedMapOf<String, String>()
            var failure: Throwable? = null
            try {
                for (setting in settings) {
                    val value = shell("settings get $setting")
                    check(value == "null" || value.toFloatOrNull() != null) { "Unexpected $setting: $value" }
                    original[setting] = value
                    shell("settings put $setting 1")
                    check(shell("settings get $setting").toFloat() == 1f) { "Could not set $setting" }
                    android.util.Log.i("NativeImePolicy", "Before activities: $setting=1 original=$value")
                }
                base.evaluate()
            } catch (caught: Throwable) {
                failure = caught
            } finally {
                // Restore every captured value even if setup or another restoration fails.
                for ((setting, value) in original.entries.reversed()) {
                    try {
                        shell(if (value == "null") "settings delete $setting" else "settings put $setting $value")
                        check(shell("settings get $setting") == value) { "Could not restore $setting" }
                        android.util.Log.i("NativeImePolicy", "After activities: $setting=$value")
                    } catch (caught: Throwable) {
                        if (failure == null) failure = caught else failure.addSuppressed(caught)
                    }
                }
            }
            failure?.let { throw it }
        }
    }
}
