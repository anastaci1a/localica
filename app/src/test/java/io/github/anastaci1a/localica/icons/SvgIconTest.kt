package io.github.anastaci1a.localica.icons

import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class SvgIconTest {
    private fun svg(body: String, attributes: String = "") =
        """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" $attributes>$body</svg>""".toByteArray()
    private val path = """<path d="M2 2 H22 V22 H2 Z"/>"""
    @Test fun inheritedPaintAndOrderedTransformsRetainNativePaths() {
        val result = SvgIconParser.parse(svg("""<g fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" transform="translate(2 3) rotate(30 12 12) scale(.5)"><path d="M0 0 C1 2 3 4 5 6" fill-rule="evenodd"/></g>"""))
        val group = result.children.single() as SvgNode.Group
        assertEquals(listOf("translate", "rotate", "scale"), group.transforms.map { it.name })
        val p = group.children.single() as SvgNode.Path
        assertFalse(p.style.fill); assertTrue(p.style.stroke)
        assertEquals(1.8f, p.style.width); assertEquals("round", p.style.cap)
        assertEquals("evenodd", p.style.rule)
        assertEquals("M0 0 C1 2 3 4 5 6", p.data)
    }
    @Test fun hostileAndUnsupportedInputsAreRejected() {
        val bad = listOf(
            "<!DOCTYPE svg [<!ENTITY x SYSTEM 'file:///etc/passwd'>]>" + String(svg(path)),
            "<?target unsafe?>" + String(svg(path)),
            String(svg("<script/>")), String(svg("<image href='https://example.invalid/a'/>")),
            String(svg("<use href='#a'/>")), String(svg(path, "style='fill:black'")),
            String(svg(path, "onclick='bad()'")), String(svg("<path d='M0 0L1 1' fill='url(http://example.invalid)'/>")),
            String(svg("<g transform='matrix(1 0 0 1 0 0)'>$path</g>")),
            String(svg("<path d='L1 1'/>")), String(svg("<path d='M1 2 C3 4'/>")),
            String(svg("<path d='M0 0 A1 1 0 2 0 3 3'/>")),
            String(svg("<path d='M0 0 L1e100 2'/>")), String(svg("<path d='MNaN 0'/>")),
            String(svg("<g>$path</svg>")), String(svg("<path d='M0 0'>text</path>")),
            String(svg("<g transform='scale(100000)'><g transform='scale(100000)'>$path</g></g>")),
            String(svg(path, "viewBox='0 0 1e-40 24'")),
            String(svg("<foreignObject/>")), String(svg(path, "viewBox='0 0 0 0'")),
            String(svg(path.repeat(257))), String(svg("<g>".repeat(20) + path + "</g>".repeat(20))))
        bad.forEach { value -> assertTrue(value.take(80), runCatching { SvgIconParser.parse(value.toByteArray()) }.isFailure) }
        assertTrue(runCatching { SvgIconParser.parse(ByteArray(SvgIconParser.MAX_BYTES + 1)) }.isFailure)
        assertTrue(runCatching { SvgIconParser.parse(byteArrayOf(0xc3.toByte(), 0x28)) }.isFailure)
        val group = "<g transform='${"scale(1) ".repeat(16)}'>"
        assertTrue(runCatching { SvgIconParser.parse(svg(group.repeat(3) + path + "</g>".repeat(3))) }.isFailure)
    }
    @Test(timeout = 10000) fun boundedMutationCorpusNeverEscapesWithAnError() {
        val random = Random(630)
        val valid = String(svg(path))
        repeat(500) {
            val chars = valid.toMutableList()
            repeat(random.nextInt(1, 12)) {
                val index = random.nextInt(chars.size)
                chars[index] = random.nextInt(0, 128).toChar()
            }
            try { SvgIconParser.parse(chars.joinToString("").toByteArray()) }
            catch (_: Exception) { /* A clear per-icon import rejection is expected. */ }
        }
    }
    @Test fun largeNumericCorpusHasAnExplicitCostLimit() {
        val huge = "M0 0 " + "L1 1 ".repeat(9000)
        assertTrue(runCatching { SvgIconParser.parse(svg("<path d='$huge'/>")) }.isFailure)
    }
}
