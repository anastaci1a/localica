package io.github.anastaci1a.localica.ui.engine

import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.geometry.Rect
import io.github.anastaci1a.localica.ui.spec.StockUiSpec
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** Exercises the same suspending admission used before native publication in GlassMorphPopup. */
class DepartingHomeReceiptTest {
    private fun owner(h: EngineHarness): ExpandCollapse = ExpandCollapse(h.clock, StockUiSpec.expansion, false).also {
        it.nativeGate(0f)
        it.setBasis(ExpansionBasis(Rect(0f, 0f, 100f, 100f), Rect(0f, 0f, 800f, 900f), 1f))
        it.target(true)
        assertTrue(it.acknowledgeNativeGate(it.nativeGeneration))
        h.advance(2.0)
    }

    @Test(timeout = 10000) fun admittedClosingReceiptCannotReleaseNativeOwnershipWhileHeld() = runBlocking {
        EngineHarness().use { h ->
            val expansion = owner(h)
            expansion.target(false)
            h.advance(2.0)
            val generation = expansion.nativeGeneration
            val receipt = CompletableDeferred<Unit>()
            var nativeOwned = true
            val adapter = launch(Dispatchers.Unconfined) {
                receipt.await()
                if (expansion.awaitNativeGate(generation, false)) {
                    nativeOwned = false
                    assertTrue(expansion.acknowledgeNativeGate(generation))
                }
            }
            expansion.hold()
            receipt.complete(Unit)
            Snapshot.sendApplyNotifications()
            yield()
            assertTrue(nativeOwned)
            assertFalse(adapter.isCompleted)
            expansion.release()
            Snapshot.sendApplyNotifications()
            adapter.join()
            assertFalse(nativeOwned)
            assertTrue(expansion.retirementReady)
            expansion.dispose()
            assertEquals(0, h.clock.activeCount)
        }
    }

    @Test(timeout = 10000) fun heldOpeningReceiptCannotPublishAdmissionBeforeRelease() = runBlocking {
        EngineHarness().use { h ->
            val expansion = ExpandCollapse(h.clock, StockUiSpec.expansion, false)
            expansion.nativeGate(0f)
            expansion.setBasis(ExpansionBasis(Rect(0f, 0f, 100f, 100f), Rect(0f, 0f, 800f, 900f), 1f))
            expansion.target(true)
            val generation = expansion.nativeGeneration
            val receipt = CompletableDeferred<Unit>()
            var admitted = false
            val adapter = launch(Dispatchers.Unconfined) {
                receipt.await()
                if (expansion.awaitNativeGate(generation, true)) {
                    admitted = true
                    assertTrue(expansion.acknowledgeNativeGate(generation))
                }
            }
            expansion.hold()
            receipt.complete(Unit)
            Snapshot.sendApplyNotifications()
            yield()
            assertFalse(admitted)
            assertFalse(adapter.isCompleted)
            expansion.release()
            Snapshot.sendApplyNotifications()
            adapter.join()
            assertTrue(admitted)
            expansion.dispose()
            assertEquals(0, h.clock.activeCount)
        }
    }

    @Test(timeout = 10000) fun disposalInvalidatesAnAdmittedReceiptWithoutPublishingItsCleanup() = runBlocking {
        EngineHarness().use { h ->
            val expansion = owner(h)
            expansion.target(false)
            h.advance(2.0)
            val generation = expansion.nativeGeneration
            val receipt = CompletableDeferred<Unit>()
            var published = false
            val adapter = launch(Dispatchers.Unconfined) {
                receipt.await()
                if (expansion.awaitNativeGate(generation, false)) published = true
            }
            expansion.hold()
            receipt.complete(Unit)
            Snapshot.sendApplyNotifications()
            yield()
            assertFalse(adapter.isCompleted)
            expansion.dispose()
            Snapshot.sendApplyNotifications()
            adapter.join()
            assertFalse(published)
            assertEquals(0, h.clock.activeCount)
        }
    }

    @Test(timeout = 10000) fun reopenKeepsTheAdmittedReturnGateBeforeItsNextOpening() = runBlocking {
        EngineHarness().use { h ->
            val expansion = owner(h)
            expansion.target(false)
            h.advance(2.0)
            val generation = expansion.nativeGeneration
            val receipt = CompletableDeferred<Unit>()
            var returnPublished = false
            val adapter = launch(Dispatchers.Unconfined) {
                receipt.await()
                if (expansion.awaitNativeGate(generation, false)) {
                    returnPublished = true
                    assertTrue(expansion.acknowledgeNativeGate(generation))
                }
            }
            expansion.hold()
            expansion.target(true)
            receipt.complete(Unit)
            Snapshot.sendApplyNotifications()
            yield()
            assertFalse(returnPublished)
            expansion.release()
            Snapshot.sendApplyNotifications()
            adapter.join()
            assertTrue(returnPublished)
            assertTrue(expansion.expanded)
            assertEquals(true, expansion.nativeOpening)
            assertTrue(expansion.nativeGeneration > generation)
            assertTrue(expansion.acknowledgeNativeGate(expansion.nativeGeneration))
            h.advance(2.0)
            assertEquals(1f, expansion.phase.valueAt(h.clock.stampNow), 0f)
            expansion.dispose()
            assertEquals(0, h.clock.activeCount)
        }
    }
}
