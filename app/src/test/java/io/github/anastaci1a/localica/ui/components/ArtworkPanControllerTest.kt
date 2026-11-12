package io.github.anastaci1a.localica.ui.components

import androidx.compose.ui.geometry.Offset
import io.github.anastaci1a.localica.ui.engine.BrowsePinchProgressOwner
import io.github.anastaci1a.localica.ui.engine.EngineHarness
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class ArtworkPanControllerTest {
    @Test fun panUsesTheExistingExactIntegratorAndStopsItsRootLifetimeOnRegrab() {
        EngineHarness().use { h ->
            val owner = BrowsePinchProgressOwner().also { it.bind(h.clock, Dispatchers.Unconfined) }
            val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
            val bounds = ArtworkPanBounds(100f, 60f)
            var value = ArtworkTransform(2f)
            var writes = 0
            val controller = ArtworkPanController(owner, scope, { value }, { bounds }, { value = it; writes++ })
            controller.release(Offset(500f, 250f), false, 1f)
            assertEquals(1, h.clock.activeCount)
            h.advance(.01); assertEquals(0, writes)
            h.advance(.1)
            val expected = advanceArtworkPan(ArtworkPanMotion(Offset.Zero, Offset(500f, 250f)), bounds, .1f, 1f)
            assertEquals(expected.offset, value.offset)
            assertEquals(1, writes)
            controller.stop(); assertEquals(0, h.clock.activeCount)
            h.advance(.1); assertEquals(1, writes)
            controller.release(Offset(-500f, 0f), false, 1f)
            assertEquals(1, h.clock.activeCount)
            controller.stop(); scope.cancel()
            assertEquals(0, h.clock.activeCount)
        }
    }
    @Test fun reducedUnitScaleAndBlockedEdgesDoNotAdmitAClock() {
        EngineHarness().use { h ->
            val owner = BrowsePinchProgressOwner().also { it.bind(h.clock, Dispatchers.Unconfined) }
            val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
            var value = ArtworkTransform(2f, Offset(10f, 0f))
            val controller = ArtworkPanController(owner, scope, { value }, { ArtworkPanBounds(10f, 0f) }, { fail("No motion may present") })
            controller.release(Offset(500f, 0f), false, 1f)
            assertEquals(0, h.clock.activeCount)
            controller.release(Offset(-500f, 0f), true, 1f)
            assertEquals(0, h.clock.activeCount)
            value = ArtworkTransform()
            controller.release(Offset(500f, 0f), false, 1f)
            assertEquals(0, h.clock.activeCount); scope.cancel()
        }
    }
}
