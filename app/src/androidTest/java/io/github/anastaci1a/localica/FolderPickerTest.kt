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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.util.concurrent.CopyOnWriteArrayList
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
        // Tree pickers can omit non-directory entries; the fixture always has this child folder.
        device.openTestLibraryRoot(By.text("nested"))
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
        val inList = hasAnyAncestor(hasTestTag("browser-list"))
        compose.waitUntil(10000) { compose.onAllNodes(hasText("Morning tone") and inList).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("browser-list").performScrollToNode(hasText("Quiet interval"))
        compose.onNode(hasText("Quiet interval") and inList).assertIsDisplayed()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("browser-list").performScrollToNode(hasText("Morning tone"))
        compose.onNode(hasText("Morning tone") and inList).assertIsDisplayed()
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
        lateinit var playback: io.github.anastaci1a.localica.playback.PlaybackConnection
        val played = CopyOnWriteArrayList<String>()
        val observation = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        compose.runOnIdle {
            playback = io.github.anastaci1a.localica.playback.PlaybackConnection(context)
            // Both two-second fixtures can finish while Compose waits for a cold player
            // expansion to become idle. Observe playback before tapping, not a transient icon.
            observation.launch {
                playback.state.filter { it.isPlaying }.map { it.title }.distinctUntilChanged().collect { played += it }
            }
        }
        try {
            await { playback.state.value.connected }
            compose.onNode(hasText("Morning tone") and inList).performClick()
            await { playback.state.value.ended && playback.state.value.title == "Quiet interval" }
            assertEquals(listOf("Morning tone", "Quiet interval"), played.toList())
            assertEquals("Quiet interval", playback.state.value.title)
            compose.onNodeWithText("Track finished").assertDoesNotExist()
        } finally { compose.runOnIdle { observation.cancel(); playback.close() } }
        // The library tap captures the displayed list, then automatically advances to its second song.
        compose.waitUntil(5000) {
            runCatching { compose.onNodeWithTag("player-title", useUnmergedTree = true)
                .assertIsDisplayed().assert(hasAnyDescendant(hasText("Quiet interval"))) }.isSuccess
        }
        compose.onNodeWithContentDescription("Collapse player").performClick()
        compose.onNodeWithContentDescription("Library").performClick()
        compose.onNodeWithContentDescription("Add playlist").performClick()
        compose.onNodeWithText("Import playlist from file").performClick()
        device.openTestLibraryRoot(By.text("import.m3u8"))
        device.clickWhenReady(By.text("import.m3u8"))
        compose.waitUntil(15000) { compose.onAllNodesWithText("Import 3 songs").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Import 3 songs").performClick()
        await { app.playlists.state.value.playlists.any { it.name == "import" } }
        val imported = app.playlists.state.value.playlists.last { it.name == "import" }
        assertEquals(listOf("tone.mp3", "tone.m4a", "tone.mp3"), imported.entries.map { it.track.fileName })
        assertEquals(3, imported.entries.map { it.id }.distinct().size)

    }
}

/** A matching breadcrumb is not a drawer root. Verify navigation before selecting a file. */
private fun UiDevice.openTestLibraryRoot(expectedEntry: BySelector) {
    try {
        if (wait(Until.hasObject(expectedEntry), 2000)) return
        val roots = By.res(Pattern.compile("(?:com.android|com.google.android).documentsui:id/roots_list"))
        val rootRow = By.res("android:id/title").text("Localica test library").hasAncestor(roots)
        if (!hasObject(roots)) clickWhenReady(By.desc("Show roots"))
        await(timeoutMs = 30000) {
            try {
                findObject(rootRow)?.let { it.isEnabled && it.clickSystemAction() } == true
            } catch (_: StaleObjectException) { false }
        }
        assertTrue("Test-library navigation did not expose $expectedEntry",
            wait(Until.hasObject(expectedEntry), 30000))
    } catch (failure: AssertionError) {
        runCatching {
            val hierarchy = ByteArrayOutputStream().also { dumpWindowHierarchy(it) }.toString("UTF-8")
            hierarchy.chunked(3000).forEach { Log.e("FolderPickerTest", it) }
        }.exceptionOrNull()?.let(failure::addSuppressed)
        throw failure
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
