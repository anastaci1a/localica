package io.github.anastaci1a.localica.ui.layout

import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import io.github.anastaci1a.localica.ui.engine.LocalUiClock
import io.github.anastaci1a.localica.ui.engine.SettingsSearchScroll

/** Screens keep a suspended scroll action; only the shared layout seam binds the Engine owner. */
@Composable
internal fun rememberSettingsSearchScroll(scroll: ScrollState): suspend (Int, Boolean, Float) -> Unit {
    val clock = LocalUiClock.current
    return remember(clock, scroll) { SettingsSearchScroll(clock, scroll)::scrollTo }
}
