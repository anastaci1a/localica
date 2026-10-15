package io.github.anastaci1a.localica.queue

/** Only acceptance/intent crosses threads; queue and native-player mutations remain actor-owned. */
internal class PlaybackIntents {
    data class Choice(val play: Boolean, val revision: Long? = null, val source: String? = null)
    private var revision = 0L
    private var latest: Choice? = null
    private var applied = 0L
    private var observed = false
    internal class RecoveryRequest internal constructor(internal val owner: PlaybackIntents,
        internal val revision: Long, internal var source: String?) {
        internal var claimed = false
    }
    private val recoveryRequests = mutableSetOf<RecoveryRequest>()

    @Synchronized fun accept(play: Boolean? = null, source: String? = null): Long {
        val accepted = ++revision
        if (play != null) latest = Choice(play, accepted, source)
        return accepted
    }
    @Synchronized fun toggle(source: String? = null): Long = accept(!(pending()?.play ?: observed), source)

    /** A queued queue/selection decision must not overwrite a newer externally accepted Pause. */
    @Synchronized fun propose(accepted: Long, play: Boolean, source: String? = null) {
        if (accepted >= (latest?.revision ?: 0)) latest = Choice(play, accepted, source)
    }
    @Synchronized fun choose(accepted: Long? = null, fallback: Boolean): Choice =
        latest?.takeIf { it.revision!! > applied || (accepted != null && it.revision >= accepted) }
            ?: Choice(fallback)
    @Synchronized fun pending(): Choice? = latest?.takeIf { it.revision!! > applied }
    @Synchronized fun applied(choice: Choice) {
        choice.revision?.let { applied = maxOf(applied, it) }
        observed = choice.play
    }
    @Synchronized fun observe(play: Boolean) { observed = play }
    @Synchronized fun currentRevision(): Long = revision

    @Synchronized fun bindSource(accepted: Long, source: String?) {
        latest?.takeIf { it.play && it.revision == accepted && it.source == null }
            ?.let { latest = it.copy(source = source) }
    }

    @Synchronized fun recoveryRequested(source: String?): RecoveryRequest =
        RecoveryRequest(this, accept(), source).also { recoveryRequests.add(it) }

    @Synchronized fun claimRecovery(request: RecoveryRequest, source: String): Long {
        check(request.owner === this && request in recoveryRequests && !request.claimed) { "Recovery request is no longer available." }
        check(request.source == null || request.source == source) { "Recovery source changed before acquisition." }
        request.source = source
        request.claimed = true
        return request.revision
    }

    @Synchronized fun finishRecovery(request: RecoveryRequest, blocked: (String) -> Boolean) {
        check(request.owner === this) { "Recovery request belongs to another playback controller." }
        if (!recoveryRequests.remove(request)) return
        // A failed/cancelled session cannot carry its preserved Play into a later recovery.
        request.source?.takeIf(blocked)?.let { discardForRecovery(it, revision, bindUnassigned = false) }
    }

    /** Recovery cannot replay an old blocked-source Play or consume another source's request. */
    @Synchronized fun discardForRecovery(source: String, through: Long, bindUnassigned: Boolean) {
        val cutoff = recoveryRequests.filter { it.source == source }.minOfOrNull { it.revision }
        discardBoundPlay(source, minOf(through, cutoff ?: through), bindUnassigned)
    }
    private fun discardBoundPlay(source: String, through: Long, bindUnassigned: Boolean) {
        val request = latest ?: return
        if (request.play && request.revision!! <= through &&
            (request.source == source || (request.source == null && bindUnassigned))) {
            applied = maxOf(applied, request.revision)
            // Also retire it for choose(accepted), which can normally reuse an already applied
            // request within its original activation. This cancellation is source/session-specific.
            latest = null
        }
    }

    /** Linearize acceptance around the non-suspending Main-thread native setter. */
    @Synchronized fun apply(accepted: Long? = null, fallback: Boolean, setter: (Boolean) -> Unit) {
        val choice = choose(accepted, fallback)
        setter(choice.play)
        applied(choice)
    }
    /** No provider IO here: only synchronous native timeline/prepare calls on Main. */
    @Synchronized fun preparing(prepare: (pauseRequested: Boolean) -> Unit) {
        prepare(pending()?.play == false)
    }
}
