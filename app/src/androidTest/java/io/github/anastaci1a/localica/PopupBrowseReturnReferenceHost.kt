package io.github.anastaci1a.localica

import androidx.compose.runtime.Composable
import io.github.anastaci1a.localica.ui.GlassPopupHome

/** Candidate uses the production chrome host; the base proof substitutes its original Box host. */
@Composable
internal fun R1ReferenceHome(home: GlassPopupHome, chrome: @Composable () -> Unit, content: @Composable () -> Unit) {
    GlassPopupHome(home, chrome = chrome, content = content)
}
