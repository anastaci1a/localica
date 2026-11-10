package io.github.anastaci1a.localica.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import io.github.anastaci1a.localica.ui.engine.ExpansionView
import io.github.anastaci1a.localica.ui.engine.PlayerPresentationSampling
import io.github.anastaci1a.localica.ui.engine.PlayerPresentationSample

/** Binds presentation admissions to the same Sheet and lyrics owners as the player, using the existing reveal maps. */
internal class PlayerPresentation internal constructor(internal val sampling: PlayerPresentationSample)

@Composable
internal fun rememberPlayerPresentation(player: ExpansionView, lyrics: ExpansionView,
    expansion: Float, lyricsVisibility: Float): PlayerPresentation {
    val binding = remember(player, lyrics) {
        PlayerPresentationSampling(PlayerReveal.Lyrics::at, PlayerReveal.Handle::at,
            PlayerReveal.Background::at, PlayerReveal.Actions::at)
    }
    return PlayerPresentation(binding.sample(expansion, lyricsVisibility))
}
