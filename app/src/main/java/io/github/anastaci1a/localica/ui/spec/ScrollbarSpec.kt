package io.github.anastaci1a.localica.ui.spec

import androidx.compose.ui.unit.dp

/** Fixed scrollbar geometry, independent of settings-row and artwork dimensions.
 * The rail reserves preview room inward of the shared input strip. The thumb grows inward
 * about its outer top edge; the resting drawing remains narrower than the input strip.
 */
internal object ScrollbarSpec {
    val railWidth = 76.dp
    val hitWidth = 48.dp
    val minimumThumbHeight = 32.dp
    val thumbWidth = 4.dp
    val edgeInset = 4.dp
    val topInset = 8.dp
    val bottomInset = 80.dp
    val previewMinimum = 48.dp
    val previewPadding = 8.dp
    val thumbRadius = 8.dp
    val previewRadius = 16.dp
    const val maximumTrackShare = .35f
    const val heldScale = 1.5f
    const val gripMillis = 240f
    const val releaseMillis = 180
    const val fadeMillis = 220
    const val idleMillis = 1200

    init {
        require(listOf(railWidth, hitWidth, minimumThumbHeight, thumbWidth, edgeInset,
            topInset, bottomInset, previewMinimum, previewPadding, thumbRadius, previewRadius)
            .all { it.value.isFinite() && it.value > 0f })
        require(railWidth >= hitWidth && hitWidth >= thumbWidth * heldScale + edgeInset)
        require(previewMinimum >= previewPadding * 2 && maximumTrackShare > 0f && maximumTrackShare <= 1f)
        require(heldScale.isFinite() && heldScale >= 1f && gripMillis > 0f && releaseMillis > 0 && fadeMillis > 0 && idleMillis > 0)
    }
}
