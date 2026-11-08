package io.github.anastaci1a.localica.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import io.github.anastaci1a.localica.icons.IconPackImages
import io.github.anastaci1a.localica.icons.SvgIconParser
import io.github.anastaci1a.localica.icons.toImageVector
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipInputStream
import kotlin.math.abs

/** Pack play/pause morph (#662): structure gate, split, correspondence and the no-crazy-tween checks. */
class PackPlayPauseMorphTest {
    private fun svg(vararg paths: String) = SvgIconParser.parse(
        """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24">${paths.joinToString("")}</svg>""".toByteArray()).toImageVector()
    private fun path(d: String, attributes: String = "") = """<path d="$d" $attributes/>"""

    // The app's own static transport art, as a pack would import it.
    private val builtinPlay = svg(path("M5,4.4Q5,2.8 6.5,3.7L20,11.1Q21.5,12 20,12.9L6.5,20.3Q5,21.2 5,19.6Z"))
    private val builtinPause = svg(path("M5,3H9Q10,3 10,4V20Q10,21 9,21H5Q4,21 4,20V4Q4,3 5,3ZM15,3H19Q20,3 20,4V20Q20,21 19,21H15Q14,21 14,20V4Q14,3 15,3Z"))
    // Different proportions: a wide squat sharp triangle, narrow tall bars with arc corners and a wide gap.
    private val syntheticPlay = svg(path("M3 5 L22 12 L3 19 Z"))
    private val syntheticPause = svg(path("M5 3 H7 A1 1 0 0 1 8 4 V20 A1 1 0 0 1 7 21 H5 A1 1 0 0 1 4 20 V4 A1 1 0 0 1 5 3 Z"),
        path("M17 3 H19 A1 1 0 0 1 20 4 V20 A1 1 0 0 1 19 21 H17 A1 1 0 0 1 16 20 V4 A1 1 0 0 1 17 3 Z"))
    // No usable corners: an egg and two capsules take the arc-length correspondence.
    private val eggPlay = svg(path("M4 12 C4 5 21 7 21 12 C21 17 4 19 4 12 Z"))
    private val capsulePause = svg(path("M5 6 A2 2 0 0 1 9 6 V18 A2 2 0 0 1 5 18 Z M15 6 A2 2 0 0 1 19 6 V18 A2 2 0 0 1 15 18 Z"))

    private fun morph(play: ImageVector, pause: ImageVector): PackPlayPauseMorph = when (val plan = planPackPlayPause(play, pause)) {
        is PackPlayPausePlan.Morph -> plan.morph
        is PackPlayPausePlan.Unsupported -> throw AssertionError("Expected a morph, got ${plan.reason}")
    }
    private fun reason(play: ImageVector, pause: ImageVector) =
        (planPackPlayPause(play, pause) as? PackPlayPausePlan.Unsupported)?.reason ?: throw AssertionError("Expected a fallback")

    @Test fun contoursComeOutInUnitCoordinatesWithGroupTransformsAndCurves() {
        val square = svg("""<g transform="translate(2 2) scale(0.5)">${path("M0 0 H40 V40 H0 Z")}</g>""").fillContours()
        assertEquals(1, square.size)
        val expected = listOf(Offset(2f, 2f), Offset(22f, 2f), Offset(22f, 22f), Offset(2f, 22f)).map { it / 24f }
        assertEquals(expected.size, square.single().size)
        expected.indices.forEach { assertEquals(0f, (expected[it] - square.single()[it]).getDistance(), 1e-6f) }
        assertEquals(2, builtinPause.fillContours().size)
        assertEquals(2, syntheticPause.fillContours().size)
        val circle = svg(path("M12 2 A10 10 0 1 1 11.99 2 Z")).fillContours().single()
        assertEquals(Math.PI * 100 / 576, signedArea(circle).toDouble(), .02)
        assertEquals(Offset(.5f, .5f).x, centroid(circle).x, .002f)
    }

    @Test fun unsupportedStructuresNameTheirReason() {
        assertEquals("pause has 1 shapes, not 2", reason(builtinPlay, svg(path("M10 3H14V21H10Z"))))
        assertEquals("play has 2 shapes, not 1", reason(builtinPause, builtinPause))
        assertEquals("pause has 3 shapes, not 2", reason(builtinPlay,
            svg(path("M4 3H10V21H4Z M6 8H8V16H6Z", "fill-rule=\"evenodd\""), path("M14 3H20V21H14Z"))))
        assertEquals("the icon has a stroked path", reason(svg(path("M5 4 L20 12 L5 20 Z", "fill=\"none\" stroke=\"black\" stroke-width=\"2\"")), builtinPause))
        assertEquals("the icon has a translucent fill", reason(builtinPlay, svg(path("M4 3H10V21H4Z", "fill-opacity=\".5\""), path("M14 3H20V21H14Z"))))
        assertEquals("the pause shapes overlap horizontally", reason(builtinPlay, svg(path("M4 3H14V21H4Z"), path("M10 3H20V21H10Z"))))
        assertEquals("the vertical cut does not split the play shape into two simple pieces",
            reason(svg(path("M4 4 H20 V8 H8 V16 H20 V20 H4 Z")), builtinPause))
    }

    @Test fun cornersClusterRoundedTurnsAndSkipGentleBends() {
        val sharp = svg(path("M4 3H10V21H4Z")).fillContours().single()
        assertEquals(4, corners(sharp).size)
        val rounded = builtinPause.fillContours()[0]
        assertEquals(4, corners(rounded).size)
        val capsule = capsulePause.fillContours()[0]
        assertEquals(2, corners(capsule).size)
        // The egg has no straight runs, so its two high-curvature ends read as rounded corners; capsules pair poorly with that.
        assertTrue(corners(eggPlay.fillContours().single()).size <= 2)
    }

    @Test fun builtInArtAndTheSyntheticPackMatchCornersWhileCornerlessArtUsesArcLength() {
        assertEquals("corner-matched", morph(builtinPlay, builtinPause).matching)
        assertEquals("corner-matched", morph(syntheticPlay, syntheticPause).matching)
        assertEquals("arc-length", morph(eggPlay, capsulePause).matching)
        val m = morph(builtinPlay, builtinPause)
        assertTrue(abs(m.left.from.size - PACK_MORPH_POINTS) <= 4 && m.left.from.size == m.left.to.size)
        val play = builtinPlay.fillContours().single()
        assertEquals("Equal bars cut the ink width in the middle", (play.minOf { it.x } + play.maxOf { it.x }) / 2f, m.cutX, 1e-6f)
        val unequal = morph(syntheticPlay, svg(path("M4 3H6V21H4Z"), path("M14 3H20V21H14Z")))
        assertEquals("Bars 2:6 cut a quarter of the way in", 3f / 24f + 19f / 24f * .25f, unequal.cutX, 1e-6f)
    }

    @Test fun everyGeneratedMorphIsPredictable() {
        verify("built-in art", morph(builtinPlay, builtinPause), builtinPlay, builtinPause)
        verify("synthetic", morph(syntheticPlay, syntheticPause), syntheticPlay, syntheticPause)
        verify("egg and capsules", morph(eggPlay, capsulePause), eggPlay, capsulePause)
        privatePack()?.let { (play, pause) -> verify("private pack", morph(play, pause), play, pause) }
    }

    @Test fun theRightPieceGrowsAndMovesRightLikeTheHandTunedMorph() {
        val tuned = PlayPauseShapes()
        fun tunedPiece(from: List<Offset>, to: List<Offset>, p: Float) = from.indices.map { from[it] + (to[it] - from[it]) * p }
        val m = morph(builtinPlay, builtinPause)
        // Right piece: the hand-tuned apex grows into its bar and moves right; so does ours.
        assertTrue(signedArea(tunedPiece(tuned.playRight, tuned.pauseRight, 1f)) > signedArea(tunedPiece(tuned.playRight, tuned.pauseRight, 0f)))
        assertTrue(signedArea(m.right.at(1f)) > signedArea(m.right.at(0f)))
        assertTrue(centroid(tunedPiece(tuned.playRight, tuned.pauseRight, 1f)).x > centroid(tunedPiece(tuned.playRight, tuned.pauseRight, 0f)).x)
        assertTrue(centroid(m.right.at(1f)).x > centroid(m.right.at(0f)).x)
        // The left piece holds most of the ink at a middle cut; whether it grows or shrinks depends on the bar width,
        // so only its monotonicity is asserted (in verify).
        assertTrue(centroid(m.left.at(1f)).x <= centroid(m.left.at(0f)).x + .05f)
    }

    @Test fun plansAreCachedPerPublishedPackAndRasterIconsFallBack() {
        val logs = mutableListOf<String>()
        val images = IconPackImages(vectors = mapOf("transport.play" to builtinPlay, "transport.pause" to builtinPause))
        val first = PackPlayPauseMorphs.plan(images, { error("built-ins are not needed") }, logs::add)
        assertSame(first, PackPlayPauseMorphs.plan(images, { error("built-ins are not needed") }, logs::add))
        assertTrue(first is PackPlayPausePlan.Morph)
        assertEquals(1, logs.size)
        assertTrue(logs[0], logs[0].startsWith("Pack play/pause morph ready (corner-matched"))
        // Only pause replaced: the built-in play geometry supplies the other endpoint.
        val mixed = IconPackImages(vectors = mapOf("transport.pause" to syntheticPause))
        assertTrue(PackPlayPauseMorphs.plan(mixed, { builtinPlay }, logs::add) is PackPlayPausePlan.Morph)
        val unsupported = IconPackImages(vectors = mapOf("transport.play" to builtinPlay, "transport.pause" to svg(path("M10 3H14V21H10Z"))))
        val plan = PackPlayPauseMorphs.plan(unsupported, { error("unused") }, logs::add)
        assertEquals("pause has 1 shapes, not 2", (plan as PackPlayPausePlan.Unsupported).reason)
        assertEquals("Pack play/pause morph unavailable: pause has 1 shapes, not 2; cross-fading instead", logs.last())
    }

    /** Frame geometry and metrics for review; set LOCALICA_MORPH_STRIP_DIR (and LOCALICA_PRIVATE_PACK) locally.
     * The unit-test classpath has no java.awt, so a separate tool rasterises frames-662.txt into the strips. */
    @Test fun exportFrameStripsForReview() {
        val dir = System.getenv("LOCALICA_MORPH_STRIP_DIR")?.takeIf { it.isNotBlank() }?.let(::File)
        assumeTrue("LOCALICA_MORPH_STRIP_DIR is not set", dir != null)
        dir!!.mkdirs()
        val tuned = PlayPauseShapes()
        val rows = mutableListOf<StripRow>()
        rows += StripRow("builtin-hand-tuned", null) { p ->
            if (p <= 0f) listOf(rounded(tuned.playOutline, tuned.cornerRadius))
            else listOf(rounded(tuned.playLeft.indices.map { tuned.playLeft[it] + (tuned.pauseLeft[it] - tuned.playLeft[it]) * p }, tuned.cornerRadius),
                rounded(tuned.playRight.indices.map { tuned.playRight[it] + (tuned.pauseRight[it] - tuned.playRight[it]) * p }, tuned.cornerRadius))
        }
        rows += packRow("builtin-art-generated", builtinPlay, builtinPause)
        privatePack()?.let { (play, pause) -> rows += packRow("private-pack", play, pause) }
        rows += packRow("synthetic", syntheticPlay, syntheticPause)
        rows += packRow("egg-capsules-arc-length", eggPlay, capsulePause)
        val steps = (0..10).map { it / 10f }
        exportGeometry(File(dir, "frames-662.txt"), rows, steps)
        File(dir, "rows.txt").writeText(rows.joinToString("\n") { row ->
            row.name + (row.morph?.let { " matching=${it.matching} cutX=${"%.4f".format(it.cutX)} points=${it.left.from.size}+${it.right.from.size}" } ?: " (reference)")
        } + "\n")
        File(dir, "metrics-662.csv").writeText(buildString {
            appendLine("row,piece,progress,area,area_ratio_to_target,centroid_x,centroid_y,min_x,max_x,min_y,max_y")
            for (row in rows) for (p in steps) for ((index, named) in row.pieces(p).withIndex()) {
                val (piece, name) = named
                val targets = row.frame(1f)
                val ratio = if (index < targets.size && (row.morph != null || p > 0f)) "%.4f".format(signedArea(piece) / signedArea(targets[index])) else ""
                appendLine("${row.name},$name,${"%.1f".format(p)},${"%.5f".format(signedArea(piece))},$ratio,${"%.5f".format(centroid(piece).x)},${"%.5f".format(centroid(piece).y)}," +
                    "${"%.5f".format(piece.minOf { it.x })},${"%.5f".format(piece.maxOf { it.x })},${"%.5f".format(piece.minOf { it.y })},${"%.5f".format(piece.maxOf { it.y })}")
            }
        })
    }

    // ---- Predictability checks --------------------------------------------------------------------

    private fun verify(name: String, m: PackPlayPauseMorph, playVector: ImageVector, pauseVector: ImageVector) {
        val play = playVector.fillContours().single()
        val bars = pauseVector.fillContours().sortedBy { centroid(it).x }
        val steps = (0..20).map { it / 20f }
        for ((piece, label) in listOf(m.left to "left", m.right to "right")) {
            val frames = steps.map(piece::at)
            frames.forEachIndexed { i, frame ->
                assertTrue("$name $label self-intersects at ${steps[i]}", isSimple(dedupe(frame)))
                assertTrue("$name $label winding flips at ${steps[i]}", signedArea(frame) > 0f)
            }
            monotone("$name $label area", frames.map { signedArea(it) })
            monotone("$name $label centroid x", frames.map { centroid(it).x }, 5e-4f)
            monotone("$name $label centroid y", frames.map { centroid(it).y }, 5e-4f)
            for (k in piece.from.indices) {
                val direct = (piece.to[k] - piece.from[k]).getDistance()
                val travelled = (1 until steps.size).sumOf { (frames[it][k] - frames[it - 1][k]).getDistance().toDouble() }.toFloat()
                assertEquals("$name $label point $k loops or reverses", direct, travelled, 1e-4f)
                assertTrue("$name $label point $k travels $direct", direct <= .5f)
            }
        }
        for (i in 1 until steps.size) assertTrue("$name pieces overlap at ${steps[i]}", disjoint(m.left.at(steps[i]), m.right.at(steps[i])))
        val eps = .5f / 72f // half a pixel at 72 px
        val startPieces = listOf(m.left.at(0f), m.right.at(0f))
        for (p in startPieces.flatten()) assertTrue("$name start frame leaves the play outline at $p",
            abs(p.x - m.cutX) <= 1e-4f || distanceToOutline(p, play) <= eps)
        for (v in play) assertTrue("$name start frame misses the play vertex $v", startPieces.minOf { distanceToOutline(v, it) } <= eps)
        for ((piece, bar) in listOf(m.left to bars[0], m.right to bars[1])) {
            val end = piece.at(1f)
            for (p in end) assertTrue("$name end frame leaves the bar at $p", distanceToOutline(p, bar) <= eps)
            for (v in bar) assertTrue("$name end frame misses the bar vertex $v", distanceToOutline(v, end) <= eps)
        }
    }

    private fun monotone(label: String, values: List<Float>, tolerance: Float = 1e-4f) {
        val diffs = (1 until values.size).map { values[it] - values[it - 1] }
        assertTrue("$label is not monotonic: $values", diffs.all { it >= -tolerance } || diffs.all { it <= tolerance })
    }

    private fun distanceToOutline(p: Offset, c: Contour): Float = c.indices.minOf { i ->
        val a = c[i]; val b = c[(i + 1) % c.size]
        val ab = b - a
        val t = if (ab.getDistanceSquared() == 0f) 0f else (((p - a).x * ab.x + (p - a).y * ab.y) / ab.getDistanceSquared()).coerceIn(0f, 1f)
        (p - (a + ab * t)).getDistance()
    }

    private fun privatePack(): Pair<ImageVector, ImageVector>? {
        val zip = System.getenv("LOCALICA_PRIVATE_PACK")?.takeIf { it.isNotBlank() }?.let(::File)?.takeIf { it.isFile } ?: return null
        val icons = mutableMapOf<String, ByteArray>()
        ZipInputStream(zip.inputStream()).use { input ->
            while (true) {
                val entry = input.nextEntry ?: break
                if (entry.name in setOf("icons/transport.play.svg", "icons/transport.pause.svg")) icons[entry.name] = input.readBytes()
            }
        }
        val play = icons["icons/transport.play.svg"] ?: return null
        val pause = icons["icons/transport.pause.svg"] ?: return null
        return SvgIconParser.parse(play).toImageVector() to SvgIconParser.parse(pause).toImageVector()
    }

    // ---- Strip rendering (JVM raster of the same geometry the app draws) ----------------------------

    private class StripRow(val name: String, val morph: PackPlayPauseMorph?, val frame: (Float) -> List<Contour>) {
        fun pieces(p: Float): List<Pair<Contour, String>> = morph?.let { listOf(it.left.at(p) to "left", it.right.at(p) to "right") }
            ?: frame(p).mapIndexed { i, c -> c to "shape$i" }
    }

    private fun packRow(name: String, play: ImageVector, pause: ImageVector): StripRow {
        val m = morph(play, pause)
        val playContours = play.fillContours(); val pauseContours = pause.fillContours()
        return StripRow(name, m) { p ->
            when {
                p <= 0f -> playContours
                p >= 1f -> pauseContours
                else -> listOf(m.left.at(p), m.right.at(p))
            }
        }
    }

    /** The app's rounded polygon as a dense polyline. */
    private fun rounded(points: List<Offset>, radius: Float): Contour {
        val vertices = points.fold(mutableListOf<Offset>()) { result, point -> if (result.lastOrNull() != point) result += point; result }
        val out = mutableListOf<Offset>()
        vertices.forEachIndexed { i, point ->
            val before = vertices[(i + vertices.size - 1) % vertices.size] - point
            val after = vertices[(i + 1) % vertices.size] - point
            val a = point + before * (radius / before.getDistance().coerceAtLeast(.001f)).coerceAtMost(.45f)
            val b = point + after * (radius / after.getDistance().coerceAtLeast(.001f)).coerceAtMost(.45f)
            for (k in 0..8) { val t = k / 8f; val u = 1f - t; out += a * (u * u) + point * (2f * u * t) + b * (t * t) }
        }
        return out
    }

    /** One line per filled shape: row, progress, kind (frame|left|right|target), then x,y pairs in unit coordinates. */
    private fun exportGeometry(file: File, rows: List<StripRow>, steps: List<Float>) {
        file.writeText(buildString {
            for (row in rows) for (p in steps) {
                fun line(kind: String, c: Contour) = appendLine("${row.name}\t${"%.1f".format(p)}\t$kind\t" +
                    c.joinToString(" ") { "${"%.5f".format(it.x)},${"%.5f".format(it.y)}" })
                row.frame(p).forEach { line("frame", it) }
                row.morph?.let { m -> line("left", m.left.at(p)); line("right", m.right.at(p)); row.frame(1f).forEach { line("target", it) } }
            }
        })
    }
}
