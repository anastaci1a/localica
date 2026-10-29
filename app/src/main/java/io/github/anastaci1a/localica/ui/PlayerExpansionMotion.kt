package io.github.anastaci1a.localica.ui

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.ScaleFactor
import androidx.compose.runtime.staticCompositionLocalOf
import kotlin.math.abs
import kotlin.math.pow

/** Selected mode is independent of the expanded target, which only changes on release. */
internal data class PlayerLyricsMotion(val layout: Float, val contentAlpha: Float, val artworkAlpha: Float)

internal fun playerLyricsMotion(expansion: Float, modeVisibility: Float): PlayerLyricsMotion {
    val p = expansion.coerceIn(0f, 1f)
    val mode = modeVisibility.coerceIn(0f, 1f)
    return PlayerLyricsMotion(mode * p, mode * playerReveal((p - .78f) / .22f), 1f - mode * p)
}

internal data class PlayerRowMotion(val seekOffset: Float, val actionsOffset: Float,
    val seekAlpha: Float, val actionsAlpha: Float)

/** Follow distinct mixtures of the unchanged title/transport displacement from their targets.
 * The common surface translation cancels out; neither reference path is redesigned.
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

/** Vertical clearance still determines mini-crop retention, but never gates frame growth.
 * This vertical-only reference cannot establish the bottom-right/title-band constraint; use
 * [playerArtworkGrowthCurve] with the presented horizontal geometry for that constraint. */
internal fun playerArtworkClearance(fullArtworkTop: Float, fullArtworkHeight: Float,
    fullTitleTop: Float, miniArtworkTop: Float, miniArtworkSize: Float, titleQuadratic: Float = 0f): Float {
    if (fullArtworkTop + fullArtworkHeight > fullTitleTop) return 0f
    val initialOverlap = miniArtworkTop + miniArtworkSize - 7f
    val linear = fullTitleTop - titleQuadratic - fullArtworkTop + miniArtworkTop + 41f
    // Delivered lyrics add titleQuadratic*p*p; solve against that unchanged title path.
    val discriminant = linear * linear + 4f * (titleQuadratic - 48f) * initialOverlap
    if (discriminant <= 0f) return 0f
    val gate = 2f * initialOverlap / (linear + kotlin.math.sqrt(discriminant))
    return gate.takeIf { it in 0f..<1f } ?: 0f
}

/** One sample of the existing trajectories, all in the same coordinate system and units.
 * A measured titleLeft enables corner-contact calibration with rectangle exclusion. Legacy
 * callers without it retain the older title-right-band contract and fixed left reference. */
internal data class PlayerArtworkTrajectorySample(val expansion: Float, val artworkLeft: Float,
    val artworkTop: Float, val titleTop: Float, val titleBottom: Float, val titleRight: Float,
    val titleLeft: Float = Float.NaN)

/** Constrained means unsupported input or no accepted curve under these sampled guards.
 * It does not prove that every other curve is impossible. Measured callers keep the bounded
 * best-effort curve even when contact cannot be admitted, avoiding a jump as layout animates.
 * Invalid input uses ordinary quadratic fallback; unreachable measured contact clamps to
 * the slowest bounded member. Legacy unmeasured callers retain their title-right solver.
 * None of these constrained cases guarantees rectangle exclusion. */
internal data class PlayerArtworkGrowthCurve(val curvature: Float, val constrained: Boolean,
    val cornerAligned: Boolean = false, val power: Float? = null)

internal data class PlayerArtworkGrowthPresentation(val expansion: Float,
    val curve: PlayerArtworkGrowthCurve, val trajectory: List<PlayerArtworkTrajectorySample>,
    val fullWidth: Float, val fullHeight: Float)

internal val LocalPlayerArtworkGrowthObserver =
    staticCompositionLocalOf<((PlayerArtworkGrowthPresentation) -> Unit)?> { null }

/** Select curvature from actual presented trajectories, including measured title height.
 * Recompute when those trajectories change, not for each finger-progress update. The caller
 * owns sampling density and native between-sample verification: this is a sampled constraint,
 * not a proof for an arbitrary continuous path. Endpoints and artwork/title positions never move.
 *
 * Each correction puts an offending corner above the title band OR within its right bound.
 * Sampled band-crossing intervals also constrain both horizontal endpoints conservatively.
 * This interval guard uses endpoint vertical ranges; an unrepresented between-sample extremum
 * is still the caller's responsibility, not an inferred bound on unknown trajectory motion.
 * Corrected corners stay safe as curvature increases. Recheck all other samples: a previously safe
 * corner below the band can enter it as growth slows, so band admission is not monotonic.
 * A fixed-geometry collision or excessive curvature returns an explicit unconstrained fallback. */
internal fun playerArtworkGrowthCurve(miniArtworkSize: Float, fullArtworkWidth: Float,
    fullArtworkHeight: Float, trajectory: List<PlayerArtworkTrajectorySample>,
    fixedLeftReference: Float = 16f): PlayerArtworkGrowthCurve {
    val fallback = PlayerArtworkGrowthCurve(0f, constrained = true)
    if (!miniArtworkSize.isFinite() || !fullArtworkWidth.isFinite() || !fullArtworkHeight.isFinite() ||
        !fixedLeftReference.isFinite() || miniArtworkSize <= 0f ||
        fullArtworkWidth <= 0f || fullArtworkHeight <= 0f ||
        trajectory.none { it.expansion == 0f } || trajectory.none { it.expansion == 1f }) return fallback
    if (trajectory.any { !it.expansion.isFinite() || it.expansion !in 0f..1f ||
            !it.artworkLeft.isFinite() || !it.artworkTop.isFinite() || !it.titleTop.isFinite() ||
            !it.titleBottom.isFinite() || !it.titleRight.isFinite() || it.titleBottom < it.titleTop ||
            it.titleRight < fixedLeftReference }) return fallback
    // Measured callers must not silently downgrade to the weaker title-right contract.
    // Missing/invalid measured geometry and infeasible contact remain explicitly constrained.
    if (trajectory.any { !it.titleLeft.isNaN() }) {
        if (trajectory.any { !it.titleLeft.isFinite() || it.titleLeft > it.titleRight }) return fallback
        return playerArtworkCornerCurve(miniArtworkSize, fullArtworkWidth, fullArtworkHeight, trajectory) ?: fallback
    }
    // Preserve the older solver's contract; measured corner fitting also supports a
    // narrow dimension shrinking through mini size as an extreme aspect ratio animates.
    if (fullArtworkWidth < miniArtworkSize || fullArtworkHeight < miniArtworkSize) return fallback
    val widthGrowth = fullArtworkWidth - miniArtworkSize
    val heightGrowth = fullArtworkHeight - miniArtworkSize
    val ordered = trajectory.sortedBy { it.expansion }
    var curvature = 0f
    repeat(trajectory.size * 3) {
        var required = curvature
        fun constrain(sample: PlayerArtworkTrajectorySample, allowAbove: Boolean): Boolean {
            val p = sample.expansion
            val rightLimit = if (widthGrowth > 0f)
                (sample.titleRight - sample.artworkLeft - miniArtworkSize - .0001f) / widthGrowth
                else Float.NEGATIVE_INFINITY
            val aboveLimit = if (allowAbove && heightGrowth > 0f)
                (sample.titleTop - sample.artworkTop - miniArtworkSize - .0001f) / heightGrowth
                else Float.NEGATIVE_INFINITY
            val limit = maxOf(rightLimit, aboveLimit)
            if (p == 0f || p == 1f || limit <= 0f) return false
            val needed = (p * p / limit - 1f) / (1f - p)
            if (!needed.isFinite() || needed > 2f || needed <= curvature) return false
            required = maxOf(required, needed)
            return true
        }
        for (sample in ordered) {
            val growth = playerArtworkSizeProgress(sample.expansion, curvature)
            val right = sample.artworkLeft + miniArtworkSize + widthGrowth * growth
            val bottom = sample.artworkTop + miniArtworkSize + heightGrowth * growth
            if (!right.isFinite() || !bottom.isFinite()) return fallback
            if (bottom < sample.titleTop || bottom > sample.titleBottom ||
                right in fixedLeftReference..sample.titleRight) continue
            // Slowing cannot repair the lower horizontal bound or a fixed endpoint.
            if (right < fixedLeftReference || !constrain(sample, allowAbove = true)) return fallback
        }
        for (index in 1 until ordered.size) {
            val first = ordered[index - 1]
            val last = ordered[index]
            if (first.expansion == last.expansion) continue
            val firstBottom = first.artworkTop + miniArtworkSize +
                heightGrowth * playerArtworkSizeProgress(first.expansion, curvature)
            val lastBottom = last.artworkTop + miniArtworkSize +
                heightGrowth * playerArtworkSizeProgress(last.expansion, curvature)
            if (!firstBottom.isFinite() || !lastBottom.isFinite()) return fallback
            // A band crossing may fall between samples even when neither endpoint is inside.
            // Conservatively constrain both horizontal endpoints of that sampled interval.
            if (maxOf(firstBottom - first.titleTop, lastBottom - last.titleTop) < 0f ||
                minOf(firstBottom - first.titleBottom, lastBottom - last.titleBottom) > 0f) continue
            for (sample in listOf(first, last)) {
                val right = sample.artworkLeft + miniArtworkSize +
                    widthGrowth * playerArtworkSizeProgress(sample.expansion, curvature)
                if (right < fixedLeftReference) return fallback
                if (right > sample.titleRight && !constrain(sample, allowAbove = false)) return fallback
            }
        }
        if (required == curvature) return PlayerArtworkGrowthCurve(curvature, constrained = false)
        curvature = required
    }
    return fallback
}

/** Fit a single immediate ease to the point where the artwork's bottom-right clears the
 * title's top-left. The contact equation is geometric and independent of the chosen easing.
 * Interpolate the caller's densely sampled geometry. Admit exclusion only when the bounded
 * curve meets contact and clears the title; otherwise retain its best effort, marked constrained.
 * This also covers artwork intentionally behind text without an admission-boundary size jump.
 * Prefer the quadratic rational ease for noticeable early growth. Compact layouts extend
 * the same family continuously: increase its power while reducing curvature by the same
 * amount, keeping endpoint slope at four without a hold, jump, or extra animation clock.
 * No new animation clock, delay, or phase boundary is introduced. */
private fun playerArtworkCornerCurve(mini: Float, width: Float, height: Float,
    trajectory: List<PlayerArtworkTrajectorySample>): PlayerArtworkGrowthCurve? {
    val dw = width - mini
    val dh = height - mini
    if (dw == 0f && dh == 0f) return PlayerArtworkGrowthCurve(0f, constrained = true)
    val ordered = trajectory.sortedBy { it.expansion }
    fun horizontal(s: PlayerArtworkTrajectorySample) = s.titleLeft - s.artworkLeft - mini
    fun vertical(s: PlayerArtworkTrajectorySample) = s.titleTop - s.artworkTop - mini
    // Cross-multiplication avoids a singularity when either dimension passes mini size.
    // Use the larger-changing axis to recover growth without dividing by a near-zero delta.
    fun growth(s: PlayerArtworkTrajectorySample) = if (abs(dw) >= abs(dh)) horizontal(s) / dw else vertical(s) / dh
    fun distance(s: PlayerArtworkTrajectorySample) = dh * horizontal(s) - dw * vertical(s)
    fun interpolate(a: PlayerArtworkTrajectorySample, b: PlayerArtworkTrajectorySample, t: Float): PlayerArtworkTrajectorySample {
        fun mix(x: Float, y: Float) = x + (y - x) * t
        return PlayerArtworkTrajectorySample(mix(a.expansion, b.expansion), mix(a.artworkLeft, b.artworkLeft),
            mix(a.artworkTop, b.artworkTop), mix(a.titleTop, b.titleTop), mix(a.titleBottom, b.titleBottom),
            mix(a.titleRight, b.titleRight), mix(a.titleLeft, b.titleLeft))
    }
    for (index in 1 until ordered.size) {
        val a = ordered[index - 1]
        val b = ordered[index]
        val da = distance(a)
        val db = distance(b)
        if ((da > 0f && db > 0f) || (da < 0f && db < 0f) || da == db || a.expansion == b.expansion) continue
        val contact = interpolate(a, b, da / (da - db))
        val p = contact.expansion
        val g = growth(contact)
        if (p <= 0f || p >= 1f) continue
        fun safe(curve: PlayerArtworkGrowthCurve): Boolean {
            // Check the complete sampled trajectory, including sub-samples at the corner.
            for (segment in 1 until ordered.size) {
                val first = ordered[segment - 1]
                val last = ordered[segment]
                for (step in 0..8) {
                    val t = step / 8f
                    fun mix(x: Float, y: Float) = x + (y - x) * t
                    val size = playerArtworkSizeProgress(mix(first.expansion, last.expansion), curve)
                    val top = mix(first.artworkTop, last.artworkTop)
                    val right = mix(first.artworkLeft, last.artworkLeft) + mini + dw * size
                    val bottom = top + mini + dh * size
                    if (bottom > mix(first.titleTop, last.titleTop) + .02f && top < mix(first.titleBottom, last.titleBottom) &&
                        right > mix(first.titleLeft, last.titleLeft) + .02f) return false
                }
            }
            return true
        }
        val bend = if (g <= 0f) Float.POSITIVE_INFINITY else (p * p / g - 1f) / (1f - p)
        val curve = if (bend <= 2f) {
            PlayerArtworkGrowthCurve(bend.coerceAtLeast(-.95f), constrained = true)
        } else {
            // This extends curvature=2 continuously, then clamps to p^4 if even that is
            // too large. Do not switch back to p^2 when contact becomes infeasible.
            var low = 2f
            var high = 4f
            repeat(24) {
                val power = (low + high) / 2f
                val size = p.pow(power) / (1f + (4f - power) * (1f - p))
                if (size > g) low = power else high = power
            }
            val power = (low + high) / 2f
            PlayerArtworkGrowthCurve(4f - power, constrained = true, power = power)
        }
        val contactError = playerArtworkSizeProgress(p, curve) - g
        val admitted = ordered.last().artworkTop + height <= ordered.last().titleTop &&
            abs(contactError * dw) <= .02f && abs(contactError * dh) <= .02f && safe(curve)
        return curve.copy(constrained = !admitted, cornerAligned = admitted)
    }
    // For valid but unreachable contact, stay at the slowest bounded member. Actual
    // extreme aspect trajectories approach this clamp before their root disappears.
    return PlayerArtworkGrowthCurve(0f, constrained = true, power = 4f)
}

/** Immediate, reversible growth with no hold, threshold splice or independent clock.
 * Curvature -.95..2 keeps growth >= p²/3 and the normalized slope <= 4, including at the endpoint;
 * unsupported geometry must not turn into a practical hold followed by an endpoint jump. */
internal fun playerArtworkSizeProgress(expansion: Float, curvature: Float): Float {
    val p = if (expansion.isNaN()) 0f else expansion.coerceIn(0f, 1f)
    val bend = if (curvature.isNaN()) 0f else curvature.coerceIn(-.95f, 2f)
    return p * p / (1f + bend * (1f - p))
}

internal fun playerArtworkSizeProgress(expansion: Float, curve: PlayerArtworkGrowthCurve): Float {
    val power = curve.power ?: return playerArtworkSizeProgress(expansion, curve.curvature)
    val p = if (expansion.isNaN()) 0f else expansion.coerceIn(0f, 1f)
    return p.pow(power.coerceIn(2f, 4f)) / (1f + curve.curvature * (1f - p))
}

// Compatibility for callers still using the vertical crop-retention reference: it now bends
// the immediate ease instead of delaying growth. Only measured trajectory input admits bounds.
internal fun playerArtworkSizeProgress(expansion: Float, fullArtworkTop: Float, fullArtworkHeight: Float,
    fullTitleTop: Float, miniArtworkTop: Float, miniArtworkSize: Float, titleQuadratic: Float = 0f): Float = playerArtworkSizeProgress(expansion,
    playerArtworkClearance(fullArtworkTop, fullArtworkHeight, fullTitleTop, miniArtworkTop, miniArtworkSize, titleQuadratic))

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
