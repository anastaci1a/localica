package io.github.anastaci1a.localica

import android.os.SystemClock
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.junit.Assert.assertEquals

/** Prove native docked input and a completed hide before the measured Compose editor.
 * Policy ownership/restoration and output logging remain with each calling fixture. */
internal fun prepareNativeDockedKeyboard(
    compose: AndroidComposeTestRule<*, ComponentActivity>,
    keyboardSetup: MutableList<String>,
    shell: (String) -> String,
) {
    lateinit var editor: EditText
    lateinit var root: FrameLayout
    lateinit var insetObserver: android.view.View
    val observation = SearchImeHandoffObservation()
    val input = requireNotNull(compose.activity.getSystemService(InputMethodManager::class.java))
    var nativeWindowVisible: Boolean? = null
    var nativeWindowObservedAt: Long? = null
    // Called only by the instrumentation thread, never inside runOnUiThread.
    fun nativeInputMethodState(): String = shell("dumpsys -t 2 input_method").also { dump ->
        nativeWindowVisible = nativeImeWindowVisible(dump)
        nativeWindowObservedAt = SystemClock.uptimeMillis()
    }
    compose.runOnUiThread {
        root = FrameLayout(compose.activity).apply { isFocusableInTouchMode = true }
        insetObserver = createSearchImeObserver(compose.activity, observation)
        // Observe the window beyond removal of the synthetic editing content.
        // An observer inside root would detach before that final input-session handoff.
        (compose.activity.window.decorView as ViewGroup).addView(insetObserver, ViewGroup.LayoutParams(0, 0))
        editor = EditText(compose.activity).apply { setSingleLine() }
        root.addView(editor, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        compose.activity.setContentView(root)
        ViewCompat.requestApplyInsets(insetObserver)
        editor.requestFocus()
    }
    fun record(label: String) {
        keyboardSetup += "${SystemClock.uptimeMillis()} $label " +
            "nativeImeWindowVisible=$nativeWindowVisible nativeWindowObservedAt=$nativeWindowObservedAt " + compose.runOnUiThread {
            val insets = ViewCompat.getRootWindowInsets(editor)
            "focused=${editor.isFocused} windowFocused=${editor.hasWindowFocus()} " +
                "editorActive=${input.isActive(editor)} rootActive=${input.isActive(root)} acceptingText=${input.isAcceptingText} " +
                "visible=${insets?.isVisible(WindowInsetsCompat.Type.ime())} " +
                "ime=${insets?.getInsets(WindowInsetsCompat.Type.ime())?.bottom} " +
                "navigation=${insets?.getInsets(WindowInsetsCompat.Type.navigationBars())?.bottom} " +
                "appliedIme=${observation.applied?.getInsets(WindowInsetsCompat.Type.ime())?.bottom} " +
                "animatedIme=${observation.animated?.getInsets(WindowInsetsCompat.Type.ime())?.bottom} " +
                "animations=${observation.animationCount}"
        }
    }
    fun awaitNativeInputView(shown: Boolean, timeoutMillis: Long) {
        var nextShowRequest = 0L
        try {
            compose.waitUntil(timeoutMillis) {
                val placed = compose.runOnUiThread {
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
                    val requested = compose.runOnUiThread { input.showSoftInput(editor, 0) }
                    record("native-show-request=$requested")
                    nextShowRequest = SystemClock.uptimeMillis() + 1000L
                }
                // InputMethodService retains mIsInputViewShown after hiding
                // its native window, so check it only while shown. Wait for the
                // native hide's end too: removing the warmup window
                // at its first zero inset can lend the still-hiding IME to the popup.
                placed && run {
                    val state = nativeInputMethodState()
                    if (shown) Regex("\\bmIsInputViewShown=(true|false)\\b")
                        .find(state)?.groupValues?.get(1) == "true"
                    else nativeWindowVisible == false // Unknown native evidence fails closed.
                }
            }
        } catch (timeout: ComposeTimeoutException) {
            record("native-input-view-$shown-timeout")
            val state = keyboardSetup.last()
            keyboardSetup += shell("dumpsys -t 2 input_method")
            throw AssertionError("Native keyboard setup did not reach shown=$shown: $state", timeout)
        }
        record("native-input-view-$shown-settled")
    }
    var failure: Throwable? = null
    try {
        record("installed-native-editor")
        compose.waitUntil(5000) {
            compose.runOnUiThread {
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
            compose.runOnUiThread {
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
                compose.runOnUiThread {
                    (root.parent as? ViewGroup)?.removeView(root)
                    assertEquals("The Compose test rule requires an empty activity content host", 0,
                        compose.activity.findViewById<ViewGroup>(android.R.id.content).childCount)
                }
                record("native-editor-removed-retirement-start")
                // A hidden IME does not prove the served editor's InputConnection is gone.
                // Public isActive/isAcceptingText checks precede the next measured editor.
                // The still-mounted observer also sees hide/animation callbacks caused by removal.
                compose.waitUntil(5000) {
                    val retired = compose.runOnUiThread {
                        val view = compose.activity.window.decorView
                        val insets = ViewCompat.getRootWindowInsets(view)
                        !input.isActive(editor) && !input.isActive(root) && !input.isAcceptingText &&
                            observation.complete(view.hasWindowFocus(), insets)
                    }
                    retired && run {
                        nativeInputMethodState()
                        nativeWindowVisible == false
                    }
                }
                record("native-editor-retired")
            } catch (removal: Throwable) {
                record("native-editor-retirement-failed")
                if (failure != null) failure.addSuppressed(removal) else {
                    failure = removal
                    throw removal
                }
            } finally {
                try {
                    compose.runOnUiThread {
                        disposeSearchImeObserver(insetObserver)
                        (insetObserver.parent as? ViewGroup)?.removeView(insetObserver)
                    }
                } catch (observerCleanup: Throwable) {
                    if (failure != null) failure.addSuppressed(observerCleanup) else throw observerCleanup
                }
            }
        }
    }
}
