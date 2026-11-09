package io.github.anastaci1a.localica.ui

import androidx.compose.runtime.*
import io.github.anastaci1a.localica.navigation.MediaRef
import io.github.anastaci1a.localica.toast.ToastCommand

/** Root composition only. No callbacks or transient route state enter the app-scoped toast queue. */
internal fun interface ToastNavigation { fun open(command: ToastCommand, presenterCurrent: () -> Boolean) }
internal val LocalToastNavigation = staticCompositionLocalOf<ToastNavigation?> { null }
internal val LocalToastScroll = compositionLocalOf<ToastScrollRequest?> { null }
internal class ToastScrollRequest(val destination: MediaRef, val entryId: String?, val queue: Boolean, val window: Any, val routeKey: String?,
    private val current: () -> Boolean) {
    var ready by mutableStateOf(!queue)
    var initialPositioned by mutableStateOf(false)
    private var consumed by mutableStateOf(false)
    val valid: Boolean get() = !consumed && current()
    fun cancel() { consumed = true }
    fun complete() { consumed = true }
}

/** One readiness contract shared by initial admission and every stock ease retarget.
 * Lambdas retain the immutable publication without copying a potentially large result per frame. */
internal data class ToastOccurrenceFrame(val currentRoute: Any, val publishedRoute: Any?, val pending: Boolean,
    val itemCount: Int, val lazyCount: Int, val measuredKeys: List<Pair<Int, Any>>,
    val itemKey: (Int) -> Any?, val occurrenceIndex: (String) -> Int)

/** null waits before motion; during motion the existing owner is cancelled on this readiness gap.
 * -1 is a settled missing/retired occurrence. Neither case may address a stale lazy index. */
internal fun toastOccurrenceTarget(frame: ToastOccurrenceFrame, occurrence: String?): Int? {
    if (frame.pending || frame.publishedRoute == null || frame.publishedRoute != frame.currentRoute ||
        frame.lazyCount != frame.itemCount + 1 || frame.measuredKeys.any { (index, key) ->
            index > 0 && frame.itemKey(index - 1) != key }) return null
    if (occurrence == null) return -1
    val index = frame.occurrenceIndex(occurrence)
    return if (index in 0 until frame.itemCount) index + 1 else -1
}
