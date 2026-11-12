package io.github.anastaci1a.localica.ui.components

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorProducer
import androidx.compose.ui.graphics.lerp
import io.github.anastaci1a.localica.ui.engine.*
import io.github.anastaci1a.localica.ui.spec.MotionProfile

/** An expand/collapse presence on the #608 law (U10.03 lyrics). The root publishes its measured [basis] after reading the
 * phase; the physical frame then binds and follows it (Rebase-capable, A2.3c). */
@Stable class Presence internal constructor(val view: ExpansionView) { var basis by mutableStateOf<ExpansionBasis?>(null) }
@Composable fun rememberPresence(key: Any, shown: Boolean, role: TriggerRole<Nothing, Nothing> = TriggerRole.Surface): Presence {
    val owner = rememberExpandCollapse(key, shown) { if (role == TriggerRole.Lyrics) it.lyricsExpansion else it.expansion }
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

/** Semantic facades select their own catalog entry; callers supply intent and effective accessibility policy. */
@Composable internal fun rememberNavigationChrome(insideCollection: Boolean, reduced: Boolean, faster: Boolean): State<Float> =
    rememberSceneFloat(if (insideCollection) 0f else 1f, MotionProfile.NavigationChrome, reduced, faster)

@Composable internal fun rememberArtworkAspect(target: Float, reduced: Boolean, faster: Boolean): State<Float> =
    rememberSceneFloat(target, MotionProfile.PlayerAspect, reduced, faster)

@Composable internal fun rememberMiniPlayerInk(target: Color, reduced: Boolean, faster: Boolean): ColorProducer {
    val state = rememberSceneColor(target, MotionProfile.MiniPlayerInk, reduced, faster)
    return remember(state) { ColorProducer { state.value } }
}

@Composable internal fun HeadingContent(title: String, reduced: Boolean, faster: Boolean,
    modifier: Modifier = Modifier, content: @Composable (String) -> Unit) =
    RetainedContentSwap(title, MotionProfile.Heading, reduced, faster, modifier, false) { shown, _ -> content(shown) }

@Composable internal fun <T> LyricsContent(target: T, reduced: Boolean,
    modifier: Modifier = Modifier, content: @Composable (T, () -> Float) -> Unit) =
    RetainedContentSwap(target, MotionProfile.LyricsSwap, reduced, false, modifier, true, content)

@Composable internal fun LyricsFollowPresence(visible: Boolean, reduced: Boolean,
    modifier: Modifier = Modifier, content: @Composable () -> Unit) =
    FollowPresence(visible, reduced, modifier, content)

@Composable internal fun OptionsPages(selected: Int, reduced: Boolean,
    modifier: Modifier = Modifier, content: @Composable (Int) -> Unit) =
    OptionsPagePlacement(selected, reduced, modifier, content)

@Composable internal fun rememberPlayerBackground(target: Float): MotionValue =
    rememberProfileValue("player-background", target, MotionProfile.ArtworkLayout)

@Composable internal fun rememberPlayerCropRetention(target: Float): MotionValue =
    rememberProfileValue("player-crop", target, MotionProfile.CropRetention)
