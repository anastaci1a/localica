package io.github.anastaci1a.localica.ui

import androidx.compose.ui.unit.dp

/** Padding between the artwork and the top controls above it, and between the artwork and the title below it. */
internal const val PlayerArtworkEdgePadding = 8f

/** The vertical band the expanded artwork lives in: from the padded bottom of the top controls
 * (back button / lyrics pill) to the padded top of the title. The artwork is centered in it, so its
 * top and bottom edges are always the same distance from the band's edges. A taller artwork gives a
 * negative gap on both sides (it overlaps, layered below the controls and title) rather than a lopsided one. */
internal data class PlayerArtworkBand(val top: Float, val height: Float) {
    val bottom: Float get() = top + height
    fun artworkTop(artworkHeight: Float): Float = top + (height - artworkHeight) / 2f
    fun topGap(artworkHeight: Float): Float = artworkTop(artworkHeight) - top
    fun bottomGap(artworkHeight: Float): Float = bottom - (artworkTop(artworkHeight) + artworkHeight)
    /** The largest artwork height that does not overlap either padded edge. */
    fun fittingHeight(): Float = height.coerceAtLeast(0f)
}

internal fun playerArtworkBand(controlsBottom: Float, titleTop: Float,
    padding: Float = PlayerArtworkEdgePadding): PlayerArtworkBand =
    PlayerArtworkBand(controlsBottom + padding, titleTop - padding - (controlsBottom + padding))

/** Height of the row holding the back button and lyrics pill, below the status bar. */
internal val PlayerTopControlsHeight = 48.dp
