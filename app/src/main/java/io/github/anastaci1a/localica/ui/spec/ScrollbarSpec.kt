package io.github.anastaci1a.localica.ui.spec

import androidx.compose.ui.unit.dp

/** Fixed scrollbar geometry, independent of settings-row and artwork dimensions.
 * The rail reserves preview room inward of the input strip. The held thumb grows inward from
 * its outer top edge: a constant extra width, and extra length proportional to its resting
 * length up to a cap. The track keeps standard padding from the controls that bound it.
 */
internal object ScrollbarSpec {
    val railWidth = 76.dp
    /** Width of the input strip, and the minimum touch length around the thumb. */
    val hitWidth = 48.dp
    val minimumThumbHeight = 32.dp
    val thumbWidth = 4.dp
    val heldExtraWidth = 2.dp
    const val heldLengthShare = .25f
    val heldMaxExtraLength = 16.dp
    val edgeInset = 4.dp
    val trackPadding = 8.dp
    val previewMinimum = 48.dp
    val previewPadding = 8.dp
    val thumbRadius = 8.dp
    val previewRadius = 16.dp
    const val maximumTrackShare = .35f
    const val gripMillis = 240f
    const val releaseMillis = 180
    const val fadeMillis = 220
    const val idleMillis = 1200
    /** The thumb takes contact only while at least this visible; a fade-out tail below it passes touches to the list. */
    const val hitOpacityFloor = .5f

    init {
        require(listOf(railWidth, hitWidth, minimumThumbHeight, thumbWidth, heldExtraWidth, heldMaxExtraLength,
            edgeInset, trackPadding, previewMinimum, previewPadding, thumbRadius, previewRadius)
            .all { it.value.isFinite() && it.value > 0f })
        require(railWidth >= hitWidth && hitWidth >= thumbWidth + heldExtraWidth + edgeInset)
        require(previewMinimum >= previewPadding * 2 && maximumTrackShare > 0f && maximumTrackShare <= 1f)
        require(heldLengthShare.isFinite() && heldLengthShare >= 0f)
        require(hitOpacityFloor > 0f && hitOpacityFloor <= 1f)
        require(gripMillis > 0f && releaseMillis > 0 && fadeMillis > 0 && idleMillis > 0)
    }

    /** Extra held length for a thumb of resting length [rest] px, capped at [cap] px. */
    fun heldExtraLength(rest: Float, cap: Float) = minOf(rest * heldLengthShare, cap)
}
