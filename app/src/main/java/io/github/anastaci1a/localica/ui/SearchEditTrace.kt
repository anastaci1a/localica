package io.github.anastaci1a.localica.ui

import androidx.compose.runtime.staticCompositionLocalOf

/** Opt-in test observer; events contain only ephemeral identity hashes, lengths and flags. */
internal val LocalSearchEditTrace = staticCompositionLocalOf<((String) -> Unit)?> { null }
