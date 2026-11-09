package io.github.anastaci1a.localica.ui.components

import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Rect

/** Root-composition binding only. A stale disappearing entry cannot clear its successor. */
internal class ToastRowPresentation {
    private var owner: Any? = null
    private var extent by mutableStateOf<(() -> Rect?)?>(null)
    fun body(): Rect? = extent?.invoke()
    fun bind(owner: Any, extent: () -> Rect?) { this.owner = owner; this.extent = extent }
    fun unbind(owner: Any) { if (this.owner === owner) { this.owner = null; extent = null } }
}

internal data class ToastRowRoot(val viewport: io.github.anastaci1a.localica.ui.engine.FloatingRowViewport,
    val seat: io.github.anastaci1a.localica.ui.engine.FloatingRowSeatProjection, val presentation: ToastRowPresentation)
internal val LocalToastRowRoot = staticCompositionLocalOf<ToastRowRoot?> { null }
