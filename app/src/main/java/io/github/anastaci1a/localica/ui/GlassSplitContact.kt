package io.github.anastaci1a.localica.ui

/** The modal owns the left body's clock only after the shared body separates. */
internal class GlassSplitContact(val left: GlassContact) {
    val joined = GlassContact()
    val right = GlassContact()
    private var split = false

    fun update(separated: Boolean) {
        if (split == separated) return
        if (separated) {
            adopt(left, joined)
            adopt(right, joined)
        } else {
            adopt(joined, left, right)
            joined.pressed = left.pressed || right.pressed
        }
        split = separated
        joined.active = !split
        left.active = split
        right.active = split
    }

    private fun adopt(target: GlassContact, a: GlassContact, b: GlassContact = a) {
        target.state.momentum.blendFrom(a.state.momentum, b.state.momentum, .5f,
            if (a.vertical == target.vertical) 1f else -1f,
            if (b.vertical == target.vertical) 1f else -1f)
        target.state.output.value = target.state.momentum.shape
        target.state.previous = target.travel + target.expansion.value
        target.state.initialized = true
    }

    fun presentation(body: GlassContact, other: GlassContact, separation: Float): GlassShape {
        // Observe each clock even though blending uses its uncompressed spring state.
        body.body.value; other.body.value
        return body.state.momentum.blendedShape(other.state.momentum, .5f * (1f - separation),
            if (body.vertical == other.vertical) 1f else -1f)
    }
}
