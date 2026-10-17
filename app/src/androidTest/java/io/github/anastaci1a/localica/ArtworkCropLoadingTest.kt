package io.github.anastaci1a.localica

import android.graphics.Bitmap
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import io.github.anastaci1a.localica.ui.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/** Controlled suspension tests the real crop owner, without timing a filesystem read. */
class ArtworkCropLoadingTest {
    @get:Rule val compose = createComposeRule()
    private val requests = CopyOnWriteArrayList<CompletableDeferred<Bitmap?>>()
    private val events = CopyOnWriteArrayList<CropLoadEvent>()
    private var uri by mutableStateOf("crop-fixture-a")
    private var visible by mutableStateOf(true)
    private val loader = CropImageLoader { _, _ ->
        val result = CompletableDeferred<Bitmap?>().also { requests += it }
        result.await()
    }

    private fun fixture(chosenLoader: CropImageLoader = loader): StateRestorationTester {
        val restore = StateRestorationTester(compose)
        restore.setContent { LocalicaTheme {
            CompositionLocalProvider(LocalCropImageLoader provides chosenLoader,
                LocalCropLoadObserver provides { events += it }) {
                if (visible) ArtworkCrop(uri, { visible = false }, { error("Unexpected crop commit") })
            }
        } }
        return restore
    }
    private fun awaitRequests(count: Int) = compose.waitUntil(10000) { requests.size == count }
    private fun ready(index: Int) {
        requests[index].complete(Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888))
        compose.waitUntil(10000) { compose.onAllNodesWithTag("artwork-crop").fetchSemanticsNodes().size == 1 }
        compose.onNodeWithText("Use square crop").assertIsEnabled()
    }

    @Test fun restorationCancelsPendingLoadAndStartsFreshGeneration() {
        val restore = fixture()
        awaitRequests(1)
        compose.onNodeWithContentDescription("Crop zoom").performSemanticsAction(SemanticsActions.SetProgress) { it(3f) }
        restore.emulateSavedInstanceStateRestore()
        awaitRequests(2)
        ready(1)
        compose.runOnIdle {
            val starts = events.filter { it.phase == CropLoadPhase.Started }
            assertEquals(2, starts.size)
            assertNotSame(starts[0].generation, starts[1].generation)
            assertTrue(events.any { it.generation === starts[0].generation && it.phase == CropLoadPhase.Cancelled })
            assertFalse(events.any { it.generation === starts[0].generation && it.phase == CropLoadPhase.Ready })
            assertFalse(events.any { it.phase == CropLoadPhase.Failed })
        }
        assertEquals(3f, compose.onNodeWithContentDescription("Crop zoom").fetchSemanticsNode()
            .config[SemanticsProperties.ProgressBarRangeInfo].current, .01f)
    }

    @Test fun repeatedReadyRestorationReloadsAndPreservesGeometry() {
        val restore = fixture()
        awaitRequests(1); ready(0)
        compose.onNodeWithContentDescription("Crop zoom").performSemanticsAction(SemanticsActions.SetProgress) { it(3f) }
        repeat(2) { index ->
            restore.emulateSavedInstanceStateRestore()
            awaitRequests(index + 2)
            compose.onNodeWithText("Use square crop").assertIsNotEnabled()
            ready(index + 1)
            assertEquals(3f, compose.onNodeWithContentDescription("Crop zoom").fetchSemanticsNode()
                .config[SemanticsProperties.ProgressBarRangeInfo].current, .01f)
        }
        compose.runOnIdle { assertEquals(3, events.count { it.phase == CropLoadPhase.Ready }) }
    }

    @Test fun lateCancelledSourceCannotPublishOverReplacement() {
        fixture(CropImageLoader { _, _ ->
            val result = CompletableDeferred<Bitmap?>().also { requests += it }
            withContext(NonCancellable) { result.await() }
        })
        awaitRequests(1)
        compose.runOnIdle { uri = "crop-fixture-b" }
        awaitRequests(2)
        ready(1)
        requests[0].complete(Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888))
        compose.waitUntil(10000) { events.any { it.phase == CropLoadPhase.Cancelled } }
        compose.runOnIdle {
            val first = events.first { it.phase == CropLoadPhase.Started }.generation
            assertFalse(events.any { it.generation === first && it.phase == CropLoadPhase.Ready })
            assertEquals(1, events.count { it.phase == CropLoadPhase.Ready })
        }
        compose.onNodeWithTag("artwork-crop").assertExists()
        compose.onNodeWithText("Use square crop").assertIsEnabled()
    }

    @Test fun replacingReadySourceHidesOldPixelsAndDisablesSaveUntilReady() {
        fixture(); awaitRequests(1); ready(0)
        compose.runOnIdle { uri = "crop-fixture-b" }
        awaitRequests(2)
        compose.onNodeWithTag("artwork-crop").assertDoesNotExist()
        compose.onNodeWithText("Use square crop").assertIsNotEnabled()
        ready(1)
    }

    @Test fun readFailureIsExplicitAndNewSourceCanLoad() {
        fixture(); awaitRequests(1)
        requests[0].completeExceptionally(java.io.IOException("Fixture read failed"))
        compose.waitUntil(10000) { events.any { it.phase == CropLoadPhase.Failed } }
        compose.onNodeWithText("Fixture read failed").assertIsDisplayed()
        compose.onNodeWithText("Use square crop").assertIsNotEnabled()
        compose.runOnIdle { uri = "crop-fixture-b" }
        awaitRequests(2)
        compose.onNodeWithText("Fixture read failed").assertDoesNotExist()
        ready(1)
    }

    @Test fun nullDecodeShowsErrorAndCannotSave() {
        fixture(); awaitRequests(1)
        requests[0].complete(null)
        compose.waitUntil(10000) { events.any { it.phase == CropLoadPhase.Failed } }
        compose.onNodeWithText("Couldn’t decode the image. Choose it again.").assertIsDisplayed()
        compose.onNodeWithTag("artwork-crop").assertDoesNotExist()
        compose.onNodeWithText("Use square crop").assertIsNotEnabled()
    }

    @Test fun dismissCancelsPendingLoadWithoutReportingFailure() {
        fixture(); awaitRequests(1)
        compose.onNodeWithText("Cancel").performClick()
        compose.waitUntil(10000) { events.any { it.phase == CropLoadPhase.Cancelled } }
        compose.runOnIdle {
            assertFalse(events.any { it.phase == CropLoadPhase.Failed || it.phase == CropLoadPhase.Ready })
            assertTrue(events.any { it.phase == CropLoadPhase.Disposed })
        }
    }
}
