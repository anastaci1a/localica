package io.github.anastaci1a.localica.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect

internal sealed interface ArtworkWashMappingResult<out T> {
    data class Known<T>(val value: T) : ArtworkWashMappingResult<T>
    data class Unavailable(val reason: Reason) : ArtworkWashMappingResult<Nothing>
    enum class Reason { MissingGeometry, InvalidGeometry, UnrepresentableBasis, NonFinitePoint, Overflow }
}

/** Continuous texture-edge coordinates; filtering/texel-center taps are not chosen here. */
internal data class ArtworkWashSamplePoint(
    val texture: Offset,
    val normalized: Offset,
    val clampedNormalized: Offset,
) {
    val wasClamped: Boolean get() = normalized != clampedNormalized
}

/** The existing PlayerBackdrop wash matrix, independent of pixels or foreground.
 *
 * Texture origin includes ArtworkWash.TOP, not the cover's top. Forward maps wash
 * pixel coordinates to player-local coordinates; inverse preserves outside points.
 * sample applies CLAMP in normalized texture-edge coordinates. A later pixel sampler
 * must honor its own texel-center/filter convention; these values are not colors.
 * Direct-cover ContentScale/feather, blends, viewport clipping and polarity are outside
 * this helper. A known wash mapping does not establish which background role is visible.
 */
internal class ArtworkWashMapping private constructor(
    val coverBounds: Rect,
    val scaleX: Float,
    val scaleY: Float,
    val translation: Offset,
) {
    companion object {
        fun create(coverBounds: Rect?): ArtworkWashMappingResult<ArtworkWashMapping> {
            if (coverBounds == null) return unavailable(ArtworkWashMappingResult.Reason.MissingGeometry)
            val r = coverBounds
            if (!r.left.isFinite() || !r.top.isFinite() || !r.right.isFinite() || !r.bottom.isFinite() ||
                !r.width.isFinite() || !r.height.isFinite() || r.width <= 0f || r.height <= 0f) {
                return unavailable(ArtworkWashMappingResult.Reason.InvalidGeometry)
            }
            // Same Float operations/constants as the existing shader local matrix.
            val sx = r.width / ArtworkWash.WIDTH
            val sy = r.height / ArtworkWash.WIDTH
            val translation = Offset(r.left, r.top + r.height * ArtworkWash.TOP)
            val extent = Offset(ArtworkWash.WIDTH * sx + translation.x, ArtworkWash.HEIGHT * sy + translation.y)
            if (!sx.isFinite() || !sy.isFinite() || sx <= 0f || sy <= 0f || !finite(translation) || !finite(extent)) {
                return unavailable(ArtworkWashMappingResult.Reason.UnrepresentableBasis)
            }
            return ArtworkWashMappingResult.Known(ArtworkWashMapping(r, sx, sy, translation))
        }

        private fun finite(point: Offset) = point.x.isFinite() && point.y.isFinite()
        private fun unavailable(reason: ArtworkWashMappingResult.Reason) = ArtworkWashMappingResult.Unavailable(reason)
    }

    fun forward(texturePoint: Offset): ArtworkWashMappingResult<Offset> {
        if (!finite(texturePoint)) return unavailable(ArtworkWashMappingResult.Reason.NonFinitePoint)
        val point = Offset(texturePoint.x * scaleX + translation.x, texturePoint.y * scaleY + translation.y)
        return if (finite(point)) ArtworkWashMappingResult.Known(point)
        else unavailable(ArtworkWashMappingResult.Reason.Overflow)
    }

    fun inverse(playerPoint: Offset): ArtworkWashMappingResult<Offset> {
        if (!finite(playerPoint)) return unavailable(ArtworkWashMappingResult.Reason.NonFinitePoint)
        val point = Offset((playerPoint.x - translation.x) / scaleX, (playerPoint.y - translation.y) / scaleY)
        return if (finite(point)) ArtworkWashMappingResult.Known(point)
        else unavailable(ArtworkWashMappingResult.Reason.Overflow)
    }

    fun sample(playerPoint: Offset): ArtworkWashMappingResult<ArtworkWashSamplePoint> = when (val mapped = inverse(playerPoint)) {
        is ArtworkWashMappingResult.Unavailable -> mapped
        is ArtworkWashMappingResult.Known -> {
            val texture = mapped.value
            val normalized = Offset(texture.x / ArtworkWash.WIDTH, texture.y / ArtworkWash.HEIGHT)
            if (!finite(normalized)) unavailable(ArtworkWashMappingResult.Reason.Overflow)
            else ArtworkWashMappingResult.Known(ArtworkWashSamplePoint(texture, normalized,
                Offset(normalized.x.coerceIn(0f, 1f), normalized.y.coerceIn(0f, 1f))))
        }
    }
}
