package io.github.anastaci1a.localica

import android.os.ParcelFileDescriptor
import android.content.ComponentName
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.Condition
import androidx.test.uiautomator.UiDevice
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

/** A changed policy needs observer delivery; an unchanged unknown cache reads at next use.
 * An explicitly unbound service also reads fresh. Missing or malformed dump data proves neither. */
internal fun awaitSystemImeHardKeyboardPolicy(
    shell: (String) -> String, showWithHardKeyboard: Boolean, changed: Boolean,
): String {
    val selected = ComponentName.unflattenFromString(shell("settings get secure default_input_method"))
        ?: throw AssertionError("No selected system keyboard")
    var observed = ""
    val settled = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).wait(Condition<UiDevice, Boolean> {
        val dump = shell("dumpsys -t 2 input_method")
        fun values(pattern: String) = Regex(pattern).findAll(dump).map { it.groupValues[1] }.toList()
        val bound = values("(?m)^\\s*mCurId=(\\S+)")
        val services = values("(?m)^Input method service state for (.*)$").map { it.substringBefore('@') }
        val states = values("(?m)^\\s*mSettingsObserver=(.*)$")
        observed = "selected=$selected bound=$bound services=$services observer=$states changed=$changed"
        val manager = dump.substringBefore("\nInput method client state").substringBefore("\nInput method service state")
        val unbound = Regex("(?m)^\\s*mCurMethod=(\\S+)").findAll(manager).map { it.groupValues[1] }.toList() == listOf("null")
        dump.startsWith("Input Method Manager Service state:") &&
            !Regex("(?i)DUMP TIMEOUT|Error dumping|Exception occurred while dumping").containsMatchIn(dump) &&
            if (unbound) services.isEmpty() && !dump.contains("SettingsObserver") &&
                bound.singleOrNull() in listOf("null", selected.flattenToShortString(), selected.flattenToString())
            else bound.singleOrNull()?.let(ComponentName::unflattenFromString) == selected &&
                services == listOf(selected.className) &&
                (states == listOf("SettingsObserver{mShowImeWithHardKeyboard=${if (showWithHardKeyboard) 2 else 1}}") ||
                    !changed && states == listOf("SettingsObserver{mShowImeWithHardKeyboard=0}"))
    }, 20_000)
    if (settled != true) throw AssertionError("The system keyboard never observed show_ime_with_hard_keyboard=" +
        "$showWithHardKeyboard ($observed)")
    return observed
}

/**
 * Own the docked system input view before fixture Activities exist. API 36 LatinIME can report
 * onShown and complete an inset animation while hardware-keyboard policy suppresses its input view;
 * every inset is then zero. A preceding keyboard fixture can mask that missing precondition.
 * Restore the exact setting after Activities retire; Android owns the IME process and binding.
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
            val events = mutableListOf<String>()
            var failure: Throwable? = null
            try {
                shell("settings put secure $key 1")
                check(shell("settings get secure $key") == "1") { "Native keyboard policy must precede Activities" }
                events += "before-activities $key=1 original=$original " +
                    awaitSystemImeHardKeyboardPolicy(::shell, true, original != "1")
                base.evaluate()
            } catch (caught: Throwable) {
                failure = caught
            } finally {
                try {
                    if (original == "null") shell("settings delete secure $key")
                    else shell("settings put secure $key $original")
                    check(shell("settings get secure $key") == original) { "Restore native keyboard policy" }
                    events += "after-activities $key=$original " +
                        awaitSystemImeHardKeyboardPolicy(::shell, (original.toIntOrNull() ?: 0) != 0, original != "1")
                } catch (caught: Throwable) {
                    events += "restore-failed $caught"
                    val primary = failure
                    if (primary == null) failure = caught else primary.addSuppressed(caught)
                }
                try {
                    val directory = nativeEvidenceDirectory("popup-ime-policy",
                        instrumentation.targetContext.getExternalFilesDir(null))
                    val ownerPrefix = description.testClass?.simpleName
                        ?.takeUnless { it == "PopupImeGeometryTest" }?.let { "$it-" }.orEmpty()
                    java.io.File(directory, "${ownerPrefix}class-policy-api${android.os.Build.VERSION.SDK_INT}.txt")
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
