package io.github.anastaci1a.localica

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import io.github.anastaci1a.localica.ui.GlassSurfaceDrawObservation
import io.github.anastaci1a.localica.ui.FrostedBackdrop

/** Immutable acceptance evidence from the FIRST coating draw of each semantic header owner.
 * Numeric identities correlate within an arm only. Cross-arm comparison uses semantic roles. */
internal data class HeaderFirstApplyReceipt(
    val roles: Map<String, HeaderFirstApplySource>,
    val sourceAliases: Set<Set<String>>,
)
internal data class HeaderFirstApplySource(
    val surfaceIdentity: Int, val sourceIdentity: Int, val request: Int, val published: Int,
    val snapshotPresent: Boolean, val recorded: Boolean, val released: Boolean,
    val sourceWidth: Int, val sourceHeight: Int, val mapping: List<Float>,
) {
    fun contract() = listOf(request, published, snapshotPresent, recorded, released,
        sourceWidth, sourceHeight, mapping)
}
internal class HeaderFirstApplyMismatch(message: String) : AssertionError(message)

internal fun freezeHeaderFirstApply(
    observations: List<GlassSurfaceDrawObservation>, semanticOwners: Map<String, Rect>,
): HeaderFirstApplyReceipt {
    check(semanticOwners.isNotEmpty()) { "Missing semantic header owners" }
    val roles = semanticOwners.mapValues { (role, owner) ->
        check(owner.width > 0f && owner.height > 0f) { "Header owner has no extent: $role" }
        // Every repeated draw retains its own first receipt. Select by semantic owner position,
        // never by a width heuristic. A full-player backing must not substitute for a control.
        val surfaces = observations.groupBy { it.surfaceIdentity }.values.map { it.first() }
            .filter { it.visible && it.attached && it.width > 0f && it.height > 0f }
            .filter { o ->
                val c = o.rootCorners ?: return@filter false
                c.size == 8 && c.all(Float::isFinite) &&
                    Rect(c.filterIndexed { i, _ -> i % 2 == 0 }.min(),
                        c.filterIndexed { i, _ -> i % 2 == 1 }.min(),
                        c.filterIndexed { i, _ -> i % 2 == 0 }.max(),
                        c.filterIndexed { i, _ -> i % 2 == 1 }.max()).contains(owner.center)
            }.sortedBy { it.width * it.height }
        check(surfaces.isNotEmpty()) { "Missing visible coating draw for $role" }
        val draw = surfaces.first()
        check(surfaces.size == 1 || draw.width * draw.height < surfaces[1].width * surfaces[1].height) {
            "Ambiguous semantic header coating: $role"
        }
        // The selected coating must fit inside its semantic owner's region. This rejects
        // accidentally selecting the whole player when a header draw is missing.
        val c = requireNotNull(draw.rootCorners)
        check((0..3).all { owner.inflate(1f).contains(Offset(c[it * 2], c[it * 2 + 1])) }) {
            "Coating does not belong to semantic header owner: $role"
        }
        val source = checkNotNull(draw.source) { "Missing header source: $role" }
        check(!source.released && source.width > 0 && source.height > 0 &&
            source.layerWidth > 0 && source.layerHeight > 0) { "Invalid header source: $role" }
        val mapping = checkNotNull(source.sourceToConsumer) { "Unregistered header source: $role" }
        check(mapping.size == 6 && mapping.all(Float::isFinite)) { "Invalid source mapping: $role" }
        HeaderFirstApplySource(draw.surfaceIdentity, source.identity, source.requestedReadback,
            source.publishedReadback, source.snapshotIdentity != null, source.recorded, source.released,
            source.width, source.height, mapping.toList())
    }
    check(roles.values.map { it.surfaceIdentity }.toSet().size == roles.size) {
        "One coating substituted for multiple semantic header owners"
    }
    val aliases = roles.entries.groupBy { it.value.sourceIdentity }.values.map { entries ->
        entries.map { it.key }.toSet()
    }.toSet()
    return HeaderFirstApplyReceipt(roles.toMap(), aliases)
}

/** Must run before native readback waiting or later-pixel capture. No catch/fallback in equality. */
internal fun assertHeaderFirstApplyEqual(reference: HeaderFirstApplyReceipt, candidate: HeaderFirstApplyReceipt) {
    if (reference.roles.keys != candidate.roles.keys || reference.sourceAliases != candidate.sourceAliases ||
        reference.roles.any { (role, value) -> value.contract() != candidate.roles[role]?.contract() })
        throw HeaderFirstApplyMismatch("Initial header readiness/request contract differs: reference=$reference candidate=$candidate")
}

/** The request set is frozen at first draw. A later request or publication is not a replacement
 * for that frame, even if it makes later pixels equal. Called after native image readback too. */
internal fun assertHeaderRequestSetUnchanged(
    receipt: HeaderFirstApplyReceipt, current: Map<Int, FrostedBackdrop>,
) {
    receipt.roles.forEach { (role, initial) ->
        val source = checkNotNull(current[initial.sourceIdentity]) { "Frozen source disappeared: $role" }
        if (source.readbackRequest != initial.request || source.snapshotReadbackRequest != initial.published ||
            (source.snapshot != null) != initial.snapshotPresent)
            throw HeaderFirstApplyMismatch("Native readback replaced the first header request/publication: $role initial=$initial current=$source")
    }
}
