package io.github.anastaci1a.localica

import android.os.SystemClock
import android.view.MotionEvent
import android.view.Window
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.ui.ModalGestureCoordinator
import io.github.anastaci1a.localica.ui.ModalGestureScope
import io.github.anastaci1a.localica.ui.ModalGestureWindowCallback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy

/** The main-loop boundary matters even when modal registration occurs during Compose apply. */
class ModalGestureCancellationTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    private class Contact {
        val events = mutableListOf<Int>()
        var duringDispatch: ((Int) -> Unit)? = null
        val owner = ModalGestureScope(ModalGestureCoordinator(), null, CoroutineScope(Dispatchers.Main))
        private val original = Proxy.newProxyInstance(Window.Callback::class.java.classLoader,
            arrayOf(Window.Callback::class.java)) { _, method, args ->
            if (method.name == "dispatchTouchEvent") {
                events += (args!![0] as MotionEvent).actionMasked
                duringDispatch?.invoke(events.last())
                true
            } else null
        } as Window.Callback
        val callback = ModalGestureWindowCallback(original, owner)
        private val downTime = SystemClock.uptimeMillis()
        fun send(action: Int): Boolean {
            val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, 40f, 40f, 0)
            return try { callback.dispatchTouchEvent(event) } finally { event.recycle() }
        }
    }

    @Test fun reentrantModalActivationDefersCancelUntilOriginalNativeDispatchReturns() {
        instrumentation.runOnMainSync {
            val contact = Contact()
            contact.owner.registerCancellation(contact.callback::cancelContact)
            var insideOriginalDispatch = false
            var activated = false
            contact.duringDispatch = { action ->
                if (action == MotionEvent.ACTION_DOWN && !activated) {
                    activated = true
                    insideOriginalDispatch = true
                    contact.owner.block(true)
                    contact.owner.block(false)
                    assertEquals("Opening and dismissing a modal must not re-enter native dispatch",
                        listOf(MotionEvent.ACTION_DOWN), contact.events)
                    insideOriginalDispatch = false
                } else if (action == MotionEvent.ACTION_CANCEL) {
                    assertFalse(insideOriginalDispatch)
                }
            }
            contact.send(MotionEvent.ACTION_DOWN)
            assertEquals(listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_CANCEL), contact.events)
            assertTrue(contact.send(MotionEvent.ACTION_UP))
            assertEquals("The cancelled release stays quarantined", 2, contact.events.size)
            contact.send(MotionEvent.ACTION_DOWN)
            contact.send(MotionEvent.ACTION_UP)
            assertEquals("A new contact remains usable", listOf(0, 3, 0, 1), contact.events)
            contact.callback.dispose()
        }
    }

    @Test fun lateRegistrationQuarantinesImmediatelyButDispatchesAfterTheApplyTurn() {
        lateinit var contact: Contact
        instrumentation.runOnMainSync {
            contact = Contact()
            contact.send(MotionEvent.ACTION_DOWN)
            contact.owner.block(true)
            contact.owner.registerCancellation(contact.callback::cancelContact)
            assertEquals(listOf(MotionEvent.ACTION_DOWN), contact.events)
            contact.owner.block(false)
        }
        instrumentation.waitForIdleSync()
        instrumentation.runOnMainSync {
            assertEquals(listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_CANCEL), contact.events)
            assertTrue(contact.send(MotionEvent.ACTION_UP))
            assertEquals(2, contact.events.size)
            contact.send(MotionEvent.ACTION_DOWN)
            contact.send(MotionEvent.ACTION_UP)
            assertEquals(listOf(0, 3, 0, 1), contact.events)
            contact.callback.dispose()
        }
    }

    @Test fun upBeforePostedCancelPreservesCancellationAndIsSwallowed() {
        instrumentation.runOnMainSync {
            val contact = Contact()
            contact.owner.registerCancellation(contact.callback::cancelContact)
            contact.send(MotionEvent.ACTION_DOWN)
            contact.owner.block(true)
            assertEquals(listOf(MotionEvent.ACTION_DOWN), contact.events)
            contact.owner.block(false)
            contact.send(MotionEvent.ACTION_UP)
            assertEquals(listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_CANCEL), contact.events)
            contact.callback.dispose()
        }
        instrumentation.waitForIdleSync()
    }

    @Test fun disposalRemovesPostedCancellation() {
        lateinit var contact: Contact
        instrumentation.runOnMainSync {
            contact = Contact()
            contact.send(MotionEvent.ACTION_DOWN)
            contact.callback.cancelContact()
            contact.callback.dispose()
        }
        instrumentation.waitForIdleSync()
        assertEquals(listOf(MotionEvent.ACTION_DOWN), contact.events)
    }
}
