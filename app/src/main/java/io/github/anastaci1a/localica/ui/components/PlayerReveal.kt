package io.github.anastaci1a.localica.ui.components

import io.github.anastaci1a.localica.ui.engine.Curve

/** C1 Engine maps of the player phase (U10.03, I2): each reveal is a smoothstep window, never a clamped ramp. */
internal enum class PlayerReveal(start: Float, end: Float) {
    Seek(.8f, 1f), Actions(.83f, 1f), Lyrics(.78f, 1f), Cover(.85f, 1f), Background(.38f, .97f), Handle(.72f, 1f),
    Frost(.08f, .88f), Edge(0f, .18f), EdgeBand(0f, .75f), Album(.65f, 1f);
    private val curve = Curve.window(start, end)
    fun at(p: Float) = curve.at(p); fun slope(p: Float) = curve.slope(p)
}
