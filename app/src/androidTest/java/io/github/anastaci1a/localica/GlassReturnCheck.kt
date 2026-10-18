package io.github.anastaci1a.localica

import android.util.Log
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import io.github.anastaci1a.localica.ui.GlassAnchor

/** Bound Compose-owned return state on its animation clock, independently of host rendering speed. */
internal fun awaitGlassReturn(rule: ComposeContentTestRule, anchor: GlassAnchor, context: () -> String) {
    val virtualStart = rule.mainClock.currentTime
    val wallStart = android.os.SystemClock.uptimeMillis()
    try {
        rule.mainClock.advanceTimeUntil(5000) { !anchor.hidden && !anchor.modalPresent }
    } catch (failure: ComposeTimeoutException) {
        // ComposeTimeoutException derives directly from Throwable, not Exception.
        val detail = runCatching(context).getOrElse { "diagnostic-error=$it" }
        val state = "$detail virtualElapsed=${rule.mainClock.currentTime - virtualStart} " +
            "wallElapsed=${android.os.SystemClock.uptimeMillis() - wallStart} " +
            "autoAdvance=${rule.mainClock.autoAdvance} hidden=${anchor.hidden} present=${anchor.modalPresent} " +
            "mounted=${anchor.triggerMounted} drawn=${anchor.restoredDrawn} shadow=${anchor.shadowInModal} " +
            "progress=${anchor.contact.expansion.value} target=${anchor.contact.expansion.targetValue} " +
            "running=${anchor.contact.expansion.isRunning} velocity=${anchor.contact.expansion.velocity}"
        Log.e("GlassReturn", state, failure)
        failure.addSuppressed(IllegalStateException(state))
        throw failure
    }
}
