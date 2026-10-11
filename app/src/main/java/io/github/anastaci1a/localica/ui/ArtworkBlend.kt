package io.github.anastaci1a.localica.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** Keep the outgoing surface opaque while its replacement fades over it. Fading both
 * surfaces out/in exposes the dark base halfway through and causes a luminance dip. */
@Composable
internal fun ArtworkBlend(visual: PlayerVisual, modifier: Modifier, duration: Int,
    content: @Composable (PlayerVisual) -> Unit) {
    AnimatedContent(visual.copy(loading = false), modifier, label = "Artwork transition",
        transitionSpec = {
            (fadeIn(tween(if (initialState.artwork == null) minOf(duration, 160) else duration)) togetherWith
                ExitTransition.None).using(SizeTransform(clip = false))
        }, content = { content(it) })
}
