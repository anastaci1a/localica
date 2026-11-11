package io.github.anastaci1a.localica.ui.layout

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.anastaci1a.localica.ui.spec.PlayerHandleSpec

/** The expanded player's full-width handle band. The caller supplies its placement before the band and its
 *  presentation after it, in the original modifier order. */
@Composable
internal fun PlayerHandleBand(placement: Modifier, presentation: Modifier, content: @Composable BoxScope.() -> Unit) {
    Box(placement.fillMaxWidth().height(PlayerHandleSpec.bandHeight).then(presentation), content = content)
}
