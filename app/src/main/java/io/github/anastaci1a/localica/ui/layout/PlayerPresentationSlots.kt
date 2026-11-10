package io.github.anastaci1a.localica.ui.layout

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import io.github.anastaci1a.localica.ui.components.PlayerPresentation
import io.github.anastaci1a.localica.ui.spec.PlayerPresentationSpec

/** The original handle Box measurement, with one composition sample shared by admission and the supplied optical operation. */
@Composable
internal fun PlayerHandlePresentation(
    presentation: PlayerPresentation, statusInset: Dp,
    shadowOpacity: @Composable (() -> Float) -> Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    presentation.sampling.Handle { alpha ->
        Box(Modifier.offset(y = statusInset).fillMaxWidth().height(PlayerPresentationSpec.Stock.handleRowHeight)
            .then(shadowOpacity { alpha }), content = content)
    }
}

/** The original action-row Box and modifier order; Engine owns its admission and composition-sampled graphics alpha. */
@Composable
internal fun PlayerActionsPresentation(
    presentation: PlayerPresentation, x: Dp, y: Dp, width: Dp, rows: Int,
    modifier: Modifier, semanticsModifier: Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    presentation.sampling.Actions { alpha ->
        Box(Modifier.offset(x = x, y = y).width(width).height(PlayerPresentationSpec.Stock.actionRowHeight * rows)
            .then(modifier).then(alpha).then(semanticsModifier), content = content)
    }
}
