package io.github.anastaci1a.localica.ui

import io.github.anastaci1a.localica.ui.engine.PopupTakeover

/** #920 step 1: the lifecycle model (DESIGN.md, ADDENDUM-TAIL.md), derived from today's flags. Only tests read it. */
internal enum class PopupPresenter { Window, Offered, Taken, Releasing }

internal sealed interface DerivedPopupPhase
internal data class PopupPhase(val takeover: PopupTakeover, val presenter: PopupPresenter? = null) : DerivedPopupPhase
/** Flags that name no model state: a candidate defect in today's code, never a new case. */
internal data class IllegalPopupState(val rule: String) : DerivedPopupPhase

internal fun popupPhaseOf(f: PopupFlags): DerivedPopupPhase = with(f) {
    fun illegal(rule: String) = IllegalPopupState(rule)
    val owned = modalPresent
    if (contactModal != owned || preparedOrigin && originAdmitted != owned) return illegal("split ownership")
    if (taken && !offered) return illegal("taken without offer")
    val presenter = if (taken) PopupPresenter.Taken else if (offered) PopupPresenter.Offered
        else if (projected) PopupPresenter.Releasing else PopupPresenter.Window
    val takeover = when {
        !present || homeGone -> if (hidden || owned) return illegal("takeover without a window") else PopupTakeover.Closed
        owned && !windowAdmitted -> return illegal("takeover without its admitted window")
        owned -> if (hidden) PopupTakeover.InWindow else PopupTakeover.Restoring
        hidden -> return illegal("hidden trigger without takeover")
        shown -> PopupTakeover.Admitting
        else -> return illegal("window outlives its takeover")
    }
    return when {
        takeover <= PopupTakeover.Admitting && presenter != PopupPresenter.Window -> illegal("presenter without takeover")
        takeover == PopupTakeover.Admitting && expansion != 0f -> illegal("admitting window shows the popup")
        takeover <= PopupTakeover.Admitting -> PopupPhase(takeover)
        takeover == PopupTakeover.Restoring && shown -> illegal("restoring with intent shown")
        takeover == PopupTakeover.Restoring && expansion != 0f -> illegal("restoring away from the trigger")
        presenter == PopupPresenter.Offered && !homeDeparting -> illegal("offer without a departing home")
        presenter == PopupPresenter.Taken && shown && !homeDeparting -> illegal("taken handoff not released on reopen")
        else -> PopupPhase(takeover, presenter)
    }
}

