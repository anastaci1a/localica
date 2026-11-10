package io.github.anastaci1a.localica

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class AsyncSectionTest {
    @get:Rule val compose = localicaComposeRule()
    private fun captureSection(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = nativeEvidenceDirectory("async-section", context.getExternalFilesDir(null))
        File(dir, "$name-api${android.os.Build.VERSION.SDK_INT}.png").outputStream().use { output ->
            assertTrue(compose.onNodeWithTag("async-scene").captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output))
        }
    }

    @Test fun supersededBackgroundRequestCannotOverwriteCurrentValue() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        var request by mutableStateOf("old")
        compose.setContent { LocalicaTheme {
            val input = request
            val result by rememberAsyncValue(input) {
                if (input == "old") { started.countDown(); check(release.await(10, TimeUnit.SECONDS)) }
                input
            }
            Text(result ?: "pending")
        } }
        try {
            assertTrue(started.await(5, TimeUnit.SECONDS))
            compose.runOnIdle { request = "new" }
            compose.waitUntil(5000) { compose.onAllNodesWithText("new").fetchSemanticsNodes().isNotEmpty() }
            release.countDown()
            compose.waitForIdle()
            compose.onNodeWithText("new").assertExists()
            compose.onNodeWithText("old").assertDoesNotExist()
        } finally { release.countDown() }
    }

    @Test fun loadingSpinnerFinishesBeforeRowsAndReducedMotionPreservesFinalState() {
        var value by mutableStateOf<String?>(null)
        var reduced by mutableStateOf(false)
        compose.mainClock.autoAdvance = false
        compose.setContent { LocalicaTheme {
            CompositionLocalProvider(LocalReducedMotion provides reduced) {
                Column(Modifier.testTag("async-scene")) { AsyncSection(value, rowCount = 2) { result ->
                    AsyncSectionRow(0) { Text(result) }
                    AsyncSectionRow(1) { Text("second") }
                } }
            }
        } }
        compose.onNodeWithTag("async-section-loading").assertExists()
        captureSection("01-pending")
        compose.runOnIdle { value = "first" }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithTag("async-section-loading").assertExists()
        compose.onNodeWithText("first").assertDoesNotExist()
        compose.mainClock.advanceTimeBy(110)
        captureSection("02-spinner-exit")
        compose.mainClock.advanceTimeBy(300)
        captureSection("03-wave-start")
        compose.mainClock.advanceTimeBy(70)
        captureSection("04-wave-stagger")
        compose.mainClock.advanceTimeBy(1920)
        captureSection("05-settled")
        compose.onNodeWithTag("async-section-loading").assertDoesNotExist()
        compose.onNodeWithText("first").assertExists()
        compose.onNodeWithText("second").assertExists()
        compose.runOnIdle { value = null; reduced = true }
        compose.mainClock.advanceTimeByFrame()
        compose.runOnIdle { value = "reduced" }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText("reduced").assertExists()
    }

    @Test fun sharedNumberPlaceholderKeepsTheSameLineHeightInSettingsAndPopup() {
        var value by mutableStateOf<String?>(null)
        compose.setContent { LocalicaTheme {
            Column {
                AsyncValueText(value, "888,888", Modifier.testTag("settings-value"))
                GlassAlertDialog(onDismissRequest = {}, title = { Text("Statistics") }, text = {
                    AsyncValueText(value, "888,888", Modifier.testTag("popup-value"))
                }, confirmButton = { Text("Close") })
            }
        } }
        val before = compose.onNodeWithTag("popup-value").getUnclippedBoundsInRoot()
        compose.runOnIdle { value = "75,000" }
        val after = compose.onNodeWithTag("popup-value").getUnclippedBoundsInRoot()
        assertEquals(before.bottom - before.top, after.bottom - after.top)
    }
    @Test fun settledTextMatchesOriginalPixelsAtNarrowWidthAndLargeFont() {
        val values = listOf(Long.MAX_VALUE.toString(), "15000 songs · 3908 artists · 10000 known albums")
        var index by mutableIntStateOf(0)
        var shared by mutableStateOf(false)
        var pending by mutableStateOf(false)
        compose.setContent { LocalicaTheme {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2.5f)) {
                Box(Modifier.width(132.dp).background(MaterialTheme.colorScheme.background).testTag("text-face")) {
                    val style = if (index == 0) MaterialTheme.typography.titleLarge else MaterialTheme.typography.bodySmall
                    if (shared) AsyncValueText(if (pending) null else values[index], values[index], style = style)
                    else Text(values[index], style = style)
                }
            }
        } }
        values.indices.forEach { sample ->
            compose.runOnIdle { index = sample; shared = false; pending = false }
            val before = compose.onNodeWithTag("text-face").captureToImage()
            val beforeBounds = compose.onNodeWithTag("text-face").getUnclippedBoundsInRoot()
            assertTrue("The fixture must wrap", beforeBounds.bottom - beforeBounds.top > 40.dp)
            compose.runOnIdle { shared = true }
            val after = compose.onNodeWithTag("text-face").captureToImage()
            assertEquals(before.width, after.width); assertEquals(before.height, after.height)
            val a = before.toPixelMap(); val b = after.toPixelMap()
            for (y in 0 until before.height) for (x in 0 until before.width) assertEquals("sample=$sample pixel=$x,$y", a[x, y], b[x, y])
            compose.runOnIdle { pending = true }
            assertEquals(beforeBounds, compose.onNodeWithTag("text-face").getUnclippedBoundsInRoot())
            compose.onAllNodesWithText(values[sample]).assertCountEquals(0)
        }
    }

    @Test fun revealedUndoRowAndFollowingRowKeepOriginalPixelsAndBounds() {
        var wrapped by mutableStateOf(false)
        compose.setContent { LocalicaTheme {
            Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).testTag("undo-face"),
                verticalArrangement = Arrangement.spacedBy(io.github.anastaci1a.localica.ui.spec.StockUiSpec.space.getValue(io.github.anastaci1a.localica.ui.spec.Space.Compact))) {
                val row: @Composable () -> Unit = {
                    SettingsLink("Artist — Song", "7 scrobbles · Chosen by you", modifier = Modifier.testTag("undo-link")) { }
                    GlassActionButton(AppIcon.ActionChevronLeft, "Undo match for Song", modifier = Modifier.testTag("undo-action")) { }
                }
                if (wrapped) AsyncSectionRow(0, row) else row()
                SettingsLink("Following row", "Following summary", modifier = Modifier.testTag("following-row")) { }
            }
        } }
        val beforeBounds = listOf("undo-link", "undo-action", "following-row").map { compose.onNodeWithTag(it).getUnclippedBoundsInRoot() }
        val before = compose.onNodeWithTag("undo-face").captureToImage()
        compose.runOnIdle { wrapped = true }
        assertEquals(beforeBounds, listOf("undo-link", "undo-action", "following-row").map { compose.onNodeWithTag(it).getUnclippedBoundsInRoot() })
        val after = compose.onNodeWithTag("undo-face").captureToImage()
        assertEquals(before.width, after.width); assertEquals(before.height, after.height)
        val a = before.toPixelMap(); val b = after.toPixelMap()
        for (y in 0 until before.height) for (x in 0 until before.width) assertEquals("pixel=$x,$y", a[x, y], b[x, y])
    }

}
