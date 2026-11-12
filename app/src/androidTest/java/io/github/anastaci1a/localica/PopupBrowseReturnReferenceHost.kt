package io.github.anastaci1a.localica

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import io.github.anastaci1a.localica.ui.LocalGlassPopupHome
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import io.github.anastaci1a.localica.ui.GlassPopupHome
import io.github.anastaci1a.localica.ui.glassPopupHome

/** Published base's original page-plus-chrome recording topology. */
@Composable
internal fun R1ReferenceHome(home: GlassPopupHome, chrome: @Composable () -> Unit, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalGlassPopupHome provides home) {
        Box(Modifier.fillMaxSize().glassPopupHome(home)) { content(); chrome() }
    }
}
