package io.github.anastaci1a.localica.ui.engine

import androidx.compose.runtime.*
import kotlin.coroutines.coroutineContext

/** One root per coroutine frame clock (the accepted ListMotion lease, shared here so pinch owners created outside
 * composition resolve the existing root on their first suspend mutation). Foundation keeps its own mutation mutex;
 * this lease only supplies the existing root's time and never creates a second frame runner. */
private class ScrollRoot(val clock: UiClock, var users: Int = 1)
private val scrollClocks = java.util.IdentityHashMap<MonotonicFrameClock, ScrollRoot>()
internal fun registerScrollClock(key: MonotonicFrameClock, clock: UiClock): () -> Unit {
    clock.checkMutation()
    val entry = scrollClocks[key]
    check(entry == null || entry.clock === clock) { "A frame clock already has a different scroll root" }
    if (entry == null) scrollClocks[key] = ScrollRoot(clock) else entry.users++
    return { clock.checkMutation(); if (--scrollClocks.getValue(key).users == 0) scrollClocks.remove(key); Unit }
}
@Composable internal fun bindScrollClock(clock: UiClock) {
    val key = requireNotNull(rememberCoroutineScope().coroutineContext[MonotonicFrameClock])
    DisposableEffect(key, clock) { val release = registerScrollClock(key, clock); onDispose(release) }
}
/** The registered root for the calling coroutine's frame clock. Absent binding fails; no fallback clock is created. */
internal suspend fun registeredRootClock(): UiClock =
    requireNotNull(scrollClocks[coroutineContext[MonotonicFrameClock]]?.clock) { "Motion requires its registered root UiClock" }
        .also { it.checkMutation() }
