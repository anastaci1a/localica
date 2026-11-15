package io.github.anastaci1a.localica

import android.app.Dialog
import android.os.Looper
import android.view.Window
import androidx.compose.ui.geometry.Rect
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.ui.GlassPopupFlight
import io.github.anastaci1a.localica.ui.PopupEvent
import io.github.anastaci1a.localica.ui.SamplingBasis

/** #920: fixtures drive a presentation through its one writer, as a popup would (no flag writes), in one real window. */
internal fun GlassPopupFlight.testRun(vararg events: PopupEvent, window: Window = testWindow) =
    apply { open(SamplingBasis(null, Rect.Zero, null, false)); testStep(*events, window = window) }
internal fun GlassPopupFlight.testStep(vararg events: PopupEvent, window: Window = state.window ?: testWindow) = apply { events.forEach { advance(state.gen, it, window) } }
/** Hands this presentation to its home (Taken), whatever it was doing; repeated calls keep it Taken. */
internal fun GlassPopupFlight.testHandOff() = apply { if (!handedOff) testRun(PopupEvent.Admitted, PopupEvent.OfferReady, PopupEvent.OfferTaken) }

/** A real dialog window for window receipts; [newTestWindow] gives a distinct one. */
internal val testWindow: Window by lazy { newTestWindow() }
internal fun newTestWindow(): Window {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    fun create() = checkNotNull(Dialog(instrumentation.targetContext).window)
    if (Looper.myLooper() == Looper.getMainLooper()) return create()
    var window: Window? = null
    instrumentation.runOnMainSync { window = create() }
    return checkNotNull(window)
}
