package io.github.anastaci1a.localica

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.*
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
                Column { AsyncSection(value) { result ->
                    AsyncSectionRow(0) { Text(result) }
                    AsyncSectionRow(1) { Text("second") }
                } }
            }
        } }
        compose.onNodeWithTag("async-section-loading").assertExists()
        compose.runOnIdle { value = "first" }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithTag("async-section-loading").assertExists()
        compose.onNodeWithText("first").assertDoesNotExist()
        compose.mainClock.advanceTimeBy(2400)
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
        val before = compose.onNodeWithTag("popup-value").getUnclippedBoundsInRoot().height
        compose.runOnIdle { value = "75,000" }
        assertEquals(before, compose.onNodeWithTag("popup-value").getUnclippedBoundsInRoot().height)
    }
}
