package io.github.anastaci1a.localica

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import io.github.anastaci1a.localica.navigation.InterfaceSettings
import io.github.anastaci1a.localica.ui.LocalicaTheme
import io.github.anastaci1a.localica.ui.LocalicaUiRoot
import io.github.anastaci1a.localica.ui.LocalicaUiRootState

/** Lyrics composed without the app root still need its engine context: lyric emphasis is an engine profile (#600).
 *  [reducedMotion] matches the flag the test passes to the lyrics, so the engine's access agrees with it. */
@Composable
internal fun LyricsEngineTheme(reducedMotion: Boolean, content: @Composable () -> Unit) {
    val root = remember(reducedMotion) { LocalicaUiRootState(InterfaceSettings(reducedMotion = reducedMotion)) }
    LocalicaUiRoot(root) { LocalicaTheme(content = content) }
}
