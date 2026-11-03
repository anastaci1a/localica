package io.github.anastaci1a.localica.ui

import kotlin.math.atan2
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/** OKLab lightness bounds per theme: dark/light art still reads as a quiet tile in both themes. */
internal object ArtworkTintTuning {
    const val DarkMinLightness = .32f
    const val DarkMaxLightness = .66f
    const val LightMinLightness = .52f
    const val LightMaxLightness = .86f
    const val MaxChroma = .13f
    /** Gray to tint, staggered by the loading wave's distance delay (#525). */
    const val CascadeMillis = 200
}

private fun toLinear(channel: Int): Double {
    val v = channel / 255.0
    return if (v <= .04045) v / 12.92 else ((v + .055) / 1.055).pow(2.4)
}

private fun toSrgb(linear: Double): Int {
    val v = linear.coerceIn(0.0, 1.0)
    val encoded = if (v <= .0031308) v * 12.92 else 1.055 * v.pow(1 / 2.4) - .055
    return (encoded * 255).roundToInt().coerceIn(0, 255)
}

/** Stored average (opaque ARGB) to the placeholder base: OKLCH lightness clamped per theme and
 * chroma capped, hue preserved. Pure; computed once per cell and colour, never per frame. */
internal fun placeholderTint(argb: Int, dark: Boolean): Int {
    val r = toLinear(argb shr 16 and 0xFF); val g = toLinear(argb shr 8 and 0xFF); val b = toLinear(argb and 0xFF)
    val l = cbrt(.4122214708 * r + .5363325363 * g + .0514459929 * b)
    val m = cbrt(.2119034982 * r + .6806995451 * g + .1073969566 * b)
    val s = cbrt(.0883024619 * r + .2817188376 * g + .6299787005 * b)
    val lightness = .2104542553 * l + .7936177850 * m - .0040720468 * s
    val labA = 1.9779984951 * l - 2.4285922050 * m + .4505937099 * s
    val labB = .0259040371 * l + .7827717662 * m - .8086757660 * s
    val (minL, maxL) = if (dark) ArtworkTintTuning.DarkMinLightness to ArtworkTintTuning.DarkMaxLightness
        else ArtworkTintTuning.LightMinLightness to ArtworkTintTuning.LightMaxLightness
    val tunedL = lightness.coerceIn(minL.toDouble(), maxL.toDouble())
    val chroma = hypot(labA, labB).coerceAtMost(ArtworkTintTuning.MaxChroma.toDouble())
    val hue = atan2(labB, labA)
    val a2 = chroma * cos(hue); val b2 = chroma * sin(hue)
    val l2 = (tunedL + .3963377774 * a2 + .2158037573 * b2).pow(3)
    val m2 = (tunedL - .1055613458 * a2 - .0638541728 * b2).pow(3)
    val s2 = (tunedL - .0894841775 * a2 - 1.2914855480 * b2).pow(3)
    val red = toSrgb(4.0767416621 * l2 - 3.3077115913 * m2 + .2309699292 * s2)
    val green = toSrgb(-1.2684380046 * l2 + 2.6097574011 * m2 - .3413193965 * s2)
    val blue = toSrgb(-.0041960863 * l2 - .7034186147 * m2 + 1.7076147010 * s2)
    return (0xFF shl 24) or (red shl 16) or (green shl 8) or blue
}

/** OKLab lightness of an opaque ARGB colour, for tests and tuning checks. */
internal fun oklabLightness(argb: Int): Float {
    val r = toLinear(argb shr 16 and 0xFF); val g = toLinear(argb shr 8 and 0xFF); val b = toLinear(argb and 0xFF)
    val l = cbrt(.4122214708 * r + .5363325363 * g + .0514459929 * b)
    val m = cbrt(.2119034982 * r + .6806995451 * g + .1073969566 * b)
    val s = cbrt(.0883024619 * r + .2817188376 * g + .6299787005 * b)
    return (.2104542553 * l + .7936177850 * m - .0040720468 * s).toFloat()
}
