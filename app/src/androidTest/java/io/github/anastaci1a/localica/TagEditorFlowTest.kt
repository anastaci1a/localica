package io.github.anastaci1a.localica

import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import io.github.anastaci1a.localica.library.AndroidDocuments
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.regex.Pattern

class TagEditorFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
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
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.onNode(hasText("Choose folder") or hasText("Add folder")).performScrollTo().performClick()
        selectFolder()
        compose.waitUntil(15000) { app.library.hasAccess(io.github.anastaci1a.localica.library.FolderSelection(tree, "Test")) }
        device.pressBack()
        compose.onNodeWithContentDescription("Library").performClick()
        compose.onNodeWithText("Songs").performClick()
        compose.waitUntil(15000) { compose.onAllNodesWithText("Morning tone").fetchSemanticsNodes().isNotEmpty() }
        val original = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
        try {
            compose.onNodeWithText("Morning tone").performTouchInput { longClick() }
            compose.onNodeWithText("Edit tags").performClick()
            compose.onNode(hasSetTextAction() and hasText("Morning tone")).performTextReplacement("Retagged tone")
            compose.onNodeWithText("Grant write access: root").performScrollTo().performClick()
            selectFolder()
            compose.onNodeWithText("Review changes").performClick()
            compose.onNodeWithText("Save changes").performClick()
            compose.waitUntil(30000) { !app.tagEditing.state.value.busy && app.tagEditing.state.value.results.isNotEmpty() }
            assertTrue(app.tagEditing.state.value.results.toString(), app.tagEditing.state.value.results.single().saved)
            assertEquals("Retagged tone", runBlocking { AndroidDocuments(context).metadata(uri.toString()).title })
            compose.onNodeWithText("Close editor").performClick()
            compose.onNodeWithText("Retagged tone").assertIsDisplayed()
            compose.onAllNodes(isToggleable()).assertCountEquals(0)
        } finally {
            context.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(original) }
            runBlocking { app.library.scan(tree) }
            app.tagEditing.close()
        }
    }
}
