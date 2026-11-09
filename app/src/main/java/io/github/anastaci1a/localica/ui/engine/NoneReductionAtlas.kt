package io.github.anastaci1a.localica.ui.engine

import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.GraphicsContext
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.CompositingStrategy
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.platform.LocalGraphicsContext
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection

internal data class NoneAtlasStyle(val whiteTint: Float = 0f, val trim: Float = 0f, val weight: Float = 0f,
    val lightMix: Float = 0f, val opaque: Boolean = false)

/** The window's source-specific None reductions, or null where every surface reduces on its own. */
internal val LocalNoneReductionAtlas = staticCompositionLocalOf<NoneReductionAtlases?> { null }

/** Provide independent None reductions for the source layers in this window (#691). Below Android 13
 * materials keep their own software average, so the content is composed unchanged. */
@Composable
internal fun NoneReductionAtlasHost(colorShader: String, enabled: Boolean = true, content: @Composable () -> Unit) {
    val graphics = LocalGraphicsContext.current
    val atlas = if (enabled && Build.VERSION.SDK_INT >= 33) remember(graphics, colorShader) { NoneReductionAtlases(graphics, colorShader) } else null
    DisposableEffect(atlas) { onDispose { if (Build.VERSION.SDK_INT >= 33) atlas?.release() } }
    CompositionLocalProvider(LocalNoneReductionAtlas provides atlas, content = content)
}

/** Window owner of independent atlases. Source identity is the actual sampled GraphicsLayer. */
@RequiresApi(33)
internal class NoneReductionAtlases(private val graphics: GraphicsContext, private val colorShader: String) {
    private val sources = java.util.IdentityHashMap<GraphicsLayer, NoneReductionAtlas>()
    private val leases = java.util.IdentityHashMap<GraphicsLayer, Int>()
    val sourceCount get() = sources.size
    fun retain(source: GraphicsLayer) { leases[source] = (leases[source] ?: 0) + 1 }
    fun forget(source: GraphicsLayer) {
        val count = (leases[source] ?: return) - 1
        if (count > 0) leases[source] = count else {
            leases.remove(source)
            sources.remove(source)?.release()
        }
    }
    fun forSource(source: GraphicsLayer): NoneReductionAtlas = sources.getOrPut(source) {
        NoneReductionAtlas(graphics, colorShader)
    }
    fun release() { sources.values.forEach { it.release() }; sources.clear(); leases.clear() }
}

/** Acquire during apply, not while composing a replacement which can precede the old owner's
 * disposal. RememberObserver also avoids allocating an owner for an abandoned composition. */
@RequiresApi(33)
internal class NoneReductionAtlasLease(private val registry: NoneReductionAtlases,
    private val source: GraphicsLayer) : RememberObserver {
    lateinit var atlas: NoneReductionAtlas
        private set
    private var retained = false
    override fun onRemembered() {
        check(!retained)
        atlas = registry.forSource(source)
        registry.retain(source)
        retained = true
    }
    override fun onForgotten() {
        if (retained) { registry.forget(source); retained = false }
    }
    override fun onAbandoned() = Unit
}

/** One reduction per source layer per frame instead of one chain for each consumer.
 *
 * Every member owns a cell of its power-of-two extent (exactly the extent its own `recordFullBounds`
 * would use) in a 2048 px wide level-0 texture, aligned to 64 px so no 8×8 block of the two shared
 * block-mean steps straddles two cells. A third step takes each cell's remaining texels to at most 8×8
 * and a fourth styles every member's mean into its own 4×4 block of the output; members draw the
 * central 2×2 texels of their block scaled to their size, so bilinear upscaling stays exact. The
 * arithmetic is the per-surface chain's; only the texture is shared. Five offscreen passes per source
 * per frame, whatever the number of members.
 *
 * Members record the atlas themselves whenever their own registration changes (synchronously, in
 * their draw), so the texture is consistent with every member in the same frame; on frames where no
 * member changes nothing is re-recorded and HWUI re-renders the four layers when a source changes. */
@RequiresApi(33)
@Stable
internal class NoneReductionAtlas(private val graphics: GraphicsContext, colorShader: String) {
    /** A member's cell and block. [index] and [cell] stay fixed while the member lives, so a member
     * never has to redraw when another one joins or leaves. */
    class Entry {
        internal var index = -1
        internal var cell = IntOffset(-1, -1)
        internal var extent = IntSize.Zero
        internal var region = Rect.Zero
        internal var registration: Any? = null
        internal var backing: Any? = null
        internal var style: NoneAtlasStyle? = null
        internal var structure: Any? = null
        internal var record: (DrawScope.() -> Unit)? = null
        val placed get() = index >= 0 && cell.x >= 0
    }

    private class Cell(val origin: IntOffset, val size: IntSize) { var free = true }
    private class Row(val y: Int, val height: Int) { var next = 0 }

    private val members = ArrayList<Entry>()
    private val indices = BooleanArray(MaxMembers)
    private val cells = ArrayList<Cell>()
    private val rows = ArrayList<Row>()
    private var cellsHeight = 0
    private var dirty = false
    private var recordDensity = Density(1f)
    private var recordDirection = LayoutDirection.Ltr
    private val level0 = layer(); private val level1 = layer(); private val level2 = layer()
    private val slotLevel = layer(); private val output = layer()
    private var passes = 0
    /** Diagnostics: how many times the shared chain has been re-recorded. */
    val recordings get() = passes
    /** Test witness for hosted cells; rejected small/overflow members never acquire an extent here. */
    val hostedExtents get() = members.filter { it.placed }.map { it.extent }
    val hostedRegions get() = members.filter { it.placed }.map { it.region }

    private fun layer() = graphics.createGraphicsLayer().apply {
        io.github.anastaci1a.localica.ui.GlassWork.event("layer")
        compositingStrategy = CompositingStrategy.Offscreen
        clip = true
        // A member may reference the output before the first record; an empty layer still draws.
        record(Density(1f), LayoutDirection.Ltr, IntSize(1, 1)) { }
    }

    fun add(entry: Entry) {
        if (entry in members) return
        members += entry
        dirty = true
    }

    fun remove(entry: Entry) {
        if (!members.remove(entry)) return
        if (entry.index >= 0) indices[entry.index] = false
        cells.firstOrNull { it.origin == entry.cell && it.size == entry.extent }?.free = true
        entry.index = -1; entry.cell = IntOffset(-1, -1); entry.record = null
        entry.backing = null; entry.registration = null; entry.structure = null; entry.style = null
        dirty = true
    }

    /** Fit [extent] (a power of two per axis, at least 64, at most the atlas width) into a free cell. */
    private fun allocate(extent: IntSize): IntOffset? {
        cells.firstOrNull { it.free && it.size == extent }?.let { it.free = false; return it.origin }
        rows.firstOrNull { it.height == extent.height && it.next + extent.width <= Width }?.let { row ->
            val cell = Cell(IntOffset(row.next, row.y), extent).also { it.free = false }
            row.next += extent.width; cells += cell
            return cell.origin
        }
        if (cellsHeight + extent.height > MaxHeight) return null
        val row = Row(cellsHeight, extent.height); rows += row; cellsHeight += extent.height
        val cell = Cell(IntOffset(0, row.y), extent).also { it.free = false }
        row.next = extent.width; cells += cell
        return cell.origin
    }

    /** Register this frame's sampling for [entry]; false when the atlas cannot host it (the caller
     * keeps its own chain). Re-records the shared chain at once when anything changed. */
    fun DrawScope.place(entry: Entry, backing: Any, registration: Any, structure: Any,
        region: Rect, style: NoneAtlasStyle, record: DrawScope.() -> Unit): Boolean {
        if (entry !in members) return false
        val extent = materialReductionExtent(IntSize(kotlin.math.ceil(region.width).toInt(), kotlin.math.ceil(region.height).toInt()))
        if (extent.width < MinCell || extent.height < MinCell || extent.width > Width || extent.height > MaxHeight) return false
        var changed = dirty
        if (entry.index < 0) {
            val index = indices.indexOfFirst { !it }
            if (index < 0) return false
            indices[index] = true; entry.index = index; changed = true
        }
        if (entry.extent != extent || entry.cell.x < 0) {
            if (entry.cell.x >= 0) cells.firstOrNull { it.origin == entry.cell && it.size == entry.extent }?.free = true
            val cell = allocate(extent)
            if (cell == null) {
                indices[entry.index] = false; entry.index = -1; entry.cell = IntOffset(-1, -1); entry.extent = IntSize.Zero
                return false
            }
            entry.cell = cell; entry.extent = extent; changed = true
        }
        if (entry.backing !== backing || entry.registration != registration || entry.region != region || entry.style != style || entry.structure != structure) {
            entry.backing = backing; entry.registration = registration; entry.region = region; entry.style = style; entry.structure = structure
            entry.record = record; changed = true
        }
        recordDensity = Density(density, fontScale); recordDirection = layoutDirection
        if (changed) recordAll()
        return true
    }

    /** The member's styled mean over its whole size. */
    fun DrawScope.drawMember(entry: Entry) {
        if (!entry.placed) return
        val block = blockOrigin(entry.index)
        clipRect {
            scale(size.width / 2f, size.height / 2f, Offset.Zero) {
                translate(-(block.x + 1f), -(block.y + 1f)) { drawLayer(output) }
            }
        }
    }

    private fun blockOrigin(index: Int) = IntOffset(4 * (index % Columns), 4 * (index / Columns))

    private fun recordAll() {
        dirty = false
        passes++
        val placed = members.filter { it.placed && it.record != null }
        val used = (placed.maxOfOrNull { it.index } ?: -1) + 1
        val height = maxOf(cellsHeight, 256 * ((used + Columns - 1) / Columns)).coerceAtLeast(256)
        level0.renderEffect = null
        level0.record(recordDensity, recordDirection, IntSize(Width, height)) {
            for (entry in placed) {
                val record = entry.record ?: continue
                translate(entry.cell.x.toFloat(), entry.cell.y.toFloat()) {
                    // Exactly recordFullBounds: the footprint fills its whole cell.
                    scale(entry.extent.width / entry.region.width, entry.extent.height / entry.region.height, Offset.Zero) {
                        clipRect(right = entry.region.width, bottom = entry.region.height) { record() }
                    }
                }
            }
        }
        level0.renderEffect = MaterialBlockMean.effect(IntSize(8, 8))
        level1.record(recordDensity, recordDirection, IntSize(Width / 8, height / 8)) { drawLayer(level0) }
        level1.renderEffect = MaterialBlockMean.effect(IntSize(8, 8))
        val level2Size = IntSize(Width / 64, height / 64)
        level2.record(recordDensity, recordDirection, level2Size) { drawLayer(level1) }
        // Per member: its level-2 region (cell / 64) reduced to at most 8×8 texels in place, then styled.
        val slots = FloatArray(4 * MaxMembers); val sizes = FloatArray(4 * MaxMembers)
        val regions = FloatArray(4 * MaxMembers); val looks = FloatArray(4 * MaxMembers); val modes = FloatArray(4 * MaxMembers)
        for (entry in placed) {
            val i = entry.index
            val ox = entry.cell.x / 64f; val oy = entry.cell.y / 64f
            val w = entry.extent.width / 64; val h = entry.extent.height / 64
            val bx = ((w + 7) / 8).coerceAtLeast(1); val by = ((h + 7) / 8).coerceAtLeast(1)
            slots[4 * i] = ox; slots[4 * i + 1] = oy; slots[4 * i + 2] = bx.toFloat(); slots[4 * i + 3] = by.toFloat()
            sizes[4 * i] = (w / bx).toFloat(); sizes[4 * i + 1] = (h / by).toFloat()
            regions[4 * i] = ox; regions[4 * i + 1] = oy; regions[4 * i + 2] = (w / bx).toFloat(); regions[4 * i + 3] = (h / by).toFloat()
            val style = entry.style ?: NoneAtlasStyle()
            looks[4 * i] = style.whiteTint; looks[4 * i + 1] = style.trim; looks[4 * i + 2] = style.weight; looks[4 * i + 3] = style.lightMix
            modes[4 * i] = 1f; modes[4 * i + 1] = if (style.opaque) 1f else 0f; modes[4 * i + 2] = 1f
        }
        level2.renderEffect = slotBlockMean(slots, sizes)
        slotLevel.record(recordDensity, recordDirection, level2Size) { drawLayer(level2) }
        slotLevel.renderEffect = slotStyle(regions, looks, modes)
        output.record(recordDensity, recordDirection, IntSize(4 * Columns, 4 * ((used + Columns - 1) / Columns).coerceAtLeast(1))) { drawLayer(slotLevel) }
    }

    private val slotBlockShader = RuntimeShader("""
        uniform shader scene;
        uniform float4 slots[$MaxMembers];
        uniform float4 sizes[$MaxMembers];
        half4 main(float2 p) {
            float4 s = float4(0); float4 z = float4(0);
            for (int k = 0; k < $MaxMembers; k++) {
                float4 sk = slots[k]; float4 zk = sizes[k];
                if (zk.x > 0.0 && p.x >= sk.x && p.y >= sk.y && p.x < sk.x + zk.x && p.y < sk.y + zk.y) { s = sk; z = zk; }
            }
            if (z.x <= 0.0) return half4(0);
            float2 origin = s.xy + floor(p - s.xy) * s.zw;
            float4 sum = float4(0);
            for (int y = 0; y < 8; y++) {
                for (int x = 0; x < 8; x++) {
                    if (float(x) < s.z && float(y) < s.w) sum += float4(scene.eval(origin + float2(float(x) + 0.5, float(y) + 0.5)));
                }
            }
            return half4(sum / (s.z * s.w));
        }
    """.trimIndent())
    private fun slotBlockMean(slots: FloatArray, sizes: FloatArray): RenderEffect {
        slotBlockShader.setFloatUniform("slots", slots)
        slotBlockShader.setFloatUniform("sizes", sizes)
        return android.graphics.RenderEffect.createRuntimeShaderEffect(slotBlockShader, "scene").asComposeRenderEffect()
    }

    private val slotStyleShader = RuntimeShader("""
        uniform shader scene;
        uniform float4 regions[$MaxMembers];
        uniform float4 looks[$MaxMembers];
        uniform float4 modes[$MaxMembers];
        $colorShader
        half4 main(float2 p) {
            int index = int(floor(p.x / 4.0)) + int(floor(p.y / 4.0)) * $Columns;
            float4 r = float4(0); float4 l = float4(0); float4 m = float4(0);
            for (int k = 0; k < $MaxMembers; k++) {
                if (k == index) { r = regions[k]; l = looks[k]; m = modes[k]; }
            }
            if (m.z < 0.5) return half4(0);
            float4 mean = float4(0);
            for (int y = 0; y < 8; y++) {
                for (int x = 0; x < 8; x++) {
                    if (float(x) < r.z && float(y) < r.w) mean += float4(scene.eval(r.xy + float2(float(x) + 0.5, float(y) + 0.5)));
                }
            }
            mean /= r.z * r.w;
            float alpha = m.y > 0.5 ? 1.0 : min(mean.a / m.x, 1.0);
            return half4(half3(glassColor(mean.rgb / max(mean.a, 0.0001), l.x, l.yzw) * alpha), half(alpha));
        }
    """.trimIndent())
    private fun slotStyle(regions: FloatArray, looks: FloatArray, modes: FloatArray): RenderEffect {
        slotStyleShader.setFloatUniform("regions", regions)
        slotStyleShader.setFloatUniform("looks", looks)
        slotStyleShader.setFloatUniform("modes", modes)
        return android.graphics.RenderEffect.createRuntimeShaderEffect(slotStyleShader, "scene").asComposeRenderEffect()
    }

    fun release() {
        members.toList().forEach(::remove)
        listOf(level0, level1, level2, slotLevel, output).forEach(graphics::releaseGraphicsLayer)
        members.clear(); cells.clear(); rows.clear(); cellsHeight = 0
    }

    companion object {
        const val Width = 2048
        const val MaxHeight = 4096
        const val MinCell = 64
        const val MaxMembers = 32
        const val Columns = 8
    }
}
