package io.github.anastaci1a.localica.ui

import kotlinx.coroutines.withTimeoutOrNull

/** Coroutine timeout/cancellation fixture only; does not capture or retire a native platform image. */
internal suspend fun <T : Any> awaitPinchSnapshot(timeoutMillis: Long = 3_000,
    capture: suspend () -> T): T? = withTimeoutOrNull(timeoutMillis) { capture() }
