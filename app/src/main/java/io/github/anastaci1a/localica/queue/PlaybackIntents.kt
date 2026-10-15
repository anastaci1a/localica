package io.github.anastaci1a.localica.queue

/** Only acceptance/intent crosses threads; queue and native-player mutations remain actor-owned. */
internal class PlaybackIntents {
    data class Choice(val play: Boolean, val revision: Long? = null)
    private var revision = 0L
    private var latest: Choice? = null
    private var applied = 0L
    private var observed = false

    @Synchronized fun accept(play: Boolean? = null): Long {
        val accepted = ++revision
        if (play != null) latest = Choice(play, accepted)
        return accepted
    }
    @Synchronized fun toggle(): Long = accept(!(pending()?.play ?: observed))

    /** A queued queue/selection decision must not overwrite a newer externally accepted Pause. */
    @Synchronized fun propose(accepted: Long, play: Boolean) {
        if (accepted >= (latest?.revision ?: 0)) latest = Choice(play, accepted)
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
