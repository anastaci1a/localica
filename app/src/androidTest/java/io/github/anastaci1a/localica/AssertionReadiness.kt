package io.github.anastaci1a.localica

import android.util.Log
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.junit4.ComposeContentTestRule

/** Preserve the reason an assertion-based readiness poll has not succeeded.
 * [state] reads the last observation only on timeout; it must not advance a frame. */
internal fun ComposeContentTestRule.waitForAssertion(timeoutMillis: Long, description: String,
    state: () -> String, assertion: () -> Unit) {
    var lastFailure: Throwable? = null
    try {
        waitUntil(timeoutMillis) {
            runCatching(assertion).onFailure { lastFailure = it }.isSuccess
        }
    } catch (timeout: ComposeTimeoutException) {
        val observed = runCatching(state).getOrElse {
            timeout.addSuppressed(it)
            "State unavailable: ${it.javaClass.name}: ${it.message}"
        }
        val detail = "$description; clock=${mainClock.currentTime}; autoAdvance=${mainClock.autoAdvance}; $observed"
        lastFailure?.let { if (it !== timeout) timeout.addSuppressed(it) }
        timeout.addSuppressed(AssertionError(detail))
        Log.e("AssertionReadiness", detail, lastFailure)
        throw timeout
    }
}
