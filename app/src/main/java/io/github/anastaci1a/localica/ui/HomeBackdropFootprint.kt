package io.github.anastaci1a.localica.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified

/**
 * A home surface's local pixel extent and its mapping into the shared screen frame.
 * Retains the original basis instead of replacing transformed corners with screen bounds.
 */
@ConsistentCopyVisibility
internal data class HomeBackdropFootprint private constructor(
    val size: Size,
    val localToScreen: BackdropRegistration
) {
    /** Source recording pixels -> home-local pixels, with both inputs in the same screen frame. */
    fun sourceToLocal(sourceToScreen: BackdropRegistration?): BackdropRegistration? {
        val screenToLocal = localToScreen.inverse() ?: return null
        return sourceToScreen?.followedBy(screenToLocal)
    }

    /**
     * Actual home-local TL, TR, BR, BL corners mapped into source recording pixels.
     * Reflection retains this local ordering. Missing/unrepresentable mappings return null,
     * never an axis-aligned bounding box or a translation-only fallback.
     */
    fun cornersInSource(sourceToScreen: BackdropRegistration?): List<Offset>? {
        val screenToSource = sourceToScreen?.inverse() ?: return null
        val localToSource = localToScreen.followedBy(screenToSource) ?: return null
        return localToSource.corners(Rect(Offset.Zero, size)).takeIf { corners ->
            corners.all { it.x.isFinite() && it.y.isFinite() }
        }
    }

    companion object {
        /** Missing/invalid upstream registration or a non-positive/non-finite extent is unavailable. */
        fun create(size: Size, localToScreen: BackdropRegistration?): HomeBackdropFootprint? {
            if (!size.isSpecified || !size.width.isFinite() || !size.height.isFinite() ||
                size.width <= 0f || size.height <= 0f || localToScreen == null) return null
            return HomeBackdropFootprint(size, localToScreen)
        }
    }
}
