package io.github.anastaci1a.localica.ui.components

import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorProducer
import androidx.compose.ui.graphics.lerp
import io.github.anastaci1a.localica.ui.engine.*
import io.github.anastaci1a.localica.ui.spec.ExpansionSpec
import io.github.anastaci1a.localica.ui.spec.MotionProfile
import io.github.anastaci1a.localica.ui.spec.UiSpec

/** An expand/collapse presence on the #608 law (U10.03 lyrics). The root publishes its measured [basis] after reading the
 * phase; the physical frame then binds and follows it (Rebase-capable, A2.3c). */
@Stable class Presence internal constructor(val view: ExpansionView) { var basis by mutableStateOf<ExpansionBasis?>(null) }
@Composable fun rememberPresence(key: Any, shown: Boolean, law: (UiSpec) -> ExpansionSpec = { it.expansion }): Presence {
    val owner = rememberExpandCollapse(key, shown, law)
    val presence = remember(owner) { Presence(owner) }
    val basis = presence.basis ?: return presence
    val range = remember(key) { maxOf(1f, 4f * basis.edgeTravelPx) }
    val frame = rememberMotionRect(key to "frame", if (shown) basis.endBounds else basis.startBounds, EdgeRanges(range, range, range, range))
    SideEffect { owner.bind(frame); owner.setBasis(basis); if (owner.expanded != shown) owner.target(shown) }
    return presence
}
/** A unit scalar following [target] on a cataloged profile; its rest at the current intent is [MotionValue.settled]. */
@Composable fun rememberProfileValue(key: Any, target: Float, profile: MotionProfile): MotionValue {
    val motion = rememberMotionFloat(key, target, 1f)
    SideEffect { motion.retarget(target, motionProfileId(profile)) }
    return motion
}
/** A colour from [start] to [end] following [target] on a cataloged profile. The value is read only when the producer
 * runs, so a draw-time producer's ease never recomposes its caller. */
@Composable fun rememberProfileColor(key: Any, target: Float, profile: MotionProfile, start: Color, end: Color): ColorProducer {
    val motion = rememberProfileValue(key, target, profile)
    return remember(motion, start, end) { ColorProducer { lerp(start, end, motion.value()) } }
}
