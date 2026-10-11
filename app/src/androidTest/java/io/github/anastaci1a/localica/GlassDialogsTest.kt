package io.github.anastaci1a.localica

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class GlassDialogsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun conditionalDialogRetainsItsClosingSurfaceAndCommitsTheLatestInputOnce() {
        var open by mutableStateOf(true)
        var saved = ""
        var saves = 0
        compose.setContent { LocalicaTheme {
            if (open) {
                var text by remember { mutableStateOf("") }
                GlassAlertDialog(onDismissRequest = { open = false }, title = { Text("New playlist") },
                    text = { OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth(), singleLine = true) },
                    confirmButton = { TextButton(onClick = { saved = text; saves++; open = false }) { Text("Create") } })
            }
        } }
        compose.onNode(hasSetTextAction()).performTextInput("Night music")
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText("Create").performClick()
        compose.mainClock.advanceTimeBy(96)
        assertEquals("Night music", saved)
        assertEquals(1, saves)
        compose.onNodeWithTag("glass-morph-surface").assertExists()
        compose.onNodeWithText("Create").assertDoesNotExist()
        compose.mainClock.advanceTimeBy(1200)
        compose.onNodeWithTag("glass-morph-surface").assertDoesNotExist()
    }

    @Test fun nestedDialogUsesItsParentsFinishedMaterialAndBackOnlyClosesTheTopWindow() {
        var parentOpen by mutableStateOf(true)
        var childOpen by mutableStateOf(false)
        lateinit var parentScene: FrostedBackdrop
        lateinit var childScene: FrostedBackdrop
        compose.setContent { LocalicaTheme { CompositionLocalProvider(LocalReducedMotion provides true) {
            if (parentOpen) GlassAlertDialog(onDismissRequest = { parentOpen = false }, title = { Text("Parent options") }, text = {
                parentScene = LocalModalBackdrop.current!!
                TextButton(onClick = { childOpen = true }) { Text("Choose source") }
                if (childOpen) GlassAlertDialog(onDismissRequest = { childOpen = false }, title = { Text("Source choices") }, text = {
                    childScene = LocalModalBackdrop.current!!
                    Text("Local files")
                }, confirmButton = { TextButton(onClick = { childOpen = false }) { Text("Done") } })
            }, confirmButton = { TextButton(onClick = { parentOpen = false }) { Text("Close options") } })
        } } }
        compose.onNodeWithText("Choose source").performClick()
        compose.onNodeWithText("Source choices").assertIsDisplayed()
        assertNotSame(parentScene, childScene)
        pressSystemBack()
        // The accessibility service dispatches Back asynchronously to the window.
        compose.waitUntil(5_000) { !childOpen }
        compose.onNodeWithText("Source choices").assertDoesNotExist()
        compose.onNodeWithText("Parent options").assertIsDisplayed()
        assertTrue(parentOpen)
        compose.onNodeWithText("Close options").performClick()
        compose.onAllNodesWithTag("glass-morph-surface").assertCountEquals(0)
    }
}
