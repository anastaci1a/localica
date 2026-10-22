package io.github.anastaci1a.localica

import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals

private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
    InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
).bufferedReader().use { it.readText().trim() }

/** Apply keyboard policy before Compose content exists, and prove a real docked
 * input view can show and finish hiding before measuring the application's editor. */
internal fun ComposeContentTestRule.withDockedKeyboard(
    activity: ComponentActivity,
    recordSetup: (String) -> Unit = { android.util.Log.i("NativeKeyboardSetup", it) },
    block: () -> Unit,
) {
    val key = "show_ime_with_hard_keyboard"
    val original = shell("settings get secure $key")
    check(original == "null" || original.matches(Regex("-?[0-9]+")))
    try {
        if (original != "1") shell("settings put secure $key 1")
        assertEquals("1", shell("settings get secure $key"))
        recordSetup("${SystemClock.uptimeMillis()} configured $key=1 (original=$original)")
        prepareDockedKeyboard(activity, recordSetup)
        block()
    } finally {
        if (original == "null") shell("settings delete secure $key")
        else if (original != "1") shell("settings put secure $key $original")
        assertEquals("Restore native keyboard policy", original, shell("settings get secure $key"))
    }
}

private fun ComposeContentTestRule.prepareDockedKeyboard(activity: ComponentActivity, recordSetup: (String) -> Unit) {
    lateinit var editor: EditText
    lateinit var root: FrameLayout
    lateinit var decor: ViewGroup
    lateinit var insetObserver: android.view.View
    val observation = SearchImeHandoffObservation()
    val input = requireNotNull(activity.getSystemService(InputMethodManager::class.java))
    runOnUiThread {
        root = FrameLayout(activity).apply { isFocusableInTouchMode = true }
        insetObserver = createSearchImeObserver(activity, observation)
        editor = EditText(activity).apply { setSingleLine() }
        root.addView(editor, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        activity.setContentView(root)
        // A decor sibling observes before legacy fitting content consumes insets.
        // The editor remains the real activity content, as in the measured flow.
        decor = activity.window.decorView as ViewGroup
        decor.addView(insetObserver, 0, ViewGroup.LayoutParams(0, 0))
        ViewCompat.requestApplyInsets(insetObserver)
        editor.requestFocus()
    }
    fun record(label: String) {
        recordSetup("${SystemClock.uptimeMillis()} $label " + runOnUiThread {
            val insets = ViewCompat.getRootWindowInsets(editor)
            "focused=${editor.isFocused} windowFocused=${editor.hasWindowFocus()} " +
                "visible=${insets?.isVisible(WindowInsetsCompat.Type.ime())} " +
                "ime=${insets?.getInsets(WindowInsetsCompat.Type.ime())?.bottom} " +
                "navigation=${insets?.getInsets(WindowInsetsCompat.Type.navigationBars())?.bottom} " +
                "appliedIme=${observation.applied?.getInsets(WindowInsetsCompat.Type.ime())?.bottom} " +
                "animatedIme=${observation.animated?.getInsets(WindowInsetsCompat.Type.ime())?.bottom} " +
                "animations=${observation.animationCount}"
        })
    }
    fun awaitNativeInputView(shown: Boolean, timeoutMillis: Long) {
        var nextShowRequest = 0L
        try {
            waitUntil(timeoutMillis) {
                val placed = runOnUiThread {
                    val insets = ViewCompat.getRootWindowInsets(editor) ?: return@runOnUiThread false
                    val ime = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
                    val navigation = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
                    if (shown) insets.isVisible(WindowInsetsCompat.Type.ime()) && ime > navigation &&
                        observation.applied?.isVisible(WindowInsetsCompat.Type.ime()) == true &&
                        observation.animationCount == 0
                    else !editor.isFocused && observation.complete(editor.hasWindowFocus(), insets)
                }
                if (shown && !placed && SystemClock.uptimeMillis() >= nextShowRequest) {
                    // A request rejected under the old policy need not replay
                    // when its observer arrives. Retry only this setup editor.
                    val requested = runOnUiThread { input.showSoftInput(editor, 0) }
                    record("native-show-request=$requested")
                    nextShowRequest = SystemClock.uptimeMillis() + 1000L
                }
                // InputMethodService retains mIsInputViewShown after hiding
                // its native window, so check it only while shown. Wait for the
                // native hide's end too: removing the warmup window
                // at its first zero inset can lend the still-hiding IME to the popup.
                placed && (!shown || Regex("\\bmIsInputViewShown=(true|false)\\b")
                    .find(shell("dumpsys -t 2 input_method"))?.groupValues?.get(1) == "true")
            }
        } catch (timeout: ComposeTimeoutException) {
            record("native-input-view-$shown-timeout")
            recordSetup(shell("dumpsys -t 2 input_method"))
            throw AssertionError("Native keyboard setup did not reach shown=$shown; see native setup diagnostics", timeout)
        }
        record("native-input-view-$shown-settled")
    }
    var failure: Throwable? = null
    try {
        record("installed-native-editor")
        waitUntil(5000) {
            runOnUiThread {
                editor.isAttachedToWindow && editor.hasWindowFocus() && editor.isFocused && input.isActive(editor)
            }
        }
        // A shell setting write can notify a background IME after Android's
        // 10-second observer delay (AOSP Settings fix 632fd439248). Prove the
        // real input view and occlusion here, before measuring search motion.
        awaitNativeInputView(shown = true, timeoutMillis = 15000)
    } catch (error: Throwable) {
        failure = error
        throw error
    } finally {
        try {
            runOnUiThread {
                root.requestFocus()
                input.hideSoftInputFromWindow(editor.windowToken, 0)
            }
            awaitNativeInputView(shown = false, timeoutMillis = 5000)
        } catch (cleanup: Throwable) {
            if (failure != null) failure.addSuppressed(cleanup) else {
                failure = cleanup
                throw cleanup
            }
        } finally {
            try {
                runOnUiThread {
                    disposeSearchImeObserver(insetObserver)
                    decor.removeView(insetObserver)
                    (root.parent as? ViewGroup)?.removeView(root)
                    assertEquals("The Compose test rule requires an empty activity content host", 0,
                        activity.findViewById<ViewGroup>(android.R.id.content).childCount)
                }
            } catch (removal: Throwable) {
                if (failure != null) failure.addSuppressed(removal) else throw removal
            }
        }
    }
}
