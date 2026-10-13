package io.github.anastaci1a.localica

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class PlayerActionSlotsTest {
    @get:Rule val compose = createComposeRule()
    @Test fun onlyEmptySlotsOpenCustomizationAndAssignmentsSurviveLayoutChanges() {
        var settings by mutableStateOf(PlayerActionSettings())
        var opens = 0
        compose.setContent { LocalicaTheme { Box(Modifier.fillMaxWidth().height(96.dp)) {
            PlayerActionStrip(settings, { it.label }, {}, { opens++ })
        } } }
        compose.onNodeWithTag("player-slot-0:0").performTouchInput { longClick() }
        compose.runOnIdle { assertEquals(0, opens) }
        compose.onNodeWithTag("player-slot-0:4").performTouchInput { longClick() }
        compose.runOnIdle {
            assertEquals(1, opens)
            settings = settings.place(PlayerAction.Lyrics, ActionSlot(0, 4))
        }
        compose.onNodeWithTag("player-slot-0:4").performTouchInput { longClick() }
        compose.runOnIdle { assertEquals(1, opens) }
    }
    @Test fun dragFromPaletteFollowsPointerAndDropsIntoSlotWhileCancelKeepsAssignments() {
        var settings by mutableStateOf(PlayerActionSettings())
        compose.setContent { LocalicaTheme { PlayerActionCustomizer(settings, { settings = it }, {}) } }
        val palette = compose.onNodeWithTag("palette-Lyrics")
        val editor = compose.onNodeWithTag("player-action-editor")
        val origin = editor.fetchSemanticsNode().boundsInRoot.topLeft
        val start = palette.fetchSemanticsNode().boundsInRoot.center
        val target = compose.onNodeWithTag("custom-slot-0:4").fetchSemanticsNode().boundsInRoot.center
        editor.performTouchInput { down(start - origin); advanceEventTime(650); moveBy(Offset(0f, -20f)); advanceEventTime(32) }
        compose.onNodeWithTag("drag-player-action").assertExists()
        val before = compose.onNodeWithTag("drag-player-action").fetchSemanticsNode().boundsInRoot.center
        compose.mainClock.autoAdvance = false
        try {
            editor.performTouchInput { moveBy(Offset(0f, -30f)) }
            compose.mainClock.advanceTimeByFrame()
            val after = compose.onNodeWithTag("drag-player-action").fetchSemanticsNode().boundsInRoot.center
            assertEquals(-30f, after.y - before.y, 2f)
            editor.performTouchInput { moveTo(target - origin); up() }
        } finally { compose.mainClock.autoAdvance = true }
        compose.waitUntil(5000) { settings.slots[ActionSlot(0, 4)] == PlayerAction.Lyrics }
        compose.waitUntil(5000) { compose.onAllNodesWithTag("drag-player-action").fetchSemanticsNodes().isEmpty() }
        val saved = settings
        compose.onNodeWithTag("custom-slot-0:4").performTouchInput { down(center); advanceEventTime(650); moveBy(Offset(0f, 30f)); cancel() }
        compose.waitUntil(5000) { compose.onAllNodesWithTag("drag-player-action").fetchSemanticsNodes().isEmpty() }
        compose.runOnIdle { assertEquals(saved, settings) }
        compose.onNodeWithText("2 rows").performClick()
        compose.onNodeWithText("Small").performClick()
        compose.onNodeWithTag("custom-slot-1:2").performClick()
        compose.onNode(hasText("Artwork") and SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.Role, androidx.compose.ui.semantics.Role.RadioButton)).performClick()
        compose.runOnIdle { assertNull(settings.slots[ActionSlot(1, 2)]) }
        compose.onNodeWithText("Select", substring = false).performClick()
        compose.onNodeWithText("2 rows").performClick()
        compose.runOnIdle { assertEquals(PlayerAction.Artwork, settings.slots[ActionSlot(1, 2)]) }
    }
    @Test fun holdingRepeatOpensItsPopupAndAppliesAdvancedPolicy() {
        var open by mutableStateOf(false)
        var settings by mutableStateOf(io.github.anastaci1a.localica.queue.QueueSettings())
        var editorOpens = 0
        compose.setContent { LocalicaTheme {
            Box(Modifier.fillMaxWidth().height(96.dp)) {
                PlayerActionStrip(PlayerActionSettings(), { it.label }, {}, { editorOpens++ }, onRepeatOptions = { open = true })
            }
            if (open) RepeatOptionsDialog(settings, { open = false }) { settings = it }
        } }
        compose.onNodeWithTag("player-slot-0:7").performTouchInput { longClick() }
        compose.onNodeWithText("Repeat modes").assertIsDisplayed()
        compose.onNodeWithText("Advanced").performClick()
        compose.onNodeWithText("Load next song and pause").performClick()
        compose.onNodeWithText("Apply").performClick()
        compose.runOnIdle {
            assertEquals(0, editorOpens)
            assertEquals(io.github.anastaci1a.localica.queue.SongEnd.NextPaused, settings.advancedRepeat?.songEnd)
            assertFalse(open)
        }
    }
    @Test fun preferencesMigrateAllLegacyActionsAndKeepSparseAssignments() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = context.getSharedPreferences("player-actions", 0)
        val saved = PlayerActionPreferences(context).read()
        try {
            store.edit().clear().putString("actions", PlayerAction.entries.joinToString(",") { it.name }).putBoolean("centered", false).commit()
            val prefs = PlayerActionPreferences(context)
            val migrated = prefs.read()
            assertEquals(PlayerAction.entries.toSet(), migrated.slots.values.toSet())
            val sparse = migrated.remove(PlayerAction.Lyrics).place(PlayerAction.Lyrics, ActionSlot(1, 2))
            prefs.write(sparse)
            assertEquals(sparse, PlayerActionPreferences(context).read())
        } finally { store.edit().clear().commit(); PlayerActionPreferences(context).write(saved) }
    }
}
