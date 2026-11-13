package io.github.anastaci1a.localica

import android.net.Uri
import androidx.compose.ui.test.*
import io.github.anastaci1a.localica.localicaAndroidComposeRule as createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import io.github.anastaci1a.localica.library.AndroidDocuments
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import java.util.regex.Pattern

class TagEditorFlowTest {
    private val compose = createAndroidComposeRule<MainActivity>()
    // Isolate the real app's default tabs and browse filters (#722).
    @get:Rule val rules: RuleChain = RuleChain.outerRule(BrowsePreferenceIsolation(
        listOf("interface", "browse-style", "library-query", "library-filters"))).around(compose)
    @Test fun editThroughPickerVerifiesFileAndRefreshesLibrary() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val app = context.applicationContext as LocalicaApplication
        val device = UiDevice.getInstance(instrumentation)
        val tree = "content://io.github.anastaci1a.localica.test.documents/tree/root"
        val uri = Uri.parse("$tree/document/tone.mp3")
        fun selectFolder() {
            if (!device.wait(Until.hasObject(By.text("Localica test library")), 2000)) device.clickWhenReady(By.desc("Show roots"))
            device.clickWhenReady(By.text("Localica test library"))
            device.clickWhenReady(By.text(Pattern.compile("use this folder|select", Pattern.CASE_INSENSITIVE)))
            device.wait(Until.findObject(By.text(Pattern.compile("allow", Pattern.CASE_INSENSITIVE))), 2000)?.click()
        }
        fun receipt() = app.tagEditing.state.value.run {
            "phase=$phase busy=$busy batch=$batchId saved=$saved interrupted=$interruptedBatch error=$error space=$spaceProblem results=$results"
        }
        fun awaitEditing(timeout: Long, condition: (io.github.anastaci1a.localica.editing.TagEditorState) -> Boolean) = try {
            compose.waitUntil(timeout) { condition(app.tagEditing.state.value) }
        } catch (failure: androidx.compose.ui.test.ComposeTimeoutException) {
            throw AssertionError("Tag editing did not settle: ${receipt()}", failure)
        }
        compose.nativePointer(compose.onNodeWithContentDescription("More"))
        compose.nativePointer(compose.onPopupAction(hasText("Settings")))
        compose.nativePointer(compose.onPopupAction(hasText("Library and tags")))
        compose.nativePointer(compose.onPopupAction(hasText("Folders to scan")))
        compose.nativePointer(compose.onPopupAction(hasText("Choose folder") or hasText("Add folder")).performScrollTo())
        selectFolder()
        compose.waitUntil(15000) { app.library.hasAccess(io.github.anastaci1a.localica.library.FolderSelection(tree, "Test")) }
        device.wait(Until.hasObject(By.pkg(context.packageName)), 10000)
        device.waitForIdle()
        compose.nativePointer(compose.onPopupAction(hasContentDescription("Close settings")))
        compose.nativePointer(compose.onNodeWithContentDescription("More"))
        compose.nativePointer(compose.onPopupAction(hasText("Scan music")))
        compose.nativePointer(compose.onPopupAction(hasText("Full scan")).assertIsEnabled())
        compose.waitUntil(15000) { compose.onAllNodesWithText("Scan complete.").fetchSemanticsNodes().isNotEmpty() }
        compose.nativePointer(compose.onPopupAction(hasText("Close")))
        compose.waitUntil(10000) { compose.onAllNodesWithText("Retrace tab history").fetchSemanticsNodes().isEmpty() }
        compose.nativePointer(compose.onNodeWithContentDescription("Library"))
        compose.nativePointer(compose.onNodeWithText("Songs"))
        val inList = hasAnyAncestor(hasTestTag("browser-list"))
        compose.onNodeWithTag("browser-list").performScrollToNode(hasText("Morning tone"))
        compose.waitUntil(15000) { compose.onAllNodes(hasText("Morning tone") and inList).fetchSemanticsNodes().isNotEmpty() }
        val original = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
        try {
            compose.nativePointer(compose.onNode(hasText("Morning tone") and inList), holdMillis = 800)
            compose.nativePointer(compose.onPopupAction(hasText("Edit tags")))
            compose.onPopupAction(hasSetTextAction() and hasText("Morning tone")).performScrollTo().performTextReplacement("Retagged tone")
            compose.awaitSearchImeHandoff(editor = compose.onNode(hasSetTextAction() and isFocused())) { pressSystemBack() }
            compose.nativePointer(compose.onPopupAction(hasText("Grant write access: root")).performScrollTo())
            selectFolder()
            compose.nativePointer(compose.onPopupAction(hasText("Review changes")))
            compose.nativePointer(compose.onPopupAction(hasText("Save changes")))
            awaitEditing(30000) { !it.busy && it.results.isNotEmpty() }
            assertTrue(receipt(), app.tagEditing.state.value.results.single().saved)
            assertEquals(receipt(), "AwaitingDecision", app.tagEditing.state.value.phase)
            assertEquals("Retagged tone", runBlocking { AndroidDocuments(context).metadata(uri.toString()).title })
            // A finished foreground save waits on its progress popup (#700); Done commits it and closes the editor.
            val edited = app.tagEditing.state.value.tracks
            compose.nativePointer(compose.onPopupAction(hasText("Done") and hasClickAction()))
            awaitEditing(10000) { it.batchId == null && it.tracks.isEmpty() }
            assertEquals(receipt(), "Complete", app.tagEditing.state.value.jobs.first().status)
            // A second verified write remains alive with no resumed Activity.
            var completed: Boolean? = null
            compose.activityRule.scenario.onActivity {
                app.tagEditing.open(edited) { completed = it }
                app.tagEditing.save(io.github.anastaci1a.localica.editing.TagPatch(mapOf(
                    io.github.anastaci1a.localica.editing.EditField.Title to "Retagged tone")))
            }
            // Background acceptance needs the admitted batch; it then commits without a Done decision.
            awaitEditing(30000) { it.batchId != null || (!it.busy && it.error != null) }
            compose.activityRule.scenario.onActivity { app.tagEditing.continueInBackground() }
            compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
            runBlocking { kotlinx.coroutines.withTimeout(30000) {
                app.tagEditing.state.first { !it.busy && it.batchId == null }
            } }
            assertEquals(receipt(), true, completed)
            assertEquals(receipt(), "Complete", app.tagEditing.state.value.jobs.first().status)
            assertEquals("Retagged tone", runBlocking { AndroidDocuments(context).metadata(uri.toString()).title })
            compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
            compose.activityRule.scenario.recreate()
            compose.activityRule.scenario.onActivity { app.tagEditing.close() }
            compose.onNodeWithTag("browser-list").performScrollToNode(hasText("Retagged tone"))
            compose.onNode(hasText("Retagged tone") and inList).assertIsDisplayed()
            compose.onAllNodes(isToggleable() and hasAnyAncestor(hasTestTag("browser-list"))).assertCountEquals(0)
        } finally {
            context.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(original) }
            runBlocking { app.library.scan(tree) }
            app.tagEditing.close()
        }
    }
}
