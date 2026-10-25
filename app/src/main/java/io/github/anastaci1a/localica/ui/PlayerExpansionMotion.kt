package io.github.anastaci1a.localica.ui

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.ScaleFactor

/** Selected mode is independent of the expanded target, which only changes on release. */
internal data class PlayerLyricsMotion(val layout: Float, val contentAlpha: Float, val artworkAlpha: Float)

internal fun playerLyricsMotion(expansion: Float, modeVisibility: Float): PlayerLyricsMotion {
    val p = expansion.coerceIn(0f, 1f)
    val mode = modeVisibility.coerceIn(0f, 1f)
    return PlayerLyricsMotion(mode * p, mode * playerReveal((p - .78f) / .22f), 1f - mode * p)
}

internal data class PlayerRowMotion(val seekOffset: Float, val actionsOffset: Float,
    val seekAlpha: Float, val actionsAlpha: Float)

/** Follow distinct mixtures of the title/transport displacement from their full targets.
 * The common surface translation cancels out. Equal thirds preserve the visual speed ladder.
 * Only a mode change can restore album actions: collapsing selected lyrics must never reveal them. */
internal fun playerRowMotion(expansion: Float, titleOffset: Float, transportOffset: Float,
    lyricsModeVisibility: Float): PlayerRowMotion {
    val p = expansion.coerceIn(0f, 1f)
    return PlayerRowMotion(
        seekOffset = titleOffset + (transportOffset - titleOffset) / 3f,
        actionsOffset = titleOffset + (transportOffset - titleOffset) * (2f / 3f),
        seekAlpha = ((p - .8f) / (1f - .8f)).coerceIn(0f, 1f),
        actionsAlpha = ((p - .83f) / (1f - .83f)).coerceIn(0f, 1f) * (1f - lyricsModeVisibility.coerceIn(0f, 1f)))
}

private fun playerReveal(value: Float): Float = value.coerceIn(0f, 1f).let { it * it * (3f - 2f * it) }

/** Preserve early transport travel and both endpoints, but avoid deceleration as the rows fade.
 * Only vertical placement changes: button sizing and horizontal spread retain their own easing.
 * A short Hermite join keeps position and velocity continuous before the rows become visible. */
internal fun playerTransportOffset(expansion: Float, titleTravel: Float, transportTravel: Float): Float {
    val p = expansion.coerceIn(0f, 1f)
    val tailSlope = titleTravel.coerceAtLeast(0f) * .6f
    if (p >= .8f) return -tailSlope * (1f - p)
    if (p <= .65f) return -transportTravel * (1f - p * p * (3f - 2f * p))
    val start = .65f
    val span = .15f
    val t = (p - start) / span
    val y0 = -transportTravel * (1f - start * start * (3f - 2f * start))
    val v0 = transportTravel * 6f * start * (1f - start)
    val y1 = -tailSlope * .2f
    return (2f * t * t * t - 3f * t * t + 1f) * y0 +
        (t * t * t - 2f * t * t + t) * span * v0 +
        (-2f * t * t * t + 3f * t * t) * y1 +
        (t * t * t - t * t) * span * tailSlope
}

internal data class PlayerLyricsViewport(val top: Float, val height: Float, val contentOffset: Float)

/** Window-anchored upper edge; the lower edge follows the actually presented title, not its
 * expanded target. Content drifts more slowly inside that viewport instead of carrying its clip. */
internal fun playerLyricsViewport(surfaceTop: Float, titleTop: Float, metadataTarget: Float,
    topInset: Float, titleGap: Float = 4f): PlayerLyricsViewport {
    val windowTop = maxOf(topInset, surfaceTop)
    val windowTitle = surfaceTop + titleTop
    val drift = (windowTitle - metadataTarget) * .35f
    return PlayerLyricsViewport(windowTop - surfaceTop,
        (windowTitle - titleGap - windowTop).coerceAtLeast(0f), topInset + drift - windowTop)
}

/** Keep the mini cover unchanged until its bottom clears the title, then resume its usual size
 * ease on the remaining interval. One progress map works in both directions, including holds.
 * Some full-background covers overlap metadata even when settled; those keep their old sizing. */
internal fun playerArtworkClearance(fullArtworkTop: Float, fullArtworkHeight: Float,
    fullTitleTop: Float, miniArtworkTop: Float, miniArtworkSize: Float): Float {
    if (fullArtworkTop + fullArtworkHeight > fullTitleTop) return 0f
    val initialOverlap = miniArtworkTop + miniArtworkSize - 7f
    val linear = fullTitleTop - fullArtworkTop + miniArtworkTop + 41f
    // Title minus the retained mini cover bottom is -overlap + linear*p - 48*p*p.
    val discriminant = linear * linear - 192f * initialOverlap
    if (discriminant <= 0f) return 0f
    val gate = 2f * initialOverlap / (linear + kotlin.math.sqrt(discriminant))
    return gate.takeIf { it in 0f..<1f } ?: 0f
}

internal fun playerArtworkSizeProgress(expansion: Float, clearance: Float): Float {
    val p = ((expansion.coerceIn(0f, 1f) - clearance) / (1f - clearance)).coerceIn(0f, 1f)
    return p * (.58f + .42f * p)
}

internal fun playerArtworkSizeProgress(expansion: Float, fullArtworkTop: Float, fullArtworkHeight: Float,
    fullTitleTop: Float, miniArtworkTop: Float, miniArtworkSize: Float): Float = playerArtworkSizeProgress(expansion,
    playerArtworkClearance(fullArtworkTop, fullArtworkHeight, fullTitleTop, miniArtworkTop, miniArtworkSize))

/** A compatible cover reveals more of its retained mini crop as its frame grows. Never switch
 * a non-square image abruptly to Fit at the clearance gate. The retention blend changes only
 * when layout/aspect compatibility changes, and is zero for the unchanged fallback animation. */
internal fun playerArtworkContentScale(miniSize: Float, cropRetention: Float, collapsed: Boolean): ContentScale {
    if (collapsed) return ContentScale.Crop
    if (cropRetention <= 0f) return ContentScale.Fit
    return object : ContentScale {
        override fun computeScaleFactor(srcSize: Size, dstSize: Size): ScaleFactor {
            val fit = ContentScale.Fit.computeScaleFactor(srcSize, dstSize)
            val mini = ContentScale.Crop.computeScaleFactor(srcSize, Size(miniSize, miniSize))
            val weight = cropRetention.coerceIn(0f, 1f)
            return ScaleFactor(fit.scaleX + (maxOf(fit.scaleX, mini.scaleX) - fit.scaleX) * weight,
                fit.scaleY + (maxOf(fit.scaleY, mini.scaleY) - fit.scaleY) * weight)
        }
    }
}
