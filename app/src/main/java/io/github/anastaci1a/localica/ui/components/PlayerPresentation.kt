package io.github.anastaci1a.localica.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import io.github.anastaci1a.localica.ui.engine.ExpansionView
import io.github.anastaci1a.localica.ui.engine.PlayerPresentationSampling

/** Binds presentation admissions to the same Sheet and lyrics owners as the player, using the existing reveal maps. */
internal class PlayerPresentation internal constructor(internal val sampling: PlayerPresentationSampling)

@Composable
internal fun rememberPlayerPresentation(player: ExpansionView, lyrics: ExpansionView): PlayerPresentation =
    remember(player, lyrics) {
        PlayerPresentation(PlayerPresentationSampling(player, lyrics,
            PlayerReveal.Lyrics::at, PlayerReveal.Handle::at, PlayerReveal.Background::at, PlayerReveal.Actions::at))
    }
