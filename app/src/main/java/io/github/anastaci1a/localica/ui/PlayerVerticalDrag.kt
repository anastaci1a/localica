package io.github.anastaci1a.localica.ui

/** Finger directions the player surface moves: up opens Queue or expands, down collapses.
 * An owned scrub keeps every direction for the seek bar. */
internal fun playerDragDirections(expanded: Boolean, scrubbing: Boolean): Int = when {
    scrubbing -> ControlScrollDirection.None
    expanded -> ControlScrollDirection.Vertical
    else -> ControlScrollDirection.Up
}
