package io.github.anastaci1a.localica

import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
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
import java.io.File

/** Real synthetic IME Window; not Gboard or physical-device certification. */
@OptIn(ExperimentalLayoutApi::class)
class FloatingImeLifecycleTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val records = mutableListOf<String>()
    private val decisions = mutableListOf<String>()
    private var host: View? = null
    private var ownershipWitness: NativeImeOwnershipWitness? = null
    private var composeVisible = false
    private var current = -1
    private var source = -1
    private var target = -1
    private var emptyReturns = 0

    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
    ).bufferedReader().use { it.readText().trim() }
    // UiAutomation uses Runtime.exec tokenization, not shell parsing. Quotes
    // would be literal bytes. These fixture settings/components are single tokens.
    private fun argument(value: String): String = value.also {
        require(it.matches(Regex("[A-Za-z0-9_./:;=+-]+"))) { "Unsupported direct-command argument" }
    }
    private fun snapshot(label: String): Boolean = compose.runOnUiThread {
        val view = host
        val insets = view?.rootWindowInsets
        val nativeVisible = insets?.isVisible(android.view.WindowInsets.Type.ime())
        val bottom = insets?.getInsets(android.view.WindowInsets.Type.ime())?.bottom
        records += "${SystemClock.uptimeMillis()} $label host=${view?.let(System::identityHashCode)} " +
            "attached=${view?.isAttachedToWindow} focused=${view?.hasWindowFocus()} " +
            "nativeVisible=$nativeVisible nativeBottom=$bottom composeVisible=$composeVisible " +
            "current=$current source=$source target=$target"
        view?.isAttachedToWindow == true && view.hasWindowFocus() && nativeVisible == true &&
            bottom == 0 && composeVisible && current == 0 && source == 0 && target == 0
    }

    private fun awaitFloating(label: String, component: String) {
        compose.waitUntil(10000) { snapshot(label) }
        assertEquals(component, shell("settings get secure default_input_method"))
        val testPackage = InstrumentationRegistry.getInstrumentation().context.packageName
        val requested = SystemClock.uptimeMillis()
        var serviceWitness = ""
        compose.waitUntil(5000) {
            records += shell("am broadcast -a " + argument(testPackage + ".floating.SNAPSHOT") + " -p " + argument(testPackage))
            serviceWitness = shell("logcat -d -v threadtime -s LocalicaFloatingIme:I *:S")
                .lineSequence().lastOrNull { "event=snapshot " in it }.orEmpty()
            fun number(key: String) = Regex("\\b" + key + "=(-?\\d+)")
                .find(serviceWitness)?.groupValues?.get(1)?.toLongOrNull()
            val generation = number("showGeneration")
            number("uptime")?.let { it >= requested } == true &&
                listOf("inputActive=true", "inputViewShown=true", "rootShown=true", "dialogShowing=true",
                    "decorAttached=true", "decorShown=true", "cardShown=true", "windowType=2011").all { it in serviceWitness } &&
                (number("cardWidth") ?: 0) > 0 && (number("cardHeight") ?: 0) > 0 &&
                generation != null && generation > 0 && number("drawShowGeneration") == generation &&
                (number("lastDrawUptime") ?: 0) > 0
        }
        records += serviceWitness
        assertTrue("Real host zero-inset visibility still agrees after service witness", snapshot("$label-confirmed"))
        val dump = shell("dumpsys -t 2 input_method")
        records += "${SystemClock.uptimeMillis()} $label dumpsys:\n$dump"
        val bound = Regex("(?m)^\\s*mCurId=(\\S+)").findAll(dump).map { it.groupValues[1] }.toList()
        assertEquals("Synthetic service is currently bound, not merely installed", listOf(component), bound)
        assertEquals("A real native IME window must be visible, not just injected zeroes", true,
            nativeImeWindowVisible(dump))
        val bitmap = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        try { File(output(), "$label.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) } }
        finally { bitmap.recycle() }
    }

    private fun hideFromService(testPackage: String, tag: String) {
        val before = compose.runOnUiThread { decisions.count { "endEditing reason=imeObservation" in it } }
        records += shell("am broadcast -a " + argument(testPackage + ".floating.HIDE") + " -p " + argument(testPackage))
        compose.waitUntil(10000) {
            compose.runOnUiThread {
                host?.rootWindowInsets?.isVisible(android.view.WindowInsets.Type.ime()) == false && !composeVisible
            } && compose.onAllNodes(hasSetTextAction() and isFocused()).fetchSemanticsNodes().isEmpty()
        }
        compose.onNodeWithTag(tag).assertIsNotFocused()
        assertEquals("Native IME disappearance ends editing exactly once", before + 1,
            compose.runOnUiThread { decisions.count { "endEditing reason=imeObservation" in it } })
    }

    private fun output(): File {
        val extra = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.takeIf { it.isNotBlank() }
        val root = extra?.let(::File) ?: requireNotNull(compose.activity.getExternalFilesDir(null))
        return File(root, "floating-ime-lifecycle").apply { mkdirs() }
    }

    @Test fun actualFloatingWindowPreservesEditingAndEndsOnceAcrossFocusAndReopen() {
        check(Build.VERSION.SDK_INT >= 30) { "This isolated real-window contract requires native IME visibility" }
        val testPackage = InstrumentationRegistry.getInstrumentation().context.packageName
        val component = "$testPackage/io.github.anastaci1a.localica.FloatingTestImeService"
        val keys = listOf("default_input_method", "enabled_input_methods", "selected_input_method_subtype", "input_methods_subtype_history", "show_ime_with_hard_keyboard")
        val original = keys.associateWith { shell("settings get secure $it") }
        // Validate every value before the first mutation so restoration never
        // discovers an unrepresentable empty/whitespace argument after changing IMEs.
        original.values.forEach { argument(it) }
        argument(component); argument(testPackage)
        var contentInstalled by mutableStateOf(true)
        var failure: Throwable? = null
        try {
            assertTrue("Test APK declares the real IME", shell("ime list -a -s").lineSequence().any { it.trim() == component })
            records += shell("ime enable " + argument(component))
            records += shell("ime set " + argument(component))
            shell("settings put secure show_ime_with_hard_keyboard 1")
            assertEquals(component, shell("settings get secure default_input_method"))
            compose.setContent {
                val view = LocalView.current
                val density = LocalDensity.current
                val visible = WindowInsets.isImeVisible
                val ime = WindowInsets.ime.getBottom(density)
                val from = WindowInsets.imeAnimationSource.getBottom(density)
                val to = WindowInsets.imeAnimationTarget.getBottom(density)
                SideEffect { host = view; composeVisible = visible; current = ime; source = from; target = to }
                if (contentInstalled) CompositionLocalProvider(LocalTextEditingObservation provides { decisions += "${SystemClock.uptimeMillis()} $it" }) {
                    TextEditingFocusHost {
                        Column(Modifier.fillMaxSize().padding(24.dp)) {
                            BasicTextField("First", {}, Modifier.testTag("first").clearTextFocusOnBack().padding(24.dp))
                            BasicTextField("Second", {}, Modifier.testTag("second").clearTextFocusOnBack().padding(24.dp))
                            CompositionLocalProvider(LocalEmptySearchBack provides { emptyReturns++ }) {
                                BasicTextField("", {}, Modifier.testTag("empty").clearTextFocusOnBack().padding(24.dp))
                            }
                        }
                    }
                }
            }
            compose.runOnUiThread {
                if (ownershipWitness == null) ownershipWitness = NativeImeOwnershipWitness(requireNotNull(host), { records += it })
            }
            compose.onNodeWithTag("first").performClick()
            awaitFloating("first-floating", component)
            compose.onNodeWithTag("first").assertIsFocused()
            val beforeCover = compose.runOnUiThread { decisions.count { "endEditing reason=imeObservation" in it } }
            val cover = compose.runOnUiThread {
                android.app.Dialog(compose.activity).apply {
                    setContentView(android.widget.TextView(context).apply { text = "Native focus cover" })
                    show()
                }
            }
            try {
                compose.waitUntil(5000) { compose.runOnUiThread { host?.hasWindowFocus() == false } }
            } finally { compose.runOnUiThread { cover.dismiss() } }
            compose.waitUntil(5000) { compose.runOnUiThread { host?.hasWindowFocus() == true } }
            assertEquals("Native focus coverage must not end the editing session", beforeCover,
                compose.runOnUiThread { decisions.count { "endEditing reason=imeObservation" in it } })
            compose.onNodeWithTag("first").performClick()
            awaitFloating("host-refocused", component)
            compose.onNodeWithTag("second").performClick()
            awaitFloating("already-visible-second", component)
            compose.onNodeWithTag("second").assertIsFocused()
            hideFromService(testPackage, "second")
            assertEquals(0, compose.runOnUiThread { emptyReturns })
            compose.onNodeWithTag("empty").performClick()
            awaitFloating("empty-reopened", component)
            hideFromService(testPackage, "empty")
            assertEquals("Empty scoped editor returns once", 1, compose.runOnUiThread { emptyReturns })
            compose.onNodeWithTag("first").performClick()
            awaitFloating("nonempty-reopened", component)
            hideFromService(testPackage, "first")
        } catch (error: Throwable) { failure = error; throw error }
        finally {
            var cleanupError: Throwable? = null
            fun cleanup(block: () -> Unit) {
                try { block() } catch (error: Throwable) {
                    if (cleanupError == null) cleanupError = error else cleanupError!!.addSuppressed(error)
                }
            }
            cleanup {
                if (host != null) {
                    val input = requireNotNull(compose.activity.getSystemService(android.view.inputmethod.InputMethodManager::class.java))
                    compose.runOnUiThread {
                        contentInstalled = false
                        host?.windowToken?.let { input.hideSoftInputFromWindow(it, 0) }
                    }
                    compose.waitForIdle()
                    compose.waitUntil(10000) {
                        compose.runOnUiThread {
                            !input.isAcceptingText &&
                                host?.rootWindowInsets?.isVisible(android.view.WindowInsets.Type.ime()) == false
                        }
                    }
                }
            }
            cleanup { compose.runOnUiThread { ownershipWitness?.dispose(); ownershipWitness = null } }
            cleanup {
                original.getValue("default_input_method").takeUnless { it == "null" || it.isBlank() }
                    ?.let { records += shell("ime set " + argument(it)) }
            }
            cleanup {
                if (!original.getValue("enabled_input_methods").split(':').any { it.substringBefore(';') == component })
                    records += shell("ime disable " + argument(component))
            }
            for ((key, value) in original) cleanup {
                shell(if (value == "null") "settings delete secure $key" else "settings put secure $key " + argument(value))
                assertEquals("Restore secure setting $key", value, shell("settings get secure $key"))
            }
            cleanup {
                val restored = keys.associateWith { shell("settings get secure $it") }
                assertEquals("All captured IME settings agree after the complete restoration", original, restored)
            }
            // Secure settings alone do not prove the native window/input runtime
            // recovered. Never dismiss an unknown crash/system window to pass this.
            cleanup {
                if (host != null) {
                    val input = requireNotNull(compose.activity.getSystemService(android.view.inputmethod.InputMethodManager::class.java))
                    compose.waitUntil(5000) {
                        compose.runOnUiThread {
                            host?.isAttachedToWindow == true && host?.hasWindowFocus() == true &&
                                !input.isAcceptingText &&
                                host?.rootWindowInsets?.isVisible(android.view.WindowInsets.Type.ime()) == false
                        }
                    }
                }
            }
            var restoredMethodDump = ""
            cleanup {
                compose.waitUntil(5000) {
                    restoredMethodDump = shell("dumpsys -t 2 input_method")
                    fun field(name: String): String? {
                        val matches = Regex("(?m)^\\s*" + name + "=(\\S+)")
                            .findAll(restoredMethodDump).map { it.groupValues[1] }.toList()
                        return matches.singleOrNull()
                    }
                    val originalId = original.getValue("default_input_method")
                    val bound = field("mCurId")
                    val noBinding = bound == "null" && field("mCurToken") == "null" &&
                        field("mCurMethod") == "null" && field("mHasMainConnection") == "false" &&
                        field("mVisibleBound") == "false"
                    // mCurIntent may retain a historical disconnected service;
                    // only current live binding fields establish contamination.
                    field("mSelectedMethodId") == originalId &&
                        ((originalId != "null" && bound == originalId) || noBinding)
                }
                records += "method-binding-restoration-verified"
            }
            cleanup { File(output(), "restored-input-method.txt").writeText(restoredMethodDump) }
            if (cleanupError != null) {
                cleanup { File(output(), "restoration-windows.txt").writeText(shell("dumpsys -t 2 window")) }
                cleanup {
                    val bitmap = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
                    try {
                        File(output(), "restoration-screen.png").outputStream().use {
                            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                        }
                    } finally { bitmap.recycle() }
                }
            }
            records += "settings-restore-attempted=$original errors=" + (cleanupError != null)
            cleanup { File(output(), "host-observations.txt").writeText(records.joinToString("\n")) }
            cleanup { File(output(), "editing-decisions.txt").writeText(compose.runOnUiThread { decisions.toList() }.joinToString("\n")) }
            cleanup { File(output(), "service-logcat.txt").writeText(shell("logcat -d -v threadtime -s LocalicaFloatingIme:I *:S")) }
            cleanupError?.let { if (failure != null) failure.addSuppressed(it) else throw it }
        }
    }
}
