package io.github.anastaci1a.localica.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import io.github.anastaci1a.localica.ui.engine.PhaseBinding

/** U10.03 palette map on a published blend clock, with the U08.02a rule: each Oklab channel rides one PhaseBinding. A blend
 * from its origin is the Oklab chord; a retarget or restarted clock rejoins from the shown color and slope (C1). */
internal class ColorBinding(initial: List<Color>) {
    private val channels = initial.flatMap(::oklab).map(::PhaseBinding)
    fun resolve(target: List<Color>, clock: Float) = target.flatMap(::oklab).mapIndexed { i, t -> channels[i].resolve(t, clock, 1f) }
    fun present(steps: List<PhaseBinding.Step>) = steps.forEachIndexed { i, s -> channels[i].present(s) }
    /** The shown colors; a settled map returns its targets exactly. */
    fun colors(steps: List<PhaseBinding.Step>, target: List<Color>) = if (steps.all { it.value == it.target }) target else
        steps.chunked(3).mapIndexed { i, (l, a, b) -> Color(l.value, a.value - .5f, b.value - .5f, target[i].alpha, ColorSpaces.Oklab).convert(target[i].colorSpace) }
    // Oklab a/b sit within ±.5 for sRGB, so the offset keeps every channel inside the binding's unit range.
    private fun oklab(c: Color) = c.convert(ColorSpaces.Oklab).let { listOf(it.red, it.green + .5f, it.blue + .5f) }
}
