package io.github.anastaci1a.localica.ui.components

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.*
import org.junit.Test

class FloatingRowFacesTest {
    @Test fun replacementIsSingleAndOldDisposalOrMeasurementCannotAffectIt() {
        val window = Any()
        val host = FloatingRowFaces(window)
        val scene = FloatingRowScene(ToastRowOwner("route"))
        val old = FloatingRowFaces.Face(scene, "return", IntSize(50, 50)) {}
        val current = FloatingRowFaces.Face(scene, "return", IntSize(50, 50)) {}
        host.attach(old)
        host.attach(current)
        host.measured(old, IntSize(200, 200))
        host.detach(old)
        assertEquals(listOf(current), host.faces)
        assertEquals(IntSize(50, 50), current.size)
        assertFalse(current.measured)
        host.measured(current, IntSize(60, 60))
        assertTrue(current.measured)
        assertEquals(IntSize(60, 60), current.size)
        assertTrue(host.acceptsWindow(window))
        assertFalse(host.acceptsWindow(Any()))
    }

    @Test fun cachedRouteCannotReplaceAnotherScenesOriginalGeometry() {
        val host = FloatingRowFaces(Any())
        val first = FloatingRowScene(ToastRowOwner("route"))
        val cached = FloatingRowScene(ToastRowOwner("cached"))
        val a = FloatingRowFaces.Face(first, "return", IntSize(50, 50)) {}
        val b = FloatingRowFaces.Face(cached, "return", IntSize(50, 50)) {}
        a.original = Rect(175f, 630f, 225f, 680f)
        b.original = Rect(175f, 330f, 225f, 380f)
        host.attach(a); host.attach(b)
        assertEquals(listOf(a), host.originals(first))
        assertEquals(680f, host.originals(first).single().original!!.bottom)
        host.detach(a)
        assertTrue(host.originals(first).isEmpty())
        assertEquals(listOf(b), host.originals(cached))
    }

    @Test fun oldToastExtentOwnerCannotWithdrawItsSuccessor() {
        val binding = ToastRowPresentation()
        val old = Any(); val current = Any()
        binding.bind(old) { Rect(0f, 0f, 100f, 50f) }
        binding.bind(current) { Rect(20f, 0f, 120f, 50f) }
        binding.unbind(old)
        assertEquals(20f, binding.body()!!.left)
        binding.unbind(current)
        assertNull(binding.body())
    }
}
