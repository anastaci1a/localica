package io.github.anastaci1a.localica.ui

import io.github.anastaci1a.localica.ui.PopupPresenter.*
import io.github.anastaci1a.localica.ui.PopupTakeover.*

/** #920 step 1: the transition table (DESIGN.md 3.3 as amended by ADDENDUM-TAIL.md 1.2), takeover and presenter together. */
internal fun legalPopupEdge(from: PopupPhase, to: PopupPhase): Boolean {
    val (a, p) = from
    val (b, q) = to
    return when {
        b == Closed -> true // Hide or Deadline from Admitting, the receipt from Restoring, HomeGone or leaving from anywhere
        b == Admitting -> a == Closed || a == InWindow // Show, or WindowReplaced
        a == Admitting -> b == InWindow && q == Window
        a == InWindow && b == Restoring -> p == q // AtHome keeps the presenter
        a == Restoring && b == InWindow -> q == if (p == Taken || p == Releasing) Releasing else Window // Show
        a == InWindow -> when (p) { // presenter steps inside In window
            Window -> q == Offered
            Offered -> q == Taken || q == Window
            Taken -> q == Releasing
            Releasing -> q == Window
            null -> false
        }
        else -> p == Offered && q == Taken // inside Restoring the home may still take an offer
    }
}
