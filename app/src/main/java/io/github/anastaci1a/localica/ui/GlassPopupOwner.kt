package io.github.anastaci1a.localica.ui

import androidx.compose.runtime.*

/**
 * Owner scope of popups opened from one tab, subtab or page (#767). A popup entry
 * retained by [GlassDialogs] after its caller left composition is tied to the owner
 * that was ambient when it opened. When the owner is deselected or leaves composition,
 * the host retires the entry in the same pass instead of finishing its open or
 * minimize animation over the incoming content.
 */
@Stable
internal class GlassPopupOwner(val key: Any, private val parent: GlassPopupOwner?) {
    private var selected by mutableStateOf(true)
    private var disposed by mutableStateOf(false)

    /** False once this scope, or any enclosing scope, is deselected or gone. */
    val live: Boolean get() = selected && !disposed && parent?.live != false

    internal fun select(value: Boolean) { selected = value }
    internal fun dispose() { disposed = true }
}

internal val LocalGlassPopupOwner = compositionLocalOf<GlassPopupOwner?> { null }

/** Creates an owner for [key]; [active] false retires its popups while content stays composed. */
@Composable
internal fun rememberGlassPopupOwner(key: Any, active: Boolean = true): GlassPopupOwner {
    val parent = LocalGlassPopupOwner.current
    val owner = remember(key, parent) { GlassPopupOwner(key, parent) }
    SideEffect { owner.select(active) }
    DisposableEffect(owner) { onDispose { owner.dispose() } }
    return owner
}

/** Tab or subtab content. Popups opened inside pass this scope to the shared host. */
@Composable
internal fun GlassPopupScope(key: Any, active: Boolean = true, content: @Composable () -> Unit) {
    val owner = rememberGlassPopupOwner(key, active)
    CompositionLocalProvider(LocalGlassPopupOwner provides owner, content = content)
}
