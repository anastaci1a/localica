package io.github.anastaci1a.localica.ui.components

import androidx.compose.runtime.*
import io.github.anastaci1a.localica.ui.engine.MotionValue
import io.github.anastaci1a.localica.ui.engine.motionProfileId
import io.github.anastaci1a.localica.ui.engine.rememberMotionFloat
import io.github.anastaci1a.localica.ui.spec.MotionProfile

/** One artwork's reveal on the Reveal profile (#524). The value is read only while drawing. Composition and input follow
 * only the reveal's intent and rest: [shown] is whether any of the art shows, and [complete] is the reveal at rest on
 * the shown artwork, which releases the item's text and action (#526, #588). */
@Stable sealed interface ArtworkReveal {
    fun alpha(): Float
    fun shown(): Boolean
    fun complete(): Boolean
}

/** No artwork has published a reveal yet: nothing shows. */
val NoArtworkReveal: ArtworkReveal get() = AbsentArtworkReveal

private object AbsentArtworkReveal : ArtworkReveal {
    override fun alpha() = 0f
    override fun shown() = false
    override fun complete() = false
}

private class EngineArtworkReveal(private val motion: MotionValue, private val epoch: IntState) : ArtworkReveal {
    override fun alpha() = motion.value()
    // A reveal that is not at rest on hidden art has moved off zero: toward the art, or still fading out of it.
    override fun shown(): Boolean { epoch.intValue; return motion.target == 1f || !motion.settled }
    override fun complete(): Boolean { epoch.intValue; return motion.target == 1f && motion.settled }
}

/** Under the caller's reduced motion the reveal rests at its target at once, as the earlier zero-length reveal did. */
@Composable fun rememberArtworkReveal(present: Boolean, reducedMotion: Boolean, onRevealed: () -> Unit): ArtworkReveal {
    val target = if (present) 1f else 0f
    val motion = rememberMotionFloat("artwork-reveal", target, 1f)
    SideEffect { if (reducedMotion) motion.snapTo(target) else motion.retarget(target, motionProfileId(MotionProfile.Reveal)) }
    val reveal = remember(motion) { EngineArtworkReveal(motion, motion.epoch) }
    val currentOnRevealed by rememberUpdatedState(onRevealed)
    LaunchedEffect(reveal) { snapshotFlow { reveal.complete() }.collect { if (it) currentOnRevealed() } }
    return reveal
}
