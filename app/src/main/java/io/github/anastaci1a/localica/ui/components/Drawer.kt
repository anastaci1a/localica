package io.github.anastaci1a.localica.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import io.github.anastaci1a.localica.ui.engine.*

/** Where an expansion rests (U10.03). Retained hosts gate lifetime, input and semantics on it, never on a raw phase threshold. */
enum class ExpansionRest { Closed, Moving, Open }
/** Only an endpoint rests; a phase held between endpoints (a test pose) is Moving. */
private fun ExpansionView.rest() = if (inContact || !phase.settled) ExpansionRest.Moving
    else if (phase.target == 1f && expanded) ExpansionRest.Open else if (phase.target == 0f && !expanded) ExpansionRest.Closed else ExpansionRest.Moving
/** Follows each applied phase epoch (an Engine settle publishes one), so no composition reads the phase. */
@Composable fun rememberExpansionRest(view: ExpansionView?): State<ExpansionRest> = produceState(view?.rest() ?: ExpansionRest.Closed, view) {
    if (view != null) snapshotFlow { view.phase.value(); view.rest() }.collect { value = it }
}
/** Projects the endpoint predicate before publication; Open → Moving does not invalidate a retained host. */
@Composable fun rememberExpansionClosed(view: ExpansionView?): State<Boolean> = remember(view) {
    derivedStateOf(structuralEqualityPolicy()) { if (view == null) true else { view.phase.value(); view.rest() == ExpansionRest.Closed } }
}
private val ScrimAlpha = Curve.smooth(0f, .2f)
/** The drawer's retained scrim: always composed, drawn from the shared phase through one C1 map, dismissing while shown. */
@Composable fun DrawerScrim(view: ExpansionView, shown: Boolean, color: Color, onDismiss: () -> Unit) {
    Box(Modifier.fillMaxSize().drawBehind { drawRect(color, alpha = ScrimAlpha.at(view.phase.value())) }
        .then(if (shown) Modifier.clickable(onClick = onDismiss) else Modifier))
}
