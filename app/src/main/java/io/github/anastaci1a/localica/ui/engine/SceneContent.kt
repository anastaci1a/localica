package io.github.anastaci1a.localica.ui.engine

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.updateTransition
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import io.github.anastaci1a.localica.ui.spec.LocalUiSpec
import io.github.anastaci1a.localica.ui.spec.MotionProfile

/** One retained-key swap serves headings and lyric documents, including their sampled source opacity. */
@OptIn(androidx.compose.animation.ExperimentalAnimationApi::class)
@Composable internal fun <T> RetainedContentSwap(target: T, profile: MotionProfile, reduced: Boolean,
    faster: Boolean, modifier: Modifier, shareSourceAlpha: Boolean, content: @Composable (T, () -> Float) -> Unit) {
    val spec = LocalUiSpec.current.spec
    val animation = sceneTween<Float>(spec, profile, reduced, faster)
    val transition = updateTransition(target, label = "Shared retained content")
    transition.Crossfade(modifier, animationSpec = animation) { shown ->
        if (shareSourceAlpha) {
            val sourceAlpha = transition.animateFloat(transitionSpec = { animation }, label = "Shared source opacity") {
                if (it == shown) 1f else 0f
            }
            content(shown) { sourceAlpha.value }
        } else content(shown) { 1f }
    }
}

/** This non-popup presence keeps the original fade and full-height exit slide. */
@Composable internal fun FollowPresence(visible: Boolean, reduced: Boolean, modifier: Modifier,
    content: @Composable () -> Unit) {
    val spec = LocalUiSpec.current.spec
    AnimatedVisibility(visible, modifier, enter = fadeIn(sceneTween(spec, MotionProfile.LyricsFollow, reduced, false)),
        exit = fadeOut(sceneTween(spec, MotionProfile.LyricsFollow, reduced, false)) + slideOutVertically(sceneTween(spec, MotionProfile.LyricsFollow, reduced, false)) { it }) { content() }
}

/** Both pages retain identity and size; only the selected page takes the upper sibling order. */
@Composable internal fun OptionsPagePlacement(selected: Int, reduced: Boolean, modifier: Modifier,
    content: @Composable (Int) -> Unit) {
    val progress = rememberSceneFloat(selected.toFloat(), MotionProfile.OptionsPage, reduced, false)
    Layout(content = { repeat(2) { content(it) } }, modifier = modifier) { children, constraints ->
        val pages = children.map { it.measure(constraints.copy(minHeight = 0)) }
        layout(constraints.maxWidth, pages.maxOf { it.height }) {
            pages.forEachIndexed { index, page ->
                val alpha = if (index == 0) 1f - progress.value else progress.value
                if (alpha > 0f) page.placeWithLayer(0, 0, zIndex = if (index == selected) 1f else 0f) { this.alpha = alpha }
            }
        }
    }
}

/** A discrete scene slot keeps threshold ownership in the shared renderer. */
@Composable internal fun <T> playerSceneValue(facts: androidx.compose.runtime.State<PlayerParticipation>,
    participates: (PlayerParticipation) -> Boolean, content: @Composable () -> T): T? =
    if (participates(facts.value)) content() else null
