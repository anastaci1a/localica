package io.github.anastaci1a.localica

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import io.github.anastaci1a.localica.library.Track
import io.github.anastaci1a.localica.lyrics.*
import io.github.anastaci1a.localica.ui.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Native menu and saveable-state coverage. The pure resolver tests cover the pre-effect selection. */
class PreparedLyricsSelectionTest {
    @get:Rule val compose = localicaComposeRule()

    @Test fun savedSourceSurvivesMenuSelectionRestorationAndTrackChanges() = verify("saved")
    @Test fun legacyMigrationCannotOverwriteTheLaterMenuChoiceOrAnotherTrack() = verify("saved:plain")

    private fun verify(choice: String) = runBlocking {
        val app = ApplicationProvider.getApplicationContext<LocalicaApplication>()
        app.preferences.awaitReady()
        val preferences = app.preferences.require("lyric-sources")
        val first = Track("prepared-lyrics-$choice", "First", fileName = "first.mp3", relativePath = "first.mp3")
        val second = first.copy(id = "prepared-other-$choice", uri = "file:///second.mp3")
        var track by mutableStateOf(first)
        var visible by mutableStateOf(false)
        val sources = listOf(LyricSource("fallback", "Fallback", LrcParser.parse("Fallback text")),
            LyricSource("saved", "Saved", LrcParser.parse("[00:00.00]Saved text")))
        withContext(Dispatchers.IO) {
            check(preferences.edit().putString(first.id, choice).putString(second.id, "saved").commit())
        }
        val restoration = StateRestorationTester(compose)
        try {
            restoration.setContent { LocalicaTheme {
                val anchor = rememberGlassAnchor()
                Column {
                    Box(Modifier.fillMaxWidth().height(48.dp).glassAnchor(anchor).clickable { visible = true }) {
                        Text("Open sources")
                    }
                    LyricsPanel(track, 0, true, {}, Modifier.fillMaxWidth().height(360.dp), result = LyricsResult(sources),
                        sourcesAnchor = anchor, sourcesVisible = visible, onSourcesDismiss = { visible = false })
                }
            } }
            compose.onNodeWithText("Saved text").assertIsDisplayed()
            compose.onAllNodesWithText("Fallback text").assertCountEquals(0)
            compose.onNodeWithText("Open sources").performClick()
            compose.onNodeWithText("Fallback").performClick()
            compose.onNodeWithText("Fallback text").assertIsDisplayed()
            compose.runOnIdle { assertEquals("fallback", preferences.getString(first.id, null)) }
            restoration.emulateSavedInstanceStateRestore()
            compose.onNodeWithText("Fallback text").assertIsDisplayed()
            // Leave a menu for the old track mounted, then change tracks before another click.
            compose.onNodeWithText("Open sources").performClick()
            compose.runOnIdle { track = second }
            compose.waitForIdle()
            compose.onNodeWithText("Saved text").assertIsDisplayed()
            compose.runOnIdle {
                assertEquals("saved", preferences.getString(second.id, null))
                track = first
            }
            compose.onNodeWithText("Fallback text").assertIsDisplayed()
            withContext(Dispatchers.IO) {
                check(preferences.edit().commit())
                val xml = java.io.File(app.applicationInfo.dataDir, "shared_prefs/lyric-sources.xml").readText()
                assertTrue(xml.contains("name=" + '"' + first.id + '"' + ">fallback</string>"))
                assertTrue(xml.contains("name=" + '"' + second.id + '"' + ">saved</string>"))
            }
        } finally {
            withContext(Dispatchers.IO) { check(preferences.edit().remove(first.id).remove(second.id).commit()) }
        }
    }

    @Test fun restorationAfterPlaybackChangedTracksDoesNotRestoreTheOldTracksMigration() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<LocalicaApplication>()
        app.preferences.awaitReady()
        val preferences = app.preferences.require("lyric-sources")
        val first = Track("prepared-restore-first", "First", fileName = "first.mp3", relativePath = "first.mp3")
        val second = first.copy(id = "prepared-restore-second", uri = "file:///second.mp3")
        val sources = listOf(LyricSource("fallback", "Fallback", LrcParser.parse("Fallback text")),
            LyricSource("saved", "Saved", LrcParser.parse("[00:00.00]Saved text")),
            LyricSource("other", "Other", LrcParser.parse("[00:00.00]Other text")))
        withContext(Dispatchers.IO) {
            check(preferences.edit().putString(first.id, "saved:plain").putString(second.id, "other").commit())
        }
        // A plain captured value does not recompose the old owner before the save/dispose boundary.
        var playbackChangedWhileAbsent = false
        val restoration = StateRestorationTester(compose)
        try {
            restoration.setContent { LocalicaTheme {
                LyricsPanel(if (playbackChangedWhileAbsent) second else first, 0, true, {},
                    Modifier.fillMaxWidth().height(360.dp), result = LyricsResult(sources))
            } }
            compose.onNodeWithText("Saved text").assertIsDisplayed()
            compose.runOnIdle { playbackChangedWhileAbsent = true }
            restoration.emulateSavedInstanceStateRestore()
            compose.onNodeWithText("Other text").assertIsDisplayed()
            compose.onAllNodesWithText("Fallback text").assertCountEquals(0)
            compose.onAllNodesWithText("Saved text").assertCountEquals(0)
            assertEquals("other", preferences.getString(second.id, null))
            assertEquals("saved", preferences.getString(first.id, null))
            withContext(Dispatchers.IO) {
                check(preferences.edit().commit())
                val xml = java.io.File(app.applicationInfo.dataDir, "shared_prefs/lyric-sources.xml").readText()
                assertTrue(xml.contains("name=" + '"' + first.id + '"' + ">saved</string>"))
                assertTrue(xml.contains("name=" + '"' + second.id + '"' + ">other</string>"))
            }
        } finally {
            withContext(Dispatchers.IO) { check(preferences.edit().remove(first.id).remove(second.id).commit()) }
        }
    }


    @Test fun legacyStringPayloadReloadsThePreparedChoiceAtTheActualSaverBoundary() =
        verifyRejectedPayload("legacy-source")
    @Test fun foreignTrackPayloadReloadsThePreparedChoiceAtTheActualSaverBoundary() =
        verifyRejectedPayload(listOf("another-track", "old-source"))
    @Test fun malformedPayloadReloadsThePreparedChoiceAtTheActualSaverBoundary() =
        verifyRejectedPayload(listOf("prepared-saver-boundary", 7))

    private fun verifyRejectedPayload(payload: Any) = runBlocking {
        val app = ApplicationProvider.getApplicationContext<LocalicaApplication>()
        app.preferences.awaitReady()
        val preferences = app.preferences.require("lyric-sources")
        val trackId = "prepared-saver-boundary"
        withContext(Dispatchers.IO) { check(preferences.edit().putString(trackId, "non-first-source").commit()) }
        var restoring = false
        val producer = androidx.compose.runtime.saveable.Saver<MutableState<String?>, Any>(
            save = { payload }, restore = { mutableStateOf("old-source") })
        val restoration = StateRestorationTester(compose)
        try {
            restoration.setContent { LocalicaTheme {
                val saver = if (restoring) lyricChoiceSaver(trackId) else producer
                val chosen = androidx.compose.runtime.saveable.rememberSaveable(saver = saver) {
                    mutableStateOf(if (restoring) preferences.getString(trackId, null) else "old-source")
                }
                Text(chosen.value.orEmpty())
            } }
            compose.onNodeWithText("old-source").assertIsDisplayed()
            compose.runOnIdle { restoring = true }
            restoration.emulateSavedInstanceStateRestore()
            compose.onNodeWithText("non-first-source").assertIsDisplayed()
            assertEquals("non-first-source", preferences.getString(trackId, null))
        } finally {
            withContext(Dispatchers.IO) { check(preferences.edit().remove(trackId).commit()) }
        }
    }

}
