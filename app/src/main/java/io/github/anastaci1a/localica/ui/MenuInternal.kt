package io.github.anastaci1a.localica.ui

/**
 * #757: marks a legacy menu building block as an internal of the menu system. A block gets the marker in the step
 * that migrates its callers; from then on only the files listed in `app/config/menu-host-policy.txt` may opt in.
 * M0 marks nothing, because every block still has unmigrated callers.
 */
@RequiresOptIn(level = RequiresOptIn.Level.ERROR, message = "Menu internals are used only by the menu binder and registered hosted popups (#757).")
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY)
internal annotation class MenuInternal
