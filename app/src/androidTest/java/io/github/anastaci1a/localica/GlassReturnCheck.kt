package io.github.anastaci1a.localica

import android.util.Log
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import io.github.anastaci1a.localica.ui.GlassAnchor

/** Keep the return's animation bound while allowing queued Android dismissal work between frames. */
internal fun awaitGlassReturn(rule: ComposeContentTestRule, anchor: GlassAnchor, context: () -> String) {
    val virtualStart = rule.mainClock.currentTime
    val wallStart = android.os.SystemClock.uptimeMillis()
    val autoAdvance = rule.mainClock.autoAdvance
    rule.mainClock.autoAdvance = false
    try {
        // advanceTimeUntil runs its entire loop in one UI task. That can starve
        // the native snapshot notification that disposes a conditional dialog.
        rule.waitUntil(5000) {
            if (rule.runOnUiThread { !anchor.hidden && !anchor.modalPresent }) true
            else {
                rule.mainClock.advanceTimeByFrame()
                if (rule.mainClock.currentTime - virtualStart > 5000) {
                    throw ComposeTimeoutException("Glass return did not finish within 5000 ms of animation time")
                }
                false
            }
        }
    } catch (failure: ComposeTimeoutException) {
        // ComposeTimeoutException derives directly from Throwable, not Exception.
        val detail = runCatching(context).getOrElse { "diagnostic-error=$it" }
        val state = "$detail virtualElapsed=${rule.mainClock.currentTime - virtualStart} " +
            "wallElapsed=${android.os.SystemClock.uptimeMillis() - wallStart} " +
            "callerAutoAdvance=$autoAdvance hidden=${anchor.hidden} present=${anchor.modalPresent} " +
            "mounted=${anchor.triggerMounted} drawn=${anchor.restoredDrawn} shadow=${anchor.shadowInModal} " +
            "progress=${anchor.contact.expansion.value} target=${anchor.contact.expansion.targetValue} " +
            "running=${anchor.contact.expansion.isRunning} velocity=${anchor.contact.expansion.velocity}"
        Log.e("GlassReturn", state, failure)
        failure.addSuppressed(IllegalStateException(state))
        throw failure
    } finally {
        rule.mainClock.autoAdvance = autoAdvance
    }
}
