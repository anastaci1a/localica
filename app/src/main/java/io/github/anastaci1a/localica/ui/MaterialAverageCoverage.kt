package io.github.anastaci1a.localica.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import java.util.Collections

internal enum class MaterialAverageCoverageStatus {
    INVALID_PROVENANCE, INVALID_GEOMETRY, INVALID_MAPPING, UNSAMPLED, SAMPLED,
}

internal data class MaterialAverageSample(val sourcePosition: Offset, val valid: Boolean)

/** A source-recording sampling plan, not geometric intersection, ancestor clipping or native visibility proof. */
internal class MaterialAverageCoverage(
    val status: MaterialAverageCoverageStatus,
    samples: List<MaterialAverageSample>,
) {
    val samples: List<MaterialAverageSample> = Collections.unmodifiableList(ArrayList(samples))
    val validCount: Int = this.samples.count { it.valid }
}

/**
 * Exactly 64 consumer-center samples in row-major order for valid geometry/mapping.
 * UNSAMPLED means this finite grid missed all valid material, not that intersection is empty.
 * No detailed/parent sampling or previous color is admitted by a rejected/unsampled plan.
 */
internal fun materialAverageCoverage(
    materialOnly: Boolean,
    recorded: Boolean,
    sourceWidth: Float,
    sourceHeight: Float,
    sourceCorner: Float,
    consumerExtent: Size,
    consumerToSource: BackdropRegistration?,
): MaterialAverageCoverage {
    fun rejected(status: MaterialAverageCoverageStatus) = MaterialAverageCoverage(status, emptyList())
    if (!materialOnly || !recorded) return rejected(MaterialAverageCoverageStatus.INVALID_PROVENANCE)
    if (!listOf(sourceWidth, sourceHeight, sourceCorner, consumerExtent.width, consumerExtent.height).all { it.isFinite() } ||
        sourceWidth <= 0f || sourceHeight <= 0f || sourceCorner < 0f ||
        consumerExtent.width <= 0f || consumerExtent.height <= 0f) {
        return rejected(MaterialAverageCoverageStatus.INVALID_GEOMETRY)
    }
    // Match backdropContainsFootprint's radius clamp; reject squared-distance overflow explicitly.
    val inset = sourceCorner.coerceAtMost(minOf(sourceWidth, sourceHeight) / 2f)
    if (!(inset * inset).isFinite()) return rejected(MaterialAverageCoverageStatus.INVALID_GEOMETRY)
    val mapping = consumerToSource ?: return rejected(MaterialAverageCoverageStatus.INVALID_MAPPING)
    val samples = ArrayList<MaterialAverageSample>(64)
    for (row in 0 until 8) for (column in 0 until 8) {
        val consumer = Offset(consumerExtent.width * ((column + .5f) / 8f),
            consumerExtent.height * ((row + .5f) / 8f))
        val point = mapping.map(consumer)
        if (!point.x.isFinite() || !point.y.isFinite()) return rejected(MaterialAverageCoverageStatus.INVALID_MAPPING)
        // Repeating one point uses precisely the existing rounded-recording containment rule.
        val valid = backdropContainsFootprint(sourceWidth, sourceHeight, sourceCorner, List(4) { point })
        samples += MaterialAverageSample(point, valid)
    }
    return MaterialAverageCoverage(if (samples.any { it.valid }) MaterialAverageCoverageStatus.SAMPLED
        else MaterialAverageCoverageStatus.UNSAMPLED, samples)
}
