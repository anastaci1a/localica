package io.github.anastaci1a.localica.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider

/**
 * Marks a composable that opens a glass popup through [GlassDialog]. Calls to it are
 * held to the same rule as the host itself: open it inside [GlassPopupFrom] with the
 * button that was pressed, or inside [GlassUnanchoredPopup] when no button owns it (#748).
 * Its own body is exempt, since it only forwards the ambient origin to the host.
 */
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.FUNCTION)
internal annotation class GlassPopupOpener

/**
 * Open every popup in [content] from [anchor], so it expands from that button's bounds
 * and retracts back into it on dismissal. This is the one path for settings links, bar
 * and header buttons, and player actions; the shared host reads it as [LocalGlassOrigin].
 */
@Composable
internal fun GlassPopupFrom(anchor: GlassAnchor, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalGlassOrigin provides anchor, content = content)
}

/**
 * Popups with no pressed button of their own: failures, progress, prompts raised by the
 * system, and dialogs raised from a menu item whose menu already owned the morph. They
 * keep the plain centred presentation; this wrapper adds nothing to the composition, so an
 * origin already supplied by an enclosing scope still applies. Naming the case keeps the choice reviewable.
 */
@Composable
internal fun GlassUnanchoredPopup(reason: String, content: @Composable () -> Unit) {
    check(reason.isNotBlank())
    content()
}

/**
 * For a popup declared in a helper whose every caller already opens it inside
 * [GlassPopupFrom]. The reason names where the origin is set.
 */
@Composable
internal fun GlassPopupInherited(reason: String, content: @Composable () -> Unit) {
    check(reason.isNotBlank())
    content()
}
