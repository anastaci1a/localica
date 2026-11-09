package io.github.anastaci1a.localica.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.vector.VectorGroup
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.ui.AppIcon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import java.util.zip.ZipFile
import java.security.MessageDigest
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.abs

/** #724 measurement, raster rule and per-glyph tables for the built-in pack (and, locally, a private pack). */
class OpticalCentringPacksTest {
    private fun vector(body: String) = SvgIconParser.parse(
        """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24">$body</svg>""".toByteArray()).toImageVector()

    @Test fun inkMeasurementMatchesExactPolygonGeometry() {
        val empty = ImageVector.Builder(defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).build()
        val m = requireNotNull(vector("""<path d="M12 4 L20 20 L4 20 Z"/>""").inkMetrics())
        assertEquals("ink height", 16f, m.bottom - m.top, .1f)
        assertEquals("ink offset is translation invariant", 8f / 3f, m.inkOffsetY, .03f)
        assertTrue(empty.inkMetrics() == null)
    }

    @Test fun deadbandAndCanvasEdgeBoundTheMove() {
        assertEquals(0f, opticalShift(InkMetrics(4f, 20f, 12.09f), 24f), 0f)
        assertEquals(-.06f, opticalShift(InkMetrics(4f, 20f, 12.12f), 24f), 1e-5f)
        // A bottom-heavy glyph touching the top edge cannot rise; one with 0.5 units of room rises only that far.
        assertEquals(0f, opticalShift(InkMetrics(0f, 20f, 14f), 24f), 0f)
        assertEquals(-.5f, opticalShift(InkMetrics(.5f, 20f, 14f), 24f), 0f)
        assertEquals(.5f, opticalShift(InkMetrics(4f, 23.5f, 9f), 24f), 0f)
    }

    @Test fun symmetricVectorsAreReturnedUntouched() {
        val builder = ImageVector.Builder(defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f)
        builder.addPath(PathParser().parsePathString("M6 6 L18 18 M18 6 L6 18").toNodes(),
            stroke = SolidColor(Color.Black), strokeLineWidth = 2.2f,
            strokeLineCap = StrokeCap.Round)
        val cross = builder.build()
        assertSame(cross, cross.opticallyCentred())
    }

    @Test fun genericVectorRuleMatchesTheImportPipeline() {
        val tray = """<path d="M12 4 V15 M7 11 L12 15 L17 11 M4 15 V19 A2 2 0 0 0 6 21 H18 A2 2 0 0 0 20 19 V15" fill="none" stroke="black" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"/>"""
        val imported = vector(tray)
        val shift = (imported.root.single() as VectorGroup).translationY
        assertTrue("a tray with a down arrow is bottom-heavy and rises: $shift", shift < -.5f)
        val after = requireNotNull(imported.inkMetrics())
        assertEquals("the ink offset itself is unchanged by a translation", -2f * shift, after.inkOffsetY, .03f)
        // Round caps put the ink at 3.1..21.9: box centre 12.5 before the move.
        assertEquals("box centre moved by the shift", 12.5f + shift, after.boxCentreY, .06f)
    }

    @Test fun rasterMasksFollowTheSameRule() {
        val size = 96
        // Base-down triangle mask: rows 16..79, width growing downward.
        val alpha = IntArray(size * size)
        for (y in 16 until 80) { val half = (y - 16) / 2 + 1; for (x in 48 - half until 48 + half) alpha[y * size + x] = 255 }
        val m = requireNotNull(alphaInkMetrics(alpha, size, size))
        assertEquals(16f, m.top, 0f); assertEquals(80f, m.bottom, 0f)
        assertTrue(m.inkOffsetY > 10f)
        assertTrue(opticalShift(m, size.toFloat()) < -5f)
        val symmetric = IntArray(size * size).also { for (y in 30 until 66) for (x in 20 until 76) it[y * size + x] = 200 }
        assertEquals(0f, opticalShift(requireNotNull(alphaInkMetrics(symmetric, size, size)), size.toFloat()), 0f)
        assertTrue(alphaInkMetrics(IntArray(size * size), size, size) == null)
    }

    // ---- Built-in pack ---------------------------------------------------------------------------

    /** The built-in vector XML as the template export writes it, read from source without Android resources. */
    private fun builtinSvg(file: File): String {
        val document = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(file)
        val ns = "http://schemas.android.com/apk/res/android"
        fun Element.a(name: String) = getAttributeNS(ns, name).takeIf { it.isNotEmpty() }
        fun paint(color: String?): Pair<String, Float> {
            if (color == null) return "none" to 0f
            val hex = color.removePrefix("#")
            val alpha = if (hex.length == 8) hex.take(2).toInt(16) / 255f else 1f
            return (if (alpha == 0f) "none" else "black") to alpha
        }
        val out = StringBuilder()
        fun walk(e: Element) {
            when (e.localName) {
                "vector" -> out.append("""<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${e.a("viewportWidth")} ${e.a("viewportHeight")}">""")
                "group" -> {
                    val px = e.a("pivotX") ?: "0"; val py = e.a("pivotY") ?: "0"
                    out.append("""<g transform="translate(${(e.a("translateX") ?: "0").toFloat() + px.toFloat()} ${(e.a("translateY") ?: "0").toFloat() + py.toFloat()}) rotate(${e.a("rotation") ?: "0"}) scale(${e.a("scaleX") ?: "1"} ${e.a("scaleY") ?: "1"}) translate(-$px -$py)">""")
                }
                "path" -> {
                    val (fill, fillAlpha) = paint(e.a("fillColor")); val (stroke, strokeAlpha) = paint(e.a("strokeColor"))
                    out.append("""<path d="${e.a("pathData")}" fill="$fill" fill-opacity="${if (fill == "none") 1f else fillAlpha * (e.a("fillAlpha")?.toFloat() ?: 1f)}" stroke="$stroke" """)
                    out.append("""stroke-opacity="${if (stroke == "none") 1f else strokeAlpha}" stroke-width="${e.a("strokeWidth") ?: "0"}" stroke-linecap="${e.a("strokeLineCap") ?: "butt"}" stroke-linejoin="${e.a("strokeLineJoin") ?: "miter"}" fill-rule="${if (e.a("fillType") == "evenOdd") "evenodd" else "nonzero"}"/>""")
                }
            }
            var child = e.firstChild
            while (child != null) { if (child is Element) walk(child); child = child.nextSibling }
            when (e.localName) { "vector" -> out.append("</svg>"); "group" -> out.append("</g>") }
        }
        walk(document.documentElement)
        return out.toString()
    }

    private val resources = File("src/main/res/drawable").takeIf { it.isDirectory } ?: File("app/src/main/res/drawable")
    private val drawableNames = File(resources.parentFile.parentFile, "java/io/github/anastaci1a/localica/ui/AppIcons.kt").readText()
        .let { text -> Regex("""\("([a-z_.0-9]+)", R\.drawable\.([a-z_0-9]+)\)""").findAll(text).associate { it.groupValues[1] to it.groupValues[2] } }

    private class Row(val id: String, val before: InkMetrics, val shift: Float)

    /** Measure the original geometry directly: resampling a translated vector introduces grid-phase error. */
    private fun measure(id: String, svg: ByteArray): Row {
        val icon = SvgIconParser.parse(svg)
        require(icon.viewBox == listOf(0f, 0f, 24f, 24f)) { "$id: tables assume a 24-unit viewBox" }
        val processed = icon.toImageVector()
        val shift = (processed.root.single() as VectorGroup).translationY
        val before = requireNotNull(icon.toImageVector(shiftY = 0f).inkMetrics()) { "$id has no ink" }
        return Row(id, before, shift)
    }

    private fun table(title: String, rows: List<Row>): String = buildString {
        appendLine("# $title")
        appendLine()
        appendLine("Values in dp of the 24 dp icon cell; positive Y is below the cell centre (12). Ink offset = ink centre − cell centre. Rule: move = −${OPTICAL_CENTRING} × (ink centre − box centre), dead band ${"%.2f".format(OPTICAL_DEADBAND * 24f)} dp, never past the cell edge.")
        appendLine()
        appendLine("| Glyph | Ink height | Box centre Y before | Ink offset before | Move | Box centre Y after | Ink offset after |")
        appendLine("| --- | ---: | ---: | ---: | ---: | ---: | ---: |")
        for (r in rows) {
            val b = r.before
            appendLine("| ${r.id} | ${"%.3f".format(b.bottom - b.top)} | ${"%+.3f".format(b.boxCentreY - 12f)} | ${"%+.3f".format(b.centroidY - 12f)} | " +
                "${"%+.3f".format(r.shift)} | ${"%+.3f".format(b.boxCentreY + r.shift - 12f)} | ${"%+.3f".format(b.centroidY + r.shift - 12f)} |")
        }
        appendLine()
        appendLine("Moved: ${rows.count { it.shift != 0f }} of ${rows.size}; unchanged: ${rows.filter { it.shift == 0f }.joinToString { it.id }}")
    }

    private fun report(name: String, text: String) {
        System.getenv("LOCALICA_ICON_OPTICAL_REPORT")?.takeIf { it.isNotBlank() }?.let { File(it).apply { mkdirs() }.resolve(name).writeText(text) }
    }

    @Test fun builtinPackFollowsTheRuleAndSymmetricGlyphsStay() {
        val rows = drawableNames.map { (id, drawable) -> measure(id, builtinSvg(File(resources, "$drawable.xml")).toByteArray()) }
        assertEquals(AppIcon.entries.size, rows.size)
        val byId = rows.associateBy { it.id }
        for (id in listOf("action.close", "action.plus", "action.ellipsis", "action.drag", "transport.play", "transport.pause"))
            assertEquals("$id is symmetric and stays", 0f, byId.getValue(id).shift, 0f)
        // The tray with a down arrow is measured natively (#701, 64 px/dp alpha) 2.895 dp low; this geometry agrees.
        val save = byId.getValue("action.save")
        assertEquals(2.895f, save.before.centroidY - 12f, .06f)
        assertTrue("Save rises by about half its offset from its box centre", save.shift < -1f)
        rows.forEach { r ->
            assertEquals(r.id, if (abs(r.before.inkOffsetY) * OPTICAL_CENTRING < OPTICAL_DEADBAND * 24f) 0f else (-OPTICAL_CENTRING * r.before.inkOffsetY).coerceIn(-r.before.top.coerceAtLeast(0f), (24f - r.before.bottom).coerceAtLeast(0f)),
                r.shift, .01f)
        }
        report("builtin-offsets.md", table("Built-in pack optical centring", rows))
        report("builtin-offsets.tsv", rows.joinToString("\n") { "${it.id}\t${it.shift}" } + "\n")
        report("BuiltinIconOffsets.kt", buildString {
            appendLine("package io.github.anastaci1a.localica.icons")
            appendLine()
            appendLine("/** Generated from built-in vector resources by OpticalCentringPacksTest; runtime never measures these glyphs. */")
            appendLine("internal object BuiltinIconOffsets {")
            appendLine("    const val SOURCE_SHA256 = \"" + builtinSourceHash() + "\"")
            appendLine("    val vertical: Map<String, Float> = mapOf(")
            rows.forEach { appendLine("        \"${it.id}\" to "+it.shift+"f,") }
            appendLine("    )")
            appendLine("}")
        })
    }

    @Test fun seekBackAndForwardNeverCrossTheirAnalyticRoundCapBounds() {
        for (id in listOf("action.seek_back", "action.seek_forward")) {
            val original = SvgIconParser.parse(builtinSvg(File(resources, drawableNames.getValue(id) + ".xml")).toByteArray()).toImageVector(shiftY = 0f)
            val bounds = requireNotNull(original.geometricInkBounds())
            assertEquals("$id exact cap clearance", .172f, bounds.first, .000002f)
            val move = original.opticalShiftY()
            assertTrue("$id crosses the top edge: top=" + bounds.first + ", move=" + move, bounds.first + move >= 0f)
            assertTrue("$id crosses the bottom edge", bounds.second + move <= 24f)
        }
    }

    @Test fun everyBuiltinStaysInsideItsCellAfterTheGeometricClamp() {
        for ((id, drawable) in drawableNames) {
            val original = SvgIconParser.parse(builtinSvg(File(resources, "$drawable.xml")).toByteArray()).toImageVector(shiftY = 0f)
            val bounds = requireNotNull(original.geometricInkBounds())
            val move = original.opticalShiftY()
            assertTrue("$id crosses the top edge: " + (bounds.first + move), bounds.first + move >= 0f)
            assertTrue("$id crosses the bottom edge: " + (bounds.second + move), bounds.second + move <= 24f)
        }
    }

    private val sourceRoot = resources.parentFile.parentFile
    private val ruleFiles = listOf("OpticalCentring.kt", "VectorGeometry.kt", "SvgIconVector.kt", "VectorInkBounds.kt")

    private fun builtinSourceHash(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        fun input(name: String, bytes: ByteArray) {
            digest.update((name + '\u0000').toByteArray()); digest.update(bytes); digest.update(0.toByte())
        }
        for ((id, drawable) in drawableNames) input("$id:drawable/$drawable.xml", File(resources, "$drawable.xml").readBytes())
        for (name in ruleFiles) input(name, File(sourceRoot, "java/io/github/anastaci1a/localica/icons/$name").readBytes())
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    @Test fun generatedBuiltinOffsetsMatchEveryResourceExactly() {
        assertTrue("The static table must contain corrections", BuiltinIconOffsets.vertical.values.any { it != 0f })
        assertEquals("Every registry id must be explicit, including zero offsets", drawableNames.keys, BuiltinIconOffsets.vertical.keys)
        val variants = resources.parentFile.listFiles().orEmpty().filter { it.isDirectory && it.name.startsWith("drawable") }
            .flatMap { dir -> dir.listFiles().orEmpty().filter { it.nameWithoutExtension in drawableNames.values }
                .map { "${dir.name}/${it.name}" } }.toSet()
        assertEquals("Qualified built-in variants require an explicit optical table selection policy",
            drawableNames.values.map { "drawable/$it.xml" }.toSet(), variants)
        for ((id, drawable) in drawableNames) {
            val measured = measure(id, builtinSvg(File(resources, "$drawable.xml")).toByteArray()).shift
            assertEquals("$id static optical offset drifted", measured, BuiltinIconOffsets.vertical.getValue(id), 0f)
        }
        assertEquals("Mapping, XML inventory or optical rule input drifted", BuiltinIconOffsets.SOURCE_SHA256, builtinSourceHash())
    }

    /** Local only: LOCALICA_PRIVATE_PACK names a pack ZIP outside the repository; its table stays with it. */
    @Test fun privatePackTable() {
        val path = System.getenv("LOCALICA_PRIVATE_PACK")?.takeIf { it.isNotBlank() }
        assumeTrue("LOCALICA_PRIVATE_PACK is not set", path != null)
        val rows = ZipFile(path!!).use { zip ->
            zip.entries().toList().filter { it.name.startsWith("icons/") && it.name.endsWith(".svg") }.sortedBy { it.name }
                .map { measure(it.name.removePrefix("icons/").removeSuffix(".svg"), zip.getInputStream(it).readBytes()) }
        }
        rows.forEach { r -> assertEquals(r.id, if (abs(r.before.inkOffsetY) * OPTICAL_CENTRING < OPTICAL_DEADBAND * 24f) 0f else (-OPTICAL_CENTRING * r.before.inkOffsetY).coerceIn(-r.before.top.coerceAtLeast(0f), (24f - r.before.bottom).coerceAtLeast(0f)), r.shift, .01f) }
        report("private-offsets-${File(path).nameWithoutExtension}.md", table("Pack optical centring: ${File(path).name}", rows))
    }
}
