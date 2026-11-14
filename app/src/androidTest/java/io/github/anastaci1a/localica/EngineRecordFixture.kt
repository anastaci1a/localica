package io.github.anastaci1a.localica

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import io.github.anastaci1a.localica.navigation.InterfaceSettings
import io.github.anastaci1a.localica.ui.engine.*
import io.github.anastaci1a.localica.ui.spec.*

/** U03.01c: a theme with the root record, for fixtures composing Engine-owned shapes (the lyrics split pill) outside LocalicaScreen. */
@Composable internal fun EngineTheme(clear: Boolean = false, reduced: Boolean = false, content: @Composable () -> Unit) =
    if (clear) ClearGlassTheme { EngineRecord(reduced, content) } else io.github.anastaci1a.localica.ui.LocalicaTheme { EngineRecord(reduced, content) }
