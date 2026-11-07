package io.github.anastaci1a.localica

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import io.github.anastaci1a.localica.navigation.InterfaceSettings
import io.github.anastaci1a.localica.ui.engine.*
import io.github.anastaci1a.localica.ui.spec.*

/** The U11.00 root record (EngineContext + measured geometry) for fixtures that compose Engine owners outside LocalicaScreen. */
@Composable fun EngineRecord(content: @Composable () -> Unit) {
    val density = LocalDensity.current
    val spec = remember(density) { uiSpec(InterfaceSettings(), ExpansionGeometryDomain(1000f, 3000f, 1f / 60), density) }
    CompositionLocalProvider(LocalEngineContext provides EngineContext(1f / 60, 1000f, spec.spec.access), LocalUiSpec provides spec, content = content)
}

/** U03.01c: a theme with the root record, for fixtures composing Engine-owned shapes (the lyrics split pill) outside LocalicaScreen. */
@Composable internal fun EngineTheme(clear: Boolean = false, content: @Composable () -> Unit) =
    if (clear) ClearGlassTheme { EngineRecord(content) } else io.github.anastaci1a.localica.ui.LocalicaTheme { EngineRecord(content) }
