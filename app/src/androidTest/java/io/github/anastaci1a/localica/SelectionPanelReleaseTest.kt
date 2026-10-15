package io.github.anastaci1a.localica

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.library.SongRow
import io.github.anastaci1a.localica.library.Track
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class SelectionPanelReleaseTest {
    @get:Rule val compose = createComposeRule()
    private val session = SessionSelection()
    private val row = SongRow("entry", Track("song", "Song", fileName = "song.mp3", relativePath = "song.mp3"))

    @Test fun initiatingPointerOwnsReleaseAndAvailabilityChangesDoNotReleaseIt() {
        val enabled = mutableStateOf(true)
        compose.setContent {
            Box(Modifier.size(200.dp).testTag("host")
                .selectionPanelRelease(session, "route", enabled.value) { true })
        }
        val host = compose.onNodeWithTag("host")
        var held = false
        try {
            host.performTouchInput { down(0, Offset(40f, 40f)); down(1, Offset(80f, 80f)); held = true }
            compose.runOnIdle { session.select(null, listOf(row)); enabled.value = false }
            compose.runOnIdle { assertFalse(session.panelVisible) }
            host.performTouchInput { up(0) }
            compose.runOnIdle { assertTrue(session.panelVisible) }
            host.performTouchInput { up(1); held = false }
        } finally { if (held) host.performTouchInput { cancel() } }
    }

    @Test fun routeDisposalRestoresRetainedSelectionAndForeignDownDoesNotDelayIt() {
        val owner = mutableStateOf("first")
        compose.setContent {
            Box(Modifier.size(200.dp).testTag("host")
                .selectionPanelRelease(session, owner.value, true) { it.x < 100f })
        }
        val host = compose.onNodeWithTag("host")
        var held = false
        try {
            host.performTouchInput { down(Offset(40f, 40f)); held = true }
            compose.runOnIdle { session.select(null, listOf(row)); assertFalse(session.panelVisible); owner.value = "second" }
            compose.runOnIdle { assertTrue(session.panelVisible); assertEquals(listOf(row), session.entries.map { it.row }); session.clear() }
            host.performTouchInput { cancel(); held = false }
            host.performTouchInput { down(Offset(150f, 40f)); held = true }
            compose.runOnIdle { session.select(null, listOf(row)); assertTrue(session.panelVisible) }
            host.performTouchInput { up(); held = false }
        } finally { if (held) host.performTouchInput { cancel() } }
    }
}
