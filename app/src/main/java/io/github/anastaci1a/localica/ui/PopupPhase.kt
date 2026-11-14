package io.github.anastaci1a.localica.ui

internal data class PopupFlags(val present: Boolean, val hidden: Boolean, val modalPresent: Boolean,
    val contactModal: Boolean, val preparedOrigin: Boolean, val originAdmitted: Boolean,
    val windowAdmitted: Boolean, val shown: Boolean, val homeDeparting: Boolean, val homeGone: Boolean,
    val offered: Boolean, val taken: Boolean, val projected: Boolean, val expansion: Float)

/** Debug builds only: a test samples each live popup's flags (keyed by its flight); a null reader means it left composition. */
internal var popupPhaseProbe: ((GlassPopupFlight, GlassAnchor, (() -> PopupFlags)?) -> Unit)? = null
