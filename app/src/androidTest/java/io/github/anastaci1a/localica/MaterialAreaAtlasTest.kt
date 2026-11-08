package io.github.anastaci1a.localica

import android.graphics.Bitmap
import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsContext
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.CompositingStrategy
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalGraphicsContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.ui.RetainedLayerReadback
import io.github.anastaci1a.localica.ui.materialReductionExtent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.ceil

/** Independent #691 atlas hypothesis. No app consumer, source or shader is replaced. */
class MaterialAreaAtlasTest {
    @get:Rule val compose = localicaComposeRule()
    private data class Request(val label: String, val width: Float, val height: Float,
        val phase: Int = 0, val x: Float = 3.25f, val y: Float = 5.75f,
        val memberB: Boolean = true, val retainB: Boolean = false, val dropHeld: Boolean = false)
    private class State {
        var parents = 0
        var epoch: Atlas? = null
        var heldEpoch: Atlas? = null
        var disposedEpoch: Atlas? = null
        var heldInputB: GraphicsLayer? = null
        var heldRequestB: Request? = null
        var heldDownstreamRecordsB = 0
        var heldAtlasRecords = 0
        var frame: Frame? = null
    }
    private data class Frame(val a: List<GraphicsLayer>, val b: List<GraphicsLayer>,
        val atlas: Atlas, val extractedA: GraphicsLayer, val extractedB: GraphicsLayer,
        val heldB: GraphicsLayer, val parents: Int, val bInputRecords: Int, val bDownstreamRecords: Int,
        val atlasInputA: GraphicsLayer, val atlasInputB: GraphicsLayer?,
        val oracleA: GraphicsLayer, val oracleB: GraphicsLayer?, val aDownstreamRecords: Int)

    @Test fun nativeAtlasMatchesEveryOriginalStage() = runProof(live = false)
    @Test fun completeLiveEndpointsTrackChildEpochWithoutDownstreamRecording() = runProof(live = true)

    private fun runProof(live: Boolean) {
        val requests = if (live) listOf(
            Request("live-initial", 17f, 13f),
            Request("live-child-only", 17f, 13f, phase = 1),
            Request("live-held-member", 17f, 13f, phase = 2, retainB = true),
            Request("live-released-member", 17f, 13f, phase = 3, memberB = false),
            Request("live-other-member-resizes", 33f, 21f, phase = 4, memberB = false),
            Request("live-last-lease-disposal", 33f, 21f, phase = 5, memberB = false, dropHeld = true)
        ) else listOf(
            Request("fractional-odd", 17f, 13f),
            Request("child-only", 17f, 13f, phase = 1),
            Request("alpha-odd-larger", 33f, 21f, phase = 2, x = 2.5f, y = 4.25f),
            Request("fractional-edge", 9.25f, 5.5f, phase = 3, x = 7.75f, y = 9.5f),
            Request("horizontal-one-axis", 17f, 1f, phase = 4),
            Request("vertical-one-axis", 1f, 17f, phase = 5),
            Request("one-texel-fallback", 1f, 1f, phase = 6)

        )
        val request = mutableStateOf(requests.first())
        val state = State()
        lateinit var scope: CoroutineScope
        lateinit var graphics: GraphicsContext
        lateinit var density: Density
        lateinit var direction: androidx.compose.ui.unit.LayoutDirection
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                scope = rememberCoroutineScope()
                graphics = LocalGraphicsContext.current
                density = LocalDensity.current
                direction = LocalLayoutDirection.current
                val a = remember(graphics) { Original(graphics) }
                val b = remember(graphics) { Original(graphics) }
                val inputA = remember(graphics) { Original(graphics) }
                val inputB = remember(graphics) { Original(graphics) }
                val childA = rememberGraphicsLayer()
                val childB = rememberGraphicsLayer()
                val source = rememberGraphicsLayer().apply { clip = true; compositingStrategy = CompositingStrategy.Offscreen }
                val oracleA = remember(graphics) { Original(graphics) }
                val oracleB = remember(graphics) { Original(graphics) }
                val extractedA = rememberGraphicsLayer().apply { clip = true; compositingStrategy = CompositingStrategy.Offscreen }
                val extractedB = rememberGraphicsLayer().apply { clip = true; compositingStrategy = CompositingStrategy.Offscreen }
                val heldB = rememberGraphicsLayer().apply { clip = true; compositingStrategy = CompositingStrategy.Offscreen }
                DisposableEffect(graphics) {
                    onDispose { state.epoch?.release(); state.heldEpoch?.release(); a.release(); b.release(); inputA.release(); inputB.release(); oracleA.release(); oracleB.release() }
                }
                Canvas(Modifier.size(300.dp, 200.dp)) {
                    val input = request.value
                    if (input.dropHeld && state.heldEpoch != null) {
                        // End the held output lease before releasing its producer graph.
                        heldB.record(size = IntSize(1, 1)) {}
                        extractedB.record(size = IntSize(1, 1)) {}
                        state.disposedEpoch = state.heldEpoch
                        state.heldEpoch?.release()
                        state.heldEpoch = null
                        b.release()
                        inputB.release()
                    }
                    fun DrawScope.paint(isB: Boolean) {
                        val even = input.phase % 2 == 0
                        drawRect(if (isB) if (even) Color(0x8040E010) else Color(0xC0E020F0)
                            else if (even) Color(0x805B193D) else Color(0xA0197D35))
                        for (x in 0 until 63) if ((x + input.phase) % (if (isB) 5 else 3) == 0)
                            drawRect(if (isB) Color(0xE01020F0) else Color(0xC0F3D107),
                                Offset(x.toFloat(), 0f), Size(1f, 47f))
                        drawRect(if (isB) Color(0x4050F020) else Color(0x407030E0),
                            Offset(9.5f, 11.25f), Size(8.75f, 19.5f))
                        drawRect(if (isB) if (even) Color.Green else Color.Yellow
                            else if (even) Color.Red else Color.Blue,
                            if (isB) Offset(8f, 8f) else Offset.Zero, Size(10f, 10f))
                    }
                    // Opaque epoch markers provide an independent freshness assertion for each L0.
                    // They are painted last; fractional top-left crop samples lie wholly inside.
                    childA.record(size = IntSize(63, 47)) { paint(false) }
                    childB.record(size = IntSize(63, 47)) { paint(true) }
                    if (state.parents == 0) {
                        // One common producer, unchanged after this first recording, fans out
                        // to all old/candidate/oracle independent crops just as production does.
                        source.record(size = IntSize(128, 64)) {
                            drawLayer(childA)
                            translate(64f, 0f) { drawLayer(childB) }
                        }
                        state.parents++
                    }
                    val extent = IntSize(ceil(input.width).toInt(), ceil(input.height).toInt())
                    val padded = materialReductionExtent(extent)
                    fun DrawScope.crop(parent: GraphicsLayer, offset: Offset) {
                        clipRect(right = input.width, bottom = input.height) {
                            translate(-offset.x, -offset.y) { drawLayer(parent) }
                        }
                    }
                    val oldA = with(a) { record(extent, reuse = live) { crop(source, Offset(input.x, input.y)) } }
                    val cropA = with(inputA) { recordInput(extent) { crop(source, Offset(input.x, input.y)) } }
                    var cropB: GraphicsLayer? = null
                    val oldB = if (input.memberB) with(b) {
                        cropB = with(inputB) { recordInput(extent) { crop(source, Offset(input.x + 71.5f, input.y + 3.25f)) } }
                        record(extent, reuse = live) { crop(source, Offset(input.x + 71.5f, input.y + 3.25f)) }
                    } else {
                        // Consumer removal does not end the retained output's producer lease.
                        // The held face still prepares its exact original crop, just as a normal
                        // visible consumer does. Its downstream native links are not rerecorded.
                        if (!input.dropHeld) {
                            val held = requireNotNull(state.heldRequestB)
                            with(b) { recordInput(IntSize(ceil(held.width).toInt(), ceil(held.height).toInt())) {
                                clipRect(right = held.width, bottom = held.height) {
                                    translate(-held.x - 71.5f, -held.y - 3.25f) { drawLayer(source) }
                                }
                            } }
                            cropB = with(inputB) { recordInput(IntSize(ceil(held.width).toInt(), ceil(held.height).toInt())) {
                                clipRect(right = held.width, bottom = held.height) {
                                    translate(-held.x - 71.5f, -held.y - 3.25f) { drawLayer(source) }
                                }
                            } }
                        }
                        b.stages
                    }
                    val previous = state.epoch
                    val epoch = if (previous == null || previous.input != padded || previous.memberB != input.memberB) {
                        Atlas(graphics, padded, input.memberB).also {
                            previous?.takeUnless { it === state.heldEpoch }?.release()
                            state.epoch = it
                        }
                    } else previous
                    if (!live || epoch.records == 0) with(epoch) { record(cropA, cropB?.takeIf { input.memberB }) }
                    val freshA = with(oracleA) { record(extent) { crop(source, Offset(input.x, input.y)) } }.last()
                    val freshB = if (input.dropHeld) null else {
                        val footprint = if (input.memberB) input else requireNotNull(state.heldRequestB)
                        with(oracleB) { record(IntSize(ceil(footprint.width).toInt(), ceil(footprint.height).toInt())) {
                            clipRect(right = footprint.width, bottom = footprint.height) {
                                translate(-footprint.x - 71.5f, -footprint.y - 3.25f) { drawLayer(source) }
                            }
                        } }.last()
                    }
                    fun DrawScope.extract(output: GraphicsLayer, memberB: Boolean) {
                        output.record(size = IntSize(1, 1)) {
                            if (epoch.levels.isEmpty()) drawLayer(if (memberB) oldB.last() else oldA.last())
                            else translate(if (memberB) -2f else 0f, 0f) { drawLayer(epoch.levels.last().layer) }
                        }
                    }
                    extract(extractedA, false)
                    if (input.memberB) extract(extractedB, true)
                    if (input.retainB) {
                        heldB.record(size = IntSize(1, 1)) { drawLayer(extractedB) }
                        state.heldEpoch = epoch
                        state.heldInputB = cropB
                        state.heldRequestB = input
                        state.heldDownstreamRecordsB = b.downstreamRecords
                        state.heldAtlasRecords = epoch.records
                    }
                    // Present complete endpoints only. Extra intermediate-root damage is a
                    // possible confound, not a demonstrated explanation of the earlier26 failures.
                    translate(0f, 0f) { scale(32f, 32f, Offset.Zero) { drawLayer(oldA.last()) } }
                    oldB.lastOrNull()?.let { translate(40f, 0f) { scale(32f, 32f, Offset.Zero) { drawLayer(it) } } }
                    translate(180f, 0f) { scale(32f, 32f, Offset.Zero) { drawLayer(extractedA) } }
                    translate(220f, 0f) { scale(32f, 32f, Offset.Zero) {
                        drawLayer(if (input.memberB) extractedB else heldB)
                    } }
                    translate(0f, 80f) { scale(32f, 32f, Offset.Zero) { drawLayer(freshA) } }
                    freshB?.let { translate(40f, 80f) { scale(32f, 32f, Offset.Zero) { drawLayer(it) } } }
                    state.frame = Frame(oldA, oldB, epoch, extractedA, extractedB, heldB,
                        state.parents, b.inputRecords, b.downstreamRecords, cropA, cropB, freshA, freshB, a.downstreamRecords)
                }
            }
        }
        val output = File(requireNotNull(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null)),
            "atlas-${if (live) "live" else "arithmetic"}-api${Build.VERSION.SDK_INT}").also { check(it.mkdirs() || it.isDirectory) }
        val readback = RetainedLayerReadback()
        val receipts = mutableListOf<String>()
        var heldBefore: ByteArray? = null
        var previousCounts: Pair<Int, Int>? = null
        var previousExtent: Pair<Float, Float>? = null
        for (input in requests) {
            compose.runOnIdle { request.value = input }
            compose.waitForIdle()
            // Admit the native producer frame before comparing snapshots. Old/old
            // comparisons still reject any missing or unstable subsequent readback.
            compose.onRoot().captureToImage()
            val frame = compose.runOnUiThread { requireNotNull(state.frame) }
            val done = AtomicBoolean(false)
            var failure: Throwable? = null
            compose.runOnIdle {
                scope.launch {
                    try {
                        suspend fun copy(layer: GraphicsLayer, name: String): Raw {
                            val image = readback.capture(layer, graphics, density, direction).asAndroidBitmap()
                            val bitmap = requireNotNull(image.copy(Bitmap.Config.ARGB_8888, false))
                            val data = ByteBuffer.allocate(bitmap.byteCount)
                            bitmap.copyPixelsToBuffer(data)
                            File(output, "${input.label}-$name.png").outputStream().use {
                                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                            }
                            return Raw(bitmap.width, bitmap.height, bitmap.rowBytes, data.array(),
                                bitmap.isPremultiplied, bitmap.colorSpace?.name.orEmpty(), bitmap.getPixel(0, 0))
                        }
                        // LIVE ORDER: endpoint snapshots first (old, old-control, atlas,
                        // fresh oracle); only then each raw L0. No intermediate stage readback.
                        // ARITHMETIC ORDER: old/control/candidate L0, then each half stage,
                        // finally extraction. Its graph is separate from the live test.
                        if (live) {
                            assertEquals("zero downstream atlas rerecords within an epoch", 1, frame.atlas.records)
                            val oldA = copy(frame.a.last(), "live-old-a")
                            val oldAControl = copy(frame.a.last(), "live-old-a-control")
                            val atlasA = copy(frame.extractedA, "live-atlas-a")
                            val freshA = copy(frame.oracleA, "live-oracle-a")
                            val oldB = frame.b.lastOrNull()?.let { copy(it, "live-old-b") }
                            val atlasB = if (input.dropHeld) null else copy(
                                if (input.memberB) frame.extractedB else frame.heldB, "live-atlas-b")
                            val freshB = frame.oracleB?.let { copy(it, "live-oracle-b") }
                            val l0a = copy(frame.a.first(), "live-l0-old-a")
                            val l0ca = copy(frame.atlasInputA, "live-l0-atlas-a")
                            val l0b = frame.b.firstOrNull()?.let { copy(it, "live-l0-old-b") }
                            val l0cb = frame.atlasInputB?.let { copy(it, "live-l0-atlas-b") }
                            val even = input.phase % 2 == 0
                            val expectedA = if (even) android.graphics.Color.RED else android.graphics.Color.BLUE
                            val expectedB = if (even) android.graphics.Color.GREEN else android.graphics.Color.YELLOW
                            receipts += "${input.label} ENDPOINTS BEFORE L0; epoch=${input.phase}; " +
                                "L0 oldA=${l0a.firstPixel.toUInt().toString(16)} candidateA=${l0ca.firstPixel.toUInt().toString(16)} " +
                                "oldB=${l0b?.firstPixel?.toUInt()?.toString(16)} candidateB=${l0cb?.firstPixel?.toUInt()?.toString(16)}"
                            assertEquals("old A L0 expected NEW producer epoch", expectedA, l0a.firstPixel)
                            assertEquals("atlas A L0 expected NEW producer epoch", expectedA, l0ca.firstPixel)
                            if (!input.dropHeld) {
                                assertEquals("old B L0 expected NEW producer epoch", expectedB, requireNotNull(l0b).firstPixel)
                                assertEquals("atlas B L0 expected NEW producer epoch", expectedB, requireNotNull(l0cb).firstPixel)
                            }
                            assertRaw("${input.label} old/old endpoint", oldA, oldAControl, 0, receipts)
                            assertRaw("${input.label} old A fresh epoch oracle", freshA, oldA, 0, receipts)
                            assertRaw("${input.label} atlas A fresh epoch oracle", freshA, atlasA, 0, receipts)
                            if (!input.dropHeld) {
                                assertRaw("${input.label} old B fresh epoch oracle", requireNotNull(freshB), requireNotNull(oldB), 0, receipts)
                                assertRaw("${input.label} atlas B fresh epoch oracle", freshB, requireNotNull(atlasB), 0, receipts)
                            }
                            if (previousExtent == (input.width to input.height)) {
                                assertEquals("zero old A downstream rerecords", requireNotNull(previousCounts).first, frame.aDownstreamRecords)
                                if (!input.dropHeld) assertEquals("zero old B downstream rerecords", previousCounts!!.second, frame.bDownstreamRecords)
                            }
                            previousCounts = frame.aDownstreamRecords to frame.bDownstreamRecords
                            previousExtent = input.width to input.height
                        }
                        if (!live) for (index in frame.a.indices) {
                            val oldA = copy(frame.a[index], "old-a-$index")
                            val oldAAgain = copy(frame.a[index], "old-a-control-$index")
                            assertRaw("${input.label} old/old A stage$index", oldA, oldAAgain, 0, receipts)
                            val oldB = if (input.memberB) copy(frame.b[index], "old-b-$index") else null
                            if (oldB != null) assertRaw("${input.label} old/old B stage$index", oldB,
                                copy(frame.b[index], "old-b-control-$index"), 0, receipts)
                            if (index > 0) {
                                val tile = frame.atlas.levels[index - 1]
                                val atlas = copy(tile.layer, "atlas-$index")
                                assertRaw("${input.label} old/atlas A stage$index", oldA, atlas, 0, receipts)
                                if (oldB != null) assertRaw("${input.label} old/atlas B stage$index", oldB,
                                    atlas, tile.size.width + 1, receipts)
                                assertGutter(atlas, tile.size.width, input.memberB)
                            } else {
                                assertRaw("${input.label} independent crop A", oldA, copy(frame.atlasInputA, "atlas-input-a"), 0, receipts)
                                if (oldB != null) assertRaw("${input.label} independent crop B", oldB,
                                    copy(requireNotNull(frame.atlasInputB), "atlas-input-b"), 0, receipts)
                            }
                        }
                        val finalA = copy(frame.extractedA, "extracted-a")
                        assertRaw("${input.label} extracted A", copy(frame.a.last(), "final-old-a"), finalA, 0, receipts)
                        if (input.memberB) {
                            val finalB = copy(frame.extractedB, "extracted-b")
                            assertRaw("${input.label} extracted B", copy(frame.b.last(), "final-old-b"), finalB, 0, receipts)
                            if (input.retainB) heldBefore = finalB.bytes.copyOf()
                        } else if (!input.dropHeld) {
                            assertFalse("A held output owns a live producer lease", requireNotNull(state.heldInputB).isReleased)
                            assertEquals("Held update does not rerecord any old B downstream level",
                                state.heldDownstreamRecordsB, frame.bDownstreamRecords)
                            assertEquals("Held update does not rerecord the retired atlas graph",
                                state.heldAtlasRecords, requireNotNull(state.heldEpoch).records)
                            val held = copy(frame.heldB, "released-held-b")
                            assertRaw("${input.label} released B live child", copy(frame.b.last(), "released-old-b"), held, 0, receipts)
                            assertFalse("A held member must update, not freeze its pre-release raster",
                                requireNotNull(heldBefore).contentEquals(held.bytes))
                            heldBefore = held.bytes.copyOf()
                        } else {
                            assertTrue("Last held lease releases the old input", requireNotNull(state.heldInputB).isReleased)
                            assertTrue("Last held lease disposes every retired atlas node",
                                requireNotNull(state.disposedEpoch).levels.all { it.layer.isReleased })
                        }
                        assertEquals("Parent counters remain unchanged", 1, frame.parents)
                        receipts += "${input.label} PASS stages=${frame.a.size} oldDownstreamTargets=${(frame.a.size - 1) * 2} atlasTargets=${frame.atlas.levels.size} activeB=${input.memberB} BcropRecords=${frame.bInputRecords} BdownstreamRecords=${frame.bDownstreamRecords}"
                    } catch (error: Throwable) { failure = error }
                    finally {
                        File(output, "comparison.txt").writeText(receipts.joinToString("\n", postfix = "\n") +
                            (failure?.let { "REJECTED ${input.label}: $it\n" } ?: ""))
                        done.set(true)
                    }
                }
            }
            compose.waitUntil(60_000) { done.get() }
            failure?.let { throw it }
        }
    }

    private data class Raw(val width: Int, val height: Int, val stride: Int,
        val bytes: ByteArray, val premultiplied: Boolean, val colorSpace: String, val firstPixel: Int)
    private fun assertRaw(label: String, old: Raw, actual: Raw, left: Int, receipts: MutableList<String>) {
        assertEquals("$label colour space", old.colorSpace, actual.colorSpace)
        assertEquals("$label alpha convention", old.premultiplied, actual.premultiplied)
        assertEquals("$label height", old.height, actual.height)
        var differences = 0
        for (y in 0 until old.height) for (x in 0 until old.width) for (channel in 0..3)
            if (old.bytes[y * old.stride + x * 4 + channel] != actual.bytes[y * actual.stride + (left + x) * 4 + channel]) differences++
        receipts += "$label differingBytes=$differences format=ARGB8888 premul=${old.premultiplied} colour=${old.colorSpace}"
        assertEquals(label, 0, differences)
    }
    private fun assertGutter(atlas: Raw, width: Int, memberB: Boolean) {
        for (y in 0 until atlas.height) for (x in 0 until atlas.width) {
            if (x < width || memberB && x in width + 1..width * 2) continue
            for (channel in 0..3) assertEquals("Transparent gutter at$x,$y channel$channel", 0,
                atlas.bytes[y * atlas.stride + x * 4 + channel].toInt())
        }
    }
    private class Original(private val graphics: GraphicsContext) {
        private val owned = mutableListOf<GraphicsLayer>()
        var inputRecords = 0; private set
        var downstreamRecords = 0; private set
        var stages: List<GraphicsLayer> = emptyList(); private set
        private var recordedExtent: IntSize? = null
        private fun level(index: Int): GraphicsLayer {
            while (owned.size <= index) owned += graphics.createGraphicsLayer().apply { compositingStrategy = CompositingStrategy.Offscreen; clip = true }
            return owned[index]
        }
        fun DrawScope.record(extent: IntSize, reuse: Boolean = false, draw: DrawScope.() -> Unit): List<GraphicsLayer> {
            var dimensions = materialReductionExtent(extent)
            var previous = recordInput(extent, draw)
            if (reuse && recordedExtent == extent && stages.isNotEmpty()) return stages
            recordedExtent = null
            var index = 1
            while (dimensions.width > 1 || dimensions.height > 1) {
                val next = IntSize((dimensions.width / 2).coerceAtLeast(1), (dimensions.height / 2).coerceAtLeast(1))
                val input = previous; val before = dimensions
                previous = level(index++)
                downstreamRecords++
                previous.record(size = next) {
                    scale(next.width.toFloat() / before.width, next.height.toFloat() / before.height, Offset.Zero) { drawLayer(input) }
                }
                dimensions = next
            }
            stages = owned.take(index)
            recordedExtent = extent
            return stages
        }
        fun DrawScope.recordInput(extent: IntSize, draw: DrawScope.() -> Unit): GraphicsLayer = level(0).also {
            inputRecords++
            it.record(Density(density, fontScale), layoutDirection, materialReductionExtent(extent), draw)
        }
        fun release() { owned.filterNot { it.isReleased }.forEach(graphics::releaseGraphicsLayer); owned.clear(); stages = emptyList(); recordedExtent = null }
    }
    private class Atlas(private val graphics: GraphicsContext, val input: IntSize, val memberB: Boolean) {
        data class Tile(val layer: GraphicsLayer, val size: IntSize)
        val levels = mutableListOf<Tile>()
        var records = 0; private set
        fun DrawScope.record(a: GraphicsLayer, b: GraphicsLayer?) {
            records++
            var previous: Tile? = null
            var before = input
            var index = 0
            while (before.width > 1 || before.height > 1) {
                val next = IntSize((before.width / 2).coerceAtLeast(1), (before.height / 2).coerceAtLeast(1))
                if (levels.size <= index) levels += Tile(graphics.createGraphicsLayer().apply {
                    compositingStrategy = CompositingStrategy.Offscreen; clip = true
                }, next)
                val output = levels[index++]
                val prior = previous
                val priorSize = before
                output.layer.record(size = IntSize(4 * next.width, next.height)) {
                    fun DrawScope.tile(source: GraphicsLayer, memberB: Boolean) {
                        translate(if (memberB) next.width + 1f else 0f, 0f) {
                            clipRect(right = next.width.toFloat(), bottom = next.height.toFloat()) {
                                scale(next.width.toFloat() / priorSize.width, next.height.toFloat() / priorSize.height, Offset.Zero) {
                                    translate(if (memberB && prior != null) -(priorSize.width + 1f) else 0f, 0f) { drawLayer(source) }
                                }
                            }
                        }
                    }
                    tile(prior?.layer ?: a, false)
                    if (b != null) tile(prior?.layer ?: b, true)
                }
                previous = output; before = next
            }
        }
        fun release() { levels.filterNot { it.layer.isReleased }.forEach { graphics.releaseGraphicsLayer(it.layer) } }
    }
}
