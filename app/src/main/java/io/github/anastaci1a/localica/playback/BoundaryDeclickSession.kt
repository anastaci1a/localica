package io.github.anastaci1a.localica.playback

/**
 * Single-owner, post-trim PCM16 stream lifecycle. This does not infer completion from audio or PTS.
 * The adapter must end only drained streams, use a new identity for each stream incarnation, and reset on
 * seek/discontinuity. Repeating begin for the active identity/format is an idempotent notification,
 * not a seek. No input is withheld, mutated or retained; every result is an independently owned copy.
 */
internal class BoundaryDeclickSession {
    private data class Stream(
        val identity: String,
        val format: BoundaryDeclick.Pcm16Format,
        var valid: Boolean,
        var tail: ShortArray = ShortArray(0),
        var pendingTail: ShortArray? = null,
    )

    private var active: Stream? = null
    private var completed: Stream? = null

    fun beginStream(
        identity: String,
        format: BoundaryDeclick.Pcm16Format,
        naturalContinuation: Boolean,
    ) {
        val current = active
        if (current != null && current.identity == identity && current.format == format) {
            // Withdrawing continuation proof may disarm, but a repeat can never re-arm.
            if (!naturalContinuation) current.pendingTail = null
            return
        }
        val previous = completed
        val supported = format.sampleRate in 8_000..192_000 && format.channels in 1..8
        val pending = if (current == null && supported && naturalContinuation &&
            previous != null && previous.valid && previous.identity != identity &&
            previous.format == format && previous.tail.size == 8 * format.channels
        ) previous.tail else null
        // A begin without end discards the old ownership rather than inventing a transition.
        active = Stream(identity, format, supported, pendingTail = pending)
        completed = null
    }

    fun process(samples: ShortArray, enabled: Boolean = false): ShortArray {
        val stream = active
        if (stream == null) {
            // Even an empty out-of-order callback makes a prior completion unreliable.
            completed = null
            return samples.copyOf()
        }
        if (samples.isEmpty()) return samples.copyOf()
        val previousTail = stream.pendingTail
        stream.pendingTail = null // A short or disabled first head also consumes the opportunity.
        if (!stream.valid || samples.size % stream.format.channels != 0) {
            stream.valid = false
            stream.tail = ShortArray(0)
            return samples.copyOf()
        }
        val output = if (previousTail != null) {
            BoundaryDeclick.plan(previousTail, samples, stream.format, stream.format, enabled).output
        } else samples.copyOf()
        val capacity = 8 * stream.format.channels
        val keep = minOf(capacity, stream.tail.size + minOf(capacity, output.size))
        val fromOutput = minOf(keep, output.size)
        val fromTail = keep - fromOutput
        stream.tail = ShortArray(keep).also { tail ->
            stream.tail.copyInto(tail, 0, stream.tail.size - fromTail)
            output.copyInto(tail, fromTail, output.size - fromOutput)
        }
        return output
    }

    /** Current-owner callback only: call serially after all active post-trim input has drained. */
    fun endStream() {
        val stream = active ?: return
        stream.pendingTail = null
        completed = stream
        active = null
    }

    /**
     * Identity-bound completion for adapters with delayed callbacks. A mismatched owner disarms
     * correction without ending the active stream; its PCM and tail continue normally. Only a later
     * matching completion can establish new continuation proof. Identities must distinguish incarnations;
     * a reused media ID cannot identify a stale callback from an earlier playback of that media.
     */
    fun endStream(identity: String) {
        val owner = active ?: completed
        if (owner == null || owner.identity != identity) {
            active?.pendingTail = null
            completed = null
        } else {
            endStream()
        }
    }

    /** Clears completion, pending repair and tail ownership, including on seek or cancellation. */
    fun reset() {
        active = null
        completed = null
    }
}
