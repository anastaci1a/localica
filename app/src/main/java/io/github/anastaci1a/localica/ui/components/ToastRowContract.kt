package io.github.anastaci1a.localica.ui.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Original, undisplaced Activity-root coordinates; shadow outsets are never collision bounds. */
internal data class ToastRowRectangle(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    init {
        require(listOf(left, top, right, bottom).all { it.isFinite() })
        require(right > left && bottom > top)
    }
}

/** A route occurrence is an identity, not a translated title or registration-order priority. */
internal class ToastRowOwner(val route: Any)
internal data class ToastRowButton(val key: Any, val original: ToastRowRectangle)
internal data class ToastRowBasis(val viewport: ToastRowRectangle, val bottom: Float,
    val buttons: List<ToastRowButton>) {
    init {
        require(bottom.isFinite() && bottom in viewport.top..viewport.bottom)
        require(buttons.map { it.key }.distinct().size == buttons.size)
    }
}

/** A single registration receipt can withdraw only itself, including during route handoff. */
internal class ToastRowRegistration internal constructor(val owner: ToastRowOwner, val basis: ToastRowBasis)

/** Activity-composition lifetime only. No controls, callbacks, LocalView/focus/lifecycle locals,
 * material owners or popup state cross this passive geometry seam. The root selects the actual
 * admitted route/Queue/player owner; cached scenes cannot win by registering later. */
internal class ToastRowRegistry(private val activityWindow: Any) {
    private var admitted by mutableStateOf<ToastRowOwner?>(null)
    private var registrations by mutableStateOf<Map<ToastRowOwner, ToastRowRegistration>>(emptyMap())
    val current: ToastRowRegistration? get() = registrations[admitted]

    fun admit(owner: ToastRowOwner?) { admitted = owner }

    fun register(owner: ToastRowOwner, sourceWindow: Any, basis: ToastRowBasis): ToastRowRegistration? {
        if (sourceWindow !== activityWindow) return null
        val snapshot = basis.copy(buttons = basis.buttons.toList())
        val receipt = ToastRowRegistration(owner, snapshot)
        registrations = registrations + (owner to receipt)
        return receipt
    }

    fun withdraw(receipt: ToastRowRegistration) {
        if (registrations[receipt.owner] === receipt) registrations = registrations - receipt.owner
    }
}
