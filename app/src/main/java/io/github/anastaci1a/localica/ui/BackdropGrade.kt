package io.github.anastaci1a.localica.ui

import kotlin.math.abs
import kotlin.math.cbrt
import kotlin.math.pow

/**
 * The one grading of blurred-artwork backdrops (#576), independent of the glass chrome's adaptive-contrast curve.
 *
 * Measure the contrast of the region behind the text and controls (the mean the polarity sampler already
 * produces) against the CHOSEN ink with the same black/white contrast ratio the ink decision uses. If the target is
 * met nothing changes. Otherwise ONE lightness shift is found in OKLab, in the contrast-increasing direction only
 * (dark ink lightens, light ink darkens) and by the minimum that meets the target. The shift is applied to every
 * backdrop color with chroma and hue kept; chroma is reduced only where the shifted color would leave sRGB.
 * The shift is continuous in the measured color and is exactly 0 where the two inks tie, so the ink flip is seamless.
 */
internal object BackdropGrade {
    const val TARGET = 4.5
    /** Solve a hair past the target so 8-bit rounding of prepared pixels cannot fall under it. */
    private const val MARGIN = 1.004

    /** Contrast of relative luminance [y] against black (dark ink) or white (light ink) ink. */
    fun contrast(y: Double, lightInk: Boolean): Double = if (lightInk) 1.05 / (y + .05) else (y + .05) / .05

    /** OKLab lightness shift for a region of mean sRGB color: >= 0 for dark ink, <= 0 for light ink, 0 when met. */
    fun lightnessShift(r: Double, g: Double, b: Double, lightInk: Boolean, target: Double = TARGET): Float {
        val lr = linear(r); val lg = linear(g); val lb = linear(b)
        if (contrast(luminance(lr, lg, lb), lightInk) >= target) return 0f
        val goal = target * MARGIN
        val lab = toLab(lr, lg, lb)
        fun met(l: Double) = contrast(luminanceOf(l, lab[1], lab[2]), lightInk) >= goal
        // The far end (white for dark ink, black for light ink) always meets any target up to 21:1.
        var kept = if (lightInk) 0.0 else 1.0     // satisfying side
        var failed = lab[0]                        // not satisfying side
        repeat(40) {
            val mid = (kept + failed) / 2
            if (met(mid)) kept = mid else failed = mid
        }
        return (kept - lab[0]).toFloat()
    }

    /** Shift one sRGB color (0..1 channels) by [shift] OKLab lightness; 0 returns it untouched. */
    fun apply(r: Float, g: Float, b: Float, shift: Float): FloatArray {
        if (shift == 0f) return floatArrayOf(r, g, b)
        val lab = toLab(linear(r.toDouble()), linear(g.toDouble()), linear(b.toDouble()))
        val out = shifted(lab[0] + shift, lab[1], lab[2])
        return floatArrayOf(delinear(out[0]).toFloat(), delinear(out[1]).toFloat(), delinear(out[2]).toFloat())
    }

    /** The same grade on a packed ARGB pixel, for prepared bitmaps. */
    fun applyPixel(argb: Int, shift: Float): Int {
        if (shift == 0f) return argb
        val c = apply((argb ushr 16 and 255) / 255f, (argb ushr 8 and 255) / 255f, (argb and 255) / 255f, shift)
        fun byte(v: Float) = (v * 255f + .5f).toInt().coerceIn(0, 255)
        return (argb and -0x1000000) or (byte(c[0]) shl 16) or (byte(c[1]) shl 8) or byte(c[2])
    }

    /** Linear RGB of (lightness, a, b) with chroma scaled down only as far as needed to stay in gamut. */
    private fun shifted(lightness: Double, a: Double, b: Double): DoubleArray {
        val l = lightness.coerceIn(0.0, 1.0)
        var rgb = fromLab(l, a, b)
        if (inGamut(rgb)) return rgb
        var lo = 0.0; var hi = 1.0
        repeat(24) {
            val k = (lo + hi) / 2
            if (inGamut(fromLab(l, a * k, b * k))) lo = k else hi = k
        }
        rgb = fromLab(l, a * lo, b * lo)
        return doubleArrayOf(rgb[0].coerceIn(0.0, 1.0), rgb[1].coerceIn(0.0, 1.0), rgb[2].coerceIn(0.0, 1.0))
    }
    private fun luminanceOf(l: Double, a: Double, b: Double): Double {
        val rgb = shifted(l, a, b)
        return luminance(rgb[0], rgb[1], rgb[2])
    }
    private fun inGamut(rgb: DoubleArray) = rgb.all { it >= -1e-9 && it <= 1.0 + 1e-9 }
    fun luminance(r: Double, g: Double, b: Double) = .2126 * r + .7152 * g + .0722 * b
    private fun linear(x: Double) = if (x <= .04045) x / 12.92 else ((x + .055) / 1.055).pow(2.4)
    private fun delinear(x: Double): Double { val v = x.coerceIn(0.0, 1.0); return if (v <= .0031308) v * 12.92 else 1.055 * v.pow(1 / 2.4) - .055 }

    /** OKLab as [L, a, b] from linear sRGB. */
    fun toLab(r: Double, g: Double, b: Double): DoubleArray {
        val l = cbrt(.4122214708 * r + .5363325363 * g + .0514459929 * b)
        val m = cbrt(.2119034982 * r + .6806995451 * g + .1073969566 * b)
        val s = cbrt(.0883024619 * r + .2817188376 * g + .6299787005 * b)
        return doubleArrayOf(.2104542553 * l + .7936177850 * m - .0040720468 * s,
            1.9779984951 * l - 2.4285922050 * m + .4505937099 * s,
            .0259040371 * l + .7827717662 * m - .8086757660 * s)
    }
    fun fromLab(lightness: Double, a: Double, b: Double): DoubleArray {
        val l = (lightness + .3963377774 * a + .2158037573 * b).pow(3)
        val m = (lightness - .1055613458 * a - .0638541728 * b).pow(3)
        val s = (lightness - .0894841775 * a - 1.2914855480 * b).pow(3)
        return doubleArrayOf(4.0767416621 * l - 3.3077115913 * m + .2309699292 * s,
            -1.2684380046 * l + 2.6097574011 * m - .3413193965 * s,
            -.0041960863 * l - .7034186147 * m + 1.7076147010 * s)
    }
    /** sRGB (0..1) to OKLab, for tests and diagnostics. */
    fun labOf(r: Double, g: Double, b: Double) = toLab(linear(r), linear(g), linear(b))
    @Suppress("unused") private fun near(a: Double, b: Double) = abs(a - b) < 1e-9
}
