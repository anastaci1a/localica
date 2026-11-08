package io.github.anastaci1a.localica.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import io.github.anastaci1a.localica.ui.components.ColorBinding
import org.junit.Assert.assertEquals
import org.junit.Test

/** U10.03 palette witness: the player palette follows the artwork blend clock through one map. A new song's blend that
 * starts mid-flight (target and reset clock together) keeps the shown color and its slope per unit of clock (C1). Palettes are
 * posed in Oklab, whose half-float storage resolves the slope that 8-bit sRGB packing would hide. */
class PlayerPaletteStampTest {
    private val a = listOf(Color(0xFF1B2A3A), Color(0xFF243447), Color(0xFF101820)).map { it.convert(ColorSpaces.Oklab) }
    private val b = listOf(Color(0xFFE8C07A), Color(0xFFD9A441), Color(0xFF8C5A1C)).map { it.convert(ColorSpaces.Oklab) }
    private val c = listOf(Color(0xFF3FA36B), Color(0xFF2E7D52), Color(0xFF14402A)).map { it.convert(ColorSpaces.Oklab) }
    private val h = .02f
    private fun lab(color: Color) = color.convert(ColorSpaces.Oklab).let { floatArrayOf(it.red, it.green, it.blue) }
    private fun slope(x: List<Color>, y: List<Color>) = x.indices.flatMap { i -> lab(y[i]).zip(lab(x[i])) { q, p -> (q - p) / h } }

    @Test fun songChangeMidBlendRejoinsC1AndSettlesExactly() {
        val map = ColorBinding(a)
        fun step(target: List<Color>, clock: Float) = map.resolve(target, clock).let { map.present(it); map.colors(it, target) }
        var clock = 0f; var previous = step(b, 0f); var shown = previous
        while (clock < .4f) { clock += h; previous = shown; shown = step(b, clock) }
        val before = slope(previous, shown)
        val joined = step(c, 0f); val next = step(c, h)
        joined.indices.forEach { i -> lab(joined[i]).zip(lab(shown[i])).forEach { (q, p) -> assertEquals("shown color is kept", p, q, 1e-4f) } }
        before.zip(slope(joined, next)).forEachIndexed { i, (u, v) -> assertEquals("channel $i slope per unit of clock", u, v, .05f + .05f * kotlin.math.abs(u)) }
        assertEquals("a settled blend shows its target exactly", c, step(c, 1f))
    }
}
