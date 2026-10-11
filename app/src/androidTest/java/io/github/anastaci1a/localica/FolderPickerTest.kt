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
        compose.onNodeWithContentDescription("Library").performClick()
        // Real picker tests must also be repeatable after manual library checks on this device.
        val repository = (context.applicationContext as LocalicaApplication).library
        runBlocking { repository.snapshots.first().folders.forEach { repository.removeFolder(it.uri) } }
        compose.onNodeWithContentDescription("More").performClick()
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithText("Library and tags").performClick()
        compose.onNodeWithText("Folders to scan").performClick()
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
        compose.onNodeWithContentDescription("Close settings").performClick()
        compose.onNodeWithContentDescription("More").performClick()
        compose.onNodeWithText("Scan music").performClick()
        compose.onNodeWithText("Scan now").performClick()
        val app = context.applicationContext as LocalicaApplication
        await { app.scanner.state.value.folder != null }
        compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
        await(timeoutMs = 20000) { !app.scanner.state.value.running }
        assertEquals("Scan complete.", app.scanner.state.value.message)
        compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
        compose.waitUntil(15000) { compose.onAllNodesWithText("Scan complete.").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Close").performClick()
        compose.onNodeWithContentDescription("Library").performClick()
        compose.onNodeWithText("Songs").performClick()
        compose.waitUntil(10000) { compose.onAllNodesWithText("Morning tone").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("browser-list").performScrollToNode(hasText("Quiet interval"))
        compose.onNodeWithText("Quiet interval").assertIsDisplayed()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("browser-list").performScrollToNode(hasText("Morning tone"))
        compose.onNodeWithText("Morning tone").assertIsDisplayed()
        val scan = runBlocking { TreeScanner(AndroidDocuments(context)).scan(tree) }
        assertEquals(2, scan.tracks.size)
        assertEquals("nested/tone.m4a", scan.tracks.last().relativePath)
        val indexed = runBlocking { repository.snapshots.first().tracks }
        compose.runOnIdle { app.scanner.start(listOf(tree)) }
        await { app.scanner.state.value.folder != null }
        compose.runOnIdle { app.scanner.cancel() }
        await { !app.scanner.state.value.running }
        assertEquals(indexed, runBlocking { repository.snapshots.first().tracks })
        // These descriptors belong to the provider's separate UID; filesystem permission
        // probes/reopening descriptor paths must not replace the granted read capability.
        for (extension in listOf("mp3", "m4a", "flac", "ogg", "opus")) {
            val uri = android.provider.DocumentsContract.buildDocumentUriUsingTree(android.net.Uri.parse(tree), "tagged.$extension").toString()
            val metadata = runBlocking { AndroidDocuments(context).metadata(uri, "tagged.$extension") }
            assertEquals(extension, "Personal notes", metadata.comment)
            assertEquals(extension, "Album artist", metadata.albumArtist)
        }
        val embeddedUri = android.provider.DocumentsContract.buildDocumentUriUsingTree(android.net.Uri.parse(tree), "provider-lyrics.mp3").toString()
        val embedded = runBlocking { app.lyrics.load(io.github.anastaci1a.localica.library.Track(embeddedUri, "Provider lyrics",
            fileName = "provider-lyrics.mp3", relativePath = "provider-lyrics.mp3"), 0) }
        assertEquals(embedded.toString(), "Through a granted folder", embedded.sources.first().document.lines.first().text)
        assertTrue(embedded.sources.first().document.synced)
        assertTrue(java.io.File(context.cacheDir, "tag-views").listFiles().orEmpty().isEmpty())
        val lyrics = runBlocking { (context.applicationContext as LocalicaApplication).lyrics.load(indexed.first { it.fileName == "tone.mp3" }, 0) }
        assertTrue(lyrics.toString(), lyrics.sources.first().sidecar)
        assertTrue(lyrics.sources.first().document.synced)
        val nestedLyrics = runBlocking { (context.applicationContext as LocalicaApplication).lyrics.load(indexed.first { it.fileName == "tone.m4a" }, 0) }
        assertTrue(nestedLyrics.sources.none { it.sidecar })
        compose.onNodeWithText("Morning tone").performClick()
        compose.waitUntil(10000) { compose.onAllNodesWithContentDescription("Pause").fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(10000) { compose.onAllNodesWithText("Track finished").fetchSemanticsNodes().isNotEmpty() }
        // The library tap captures the displayed list, then automatically advances to its second song.
        compose.onNodeWithText("Quiet interval").assertIsDisplayed()
        compose.onNodeWithContentDescription("Collapse player").performClick()
        compose.onNodeWithContentDescription("Library").performClick()
        compose.onNodeWithContentDescription("Add playlist").performClick()
        compose.onNodeWithText("Import playlist from file").performClick()
        if (!device.wait(Until.hasObject(By.text("import.m3u8")), 2000)) {
            device.clickWhenReady(By.desc("Show roots"))
            device.clickWhenReady(By.text("Localica test library"))
        }
        device.clickWhenReady(By.text("import.m3u8"))
        compose.waitUntil(15000) { compose.onAllNodesWithText("Import 3 songs").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Import 3 songs").performClick()
        await { app.playlists.state.value.playlists.any { it.name == "import" } }
        val imported = app.playlists.state.value.playlists.last { it.name == "import" }
        assertEquals(listOf("tone.mp3", "tone.m4a", "tone.mp3"), imported.entries.map { it.track.fileName })
        assertEquals(3, imported.entries.map { it.id }.distinct().size)

    }
}

internal fun UiDevice.clickWhenReady(selector: BySelector) {
    waitForIdle()
    try {
        await(timeoutMs = 30000) {
            try {
                // The API 36 emulator can show a system/launcher ANR over DocumentsUI.
                // Keep the system process alive and continue testing the real picker flow.
                if (findObject(By.text("System UI isn't responding")) != null ||
                    findObject(By.text("Process system isn't responding")) != null ||
                    findObject(By.text("Quickstep isn't responding")) != null) {
                    findObject(By.text("Wait"))?.click()
                    return@await false
                }
                val node = findObject(selector)
                if (node != null && node.isEnabled) {
                    // Old DocumentsUI file tiles expose no click action; their stable content
                    // still needs a touch. Root rows and toolbar buttons use accessibility.
                    if (!node.clickSystemAction()) node.click()
                    true
                } else false
            } catch (_: StaleObjectException) { false }
        }
    } catch (failure: AssertionError) {
        val hierarchy = ByteArrayOutputStream().also { dumpWindowHierarchy(it) }.toString("UTF-8")
        hierarchy.chunked(3000).forEach { Log.e("FolderPickerTest", it) }
        throw AssertionError("Could not click $selector; UI hierarchy is in logcat", failure)
    }
}
