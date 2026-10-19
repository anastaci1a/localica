package io.github.anastaci1a.localica.ui

import kotlinx.coroutines.withTimeoutOrNull

/** A missing platform image must not retain pinch ownership indefinitely.
 * Timeout retires this readback; external cancellation still propagates. */
internal suspend fun <T : Any> awaitPinchSnapshot(timeoutMillis: Long = 3_000,
    capture: suspend () -> T): T? = withTimeoutOrNull(timeoutMillis) { capture() }
