package io.github.anastaci1a.localica.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import org.junit.Assert.*
import org.junit.Test

class SelectorSceneGeometryTest {
    private val shell = LensGeometry(Rect(0f, 0f, 300f, 64f), 32f)
    private val selector = LensGeometry(Rect(4f, 4f, 96f, 60f), 28f)
    private fun scene(slots: List<SelectorInkSlot> = listOf(SelectorInkSlot(Rect(0f, 0f, 100f, 64f), Rect(0f, 0f, 100f, 64f)))) =
        SelectorSceneGeometry(shell, selector, slots, .72f, .72f)

    @Test fun composesBothRaysBeforeLookingUpTheStableBackdrop() {
        val p = Offset(15f, 26f)
        val sample = scene().sample(p, true)
        val first = lensRay(selector.field(p), selector.span, .72f)
        val q = p + first.offset
        val second = lensRay(shell.field(q), shell.span, .72f)
        assertEquals(q, sample.surface)
        assertEquals(q + second.offset, sample.backdrop)
        assertEquals(q, sample.ink)
        assertNotEquals(p + lensRay(shell.field(p), shell.span, .72f).offset, sample.backdrop)
    }

    @Test fun changingSlotStretchMovesOnlyInkNotTheBackground() {
        val source = Rect(0f, 0f, 100f, 64f)
        val slot = SelectorInkSlot(source, Rect(-12f, 2f, 104f, 62f))
        val p = Offset(35f, 26f)
        val normal = scene().sample(p, true)
        val stretched = scene(listOf(slot)).sample(p, true)
        assertEquals(normal.backdrop, stretched.backdrop)
        assertEquals(normal.surface, stretched.surface)
        assertEquals(slot.sourceAt(stretched.surface), stretched.ink)
        assertNotEquals(normal.ink, stretched.ink)
        assertEquals(normal.backdrop, scene(listOf(slot)).sample(p, false).backdrop)
        assertNull(scene(listOf(slot)).sample(p, false).ink)
    }

    @Test fun aMirroredOrVerticalSlotUsesItsOwnInverseTransform() {
        val source = Rect(0f, 0f, 100f, 64f)
        val right = SelectorInkSlot(source, Rect(200f, 2f, 310f, 62f))
        assertEquals(source.center, right.sourceAt(right.displayed.center))
        assertEquals(source.topLeft, right.sourceAt(right.displayed.topLeft))
        val vertical = SelectorInkSlot(Rect(0f, 0f, 280f, 52f), Rect(4f, -9f, 276f, 48f))
        assertEquals(vertical.source.center, vertical.sourceAt(vertical.displayed.center))
    }

    @Test fun maskFollowsTheSampleCoordinateAndInkOutsideShellIsAbsent() {
        val s = scene()
        for (x in 0..100) for (y in 0..64) {
            val sample = s.sample(Offset(x.toFloat(), y.toFloat()), true)
            assertEquals(sample.ink != null && selector.field(sample.surface).distance >= 0f, sample.selectedInk)
        }
        val outside = s.sample(Offset(-20f, -20f), true)
        assertNull(outside.ink)
        assertNull(outside.shellRay)
        assertEquals(Offset(-20f, -20f), outside.backdrop)
    }
}
