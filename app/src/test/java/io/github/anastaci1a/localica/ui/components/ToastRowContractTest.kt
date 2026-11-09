package io.github.anastaci1a.localica.ui.components

import org.junit.Assert.*
import org.junit.Test

class ToastRowContractTest {
    private val activity = Any()
    private val root = ToastRowRectangle(0f, 0f, 400f, 800f)
    private fun basis(bottom: Float = 700f) = ToastRowBasis(root, bottom, emptyList())

    @Test fun popupWindowCannotRegisterEvenForAdmittedRoute() {
        val registry = ToastRowRegistry(activity)
        val route = ToastRowOwner("route")
        registry.admit(route)
        assertNull(registry.register(route, Any(), basis()))
        assertNull(registry.current)
        val valid = registry.register(route, activity, basis())
        assertSame(valid, registry.current)
    }

    @Test fun actualAdmissionWinsOverCachedSceneRegistrationOrder() {
        val registry = ToastRowRegistry(activity)
        val browse = ToastRowOwner("browse")
        val queue = ToastRowOwner("queue")
        val cached = ToastRowOwner("cached")
        registry.admit(queue)
        registry.register(browse, activity, basis())
        val queueReceipt = registry.register(queue, activity, basis(600f))
        registry.register(cached, activity, basis(750f))
        assertSame(queueReceipt, registry.current)
        registry.admit(browse)
        assertEquals(700f, registry.current!!.basis.bottom)
    }

    @Test fun oldReceiptCannotWithdrawRemeasuredOrSuccessorRow() {
        val registry = ToastRowRegistry(activity)
        val old = ToastRowOwner("route")
        val successor = ToastRowOwner("route")
        registry.admit(old)
        val first = registry.register(old, activity, basis())!!
        val measured = registry.register(old, activity, basis(650f))!!
        registry.withdraw(first)
        assertSame(measured, registry.current)
        val next = registry.register(successor, activity, basis(600f))!!
        registry.admit(successor)
        registry.withdraw(measured)
        assertSame(next, registry.current)
        registry.withdraw(next)
        assertNull(registry.current)
    }

    @Test fun emptyAndRaisedRowsRetainMeasuredBasisAndIndependentOriginalRectangles() {
        val buttons = mutableListOf(ToastRowButton("return", ToastRowRectangle(175f, 630f, 225f, 680f)),
            ToastRowButton("actions", ToastRowRectangle(338f, 642f, 388f, 692f)))
        val registry = ToastRowRegistry(activity)
        val route = ToastRowOwner("route")
        registry.admit(route)
        registry.register(route, activity, ToastRowBasis(root, 692f, buttons))
        buttons.clear()
        assertEquals(2, registry.current!!.basis.buttons.size)
        assertEquals(680f, registry.current!!.basis.buttons.first().original.bottom)
        registry.register(route, activity, basis(550f))
        assertEquals(550f, registry.current!!.basis.bottom)
        assertTrue(registry.current!!.basis.buttons.isEmpty())
        registry.admit(null)
        assertNull(registry.current)
    }
}
