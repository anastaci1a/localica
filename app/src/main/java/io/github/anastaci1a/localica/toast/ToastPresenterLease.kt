package io.github.anastaci1a.localica.toast

/** Activity-composition ownership. Every presenter mutation is fenced under the reducer's own
 * lock; a retired window cannot pause, claim or retire its successor's visible surface. */
class ToastPresenterLease internal constructor(private val queue: ToastQueue, val token: Long) {
    val current: Boolean get() = queue.activePresenter.value == token
    val state get() = queue.state
    fun pause(reason: ToastPause, paused: Boolean) = queue.fromPresenter(token, Unit) { queue.pause(reason, paused) }
    fun presented(id: Long, generation: Long, recommendedDwellMillis: Long) =
        queue.fromPresenter(token, false) { queue.presented(id, generation, recommendedDwellMillis) }
    fun marqueeCompleted(id: Long, generation: Long) =
        queue.fromPresenter(token, Unit) { queue.marqueeCompleted(id, generation) }
    fun marqueePending(id: Long, generation: Long) =
        queue.fromPresenter(token, false) { queue.marqueePending(id, generation) }
    fun retainedAfterContact(id: Long, generation: Long) =
        queue.fromPresenter(token, false) { queue.retainedAfterContact(id, generation) }
    fun delayUntil(ticket: ToastDeadline): Long? = queue.fromPresenter<Long?>(token, null) { queue.delayUntil(ticket) }
    fun timeout(ticket: ToastDeadline) = queue.fromPresenter(token, false) { queue.timeout(ticket) }
    fun dismiss(id: Long, generation: Long) = queue.fromPresenter(token, false) { queue.dismiss(id, generation) }
    fun claimAction(id: Long, generation: Long, body: Boolean = false): ToastCommand? =
        queue.fromPresenter<ToastCommand?>(token, null) { queue.claimAction(id, generation, body) }
    fun exited(id: Long) = queue.fromPresenter(token, false) { queue.exited(id) }
    fun release() = queue.releasePresenter(token)
}
