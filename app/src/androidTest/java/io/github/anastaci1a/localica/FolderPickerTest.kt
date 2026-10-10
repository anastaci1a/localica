package io.github.anastaci1a.localica

import android.util.Log
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import io.github.anastaci1a.localica.library.AndroidDocuments
import io.github.anastaci1a.localica.library.TreeScanner
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.util.regex.Pattern

@RunWith(AndroidJUnit4::class)
class FolderPickerTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun selectedTreeScansSubfoldersAndPreservesPermission() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val device = UiDevice.getInstance(instrumentation)
        compose.onNodeWithText("Library").performClick()
        if (compose.onAllNodesWithText("Reset view").fetchSemanticsNodes().isNotEmpty()) {
            compose.onNodeWithTag("library-list").performScrollToNode(hasText("Reset view"))
            compose.onNodeWithText("Reset view").performClick()
        }
        // Real picker tests must also be repeatable after manual library checks on this device.
        val repository = (context.applicationContext as LocalicaApplication).library
        runBlocking { repository.snapshots.first().folders.forEach { repository.removeFolder(it.uri) } }
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.waitUntil(10000) {
            compose.onAllNodes(hasText("Choose folder") or hasText("Add folder"))
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNode(hasText("Choose folder") or hasText("Add folder")).performClick()
        // DocumentsUI can use either the Android or Google package depending on the image.
        if (!device.wait(Until.hasObject(By.text("Localica test library")), 2000)) {
            device.clickWhenReady(By.desc("Show roots"))
        }
        device.clickWhenReady(By.text("Localica test library"))
        device.clickWhenReady(By.text(Pattern.compile("use this folder|select", Pattern.CASE_INSENSITIVE)))
        // Older pickers grant directly; newer versions show a second confirmation dialog.
        device.wait(Until.findObject(By.text(Pattern.compile("allow", Pattern.CASE_INSENSITIVE))), 2000)?.click()
        val tree = "content://io.github.anastaci1a.localica.test.documents/tree/root"
        await { context.contentResolver.persistedUriPermissions.any { it.uri.toString() == tree && it.isReadPermission } }
        device.pressBack() // close settings
        compose.onNodeWithText("Library").performClick()
        compose.waitUntil(10000) { compose.onAllNodesWithText("Morning tone").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("library-list").performScrollToNode(hasText("2 songs · Test tones"))
        compose.onNodeWithText("2 songs · Test tones").assertIsDisplayed()
        compose.onNodeWithTag("library-list").performScrollToNode(hasText("Quiet interval"))
        compose.onNodeWithText("Quiet interval").assertIsDisplayed()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("library-list").performScrollToNode(hasText("Morning tone"))
        compose.onNodeWithText("Morning tone").assertIsDisplayed()
        val scan = runBlocking { TreeScanner(AndroidDocuments(context)).scan(tree) }
        assertEquals(2, scan.tracks.size)
        assertEquals("nested/tone.m4a", scan.tracks.last().relativePath)
        compose.onNodeWithText("Morning tone").performClick()
        compose.waitUntil(10000) { compose.onAllNodesWithContentDescription("Pause").fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(10000) { compose.onAllNodesWithText("Track finished").fetchSemanticsNodes().isNotEmpty() }
        // The library tap captures the displayed list, then automatically advances to its second song.
        compose.onNodeWithText("Quiet interval").assertIsDisplayed()
    }
}

private fun UiDevice.clickWhenReady(selector: BySelector) {
    waitForIdle()
    try {
        await(timeoutMs = 30000) {
            try {
                // The API 36 emulator can show either system-process ANR over DocumentsUI.
                // Keep the system process alive and continue testing the real picker flow.
                if (findObject(By.text("System UI isn't responding")) != null ||
                    findObject(By.text("Process system isn't responding")) != null) {
                    findObject(By.text("Wait"))?.click()
                    return@await false
                }
                val node = findObject(selector)
                if (node != null && node.isEnabled) { node.click(); true } else false
            } catch (_: StaleObjectException) { false }
        }
    } catch (failure: AssertionError) {
        val hierarchy = ByteArrayOutputStream().also { dumpWindowHierarchy(it) }.toString("UTF-8")
        hierarchy.chunked(3000).forEach { Log.e("FolderPickerTest", it) }
        throw AssertionError("Could not click $selector; UI hierarchy is in logcat", failure)
    }
}
