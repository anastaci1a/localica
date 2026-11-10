package io.github.anastaci1a.localica

import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import io.github.anastaci1a.localica.library.Track
import io.github.anastaci1a.localica.lyrics.*
import io.github.anastaci1a.localica.ui.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class PreparedLyricsSelectionTest {
    @get:Rule val compose = localicaComposeRule()

    @Test fun savedSourceIsPresentOnTheFirstComposition() = verify("saved")
    @Test fun legacyAliasIsPresentBeforeMigrationAndLaterChoiceWinsDurably() = verify("saved:plain")

    private fun verify(choice: String) = runBlocking {
        val app = ApplicationProvider.getApplicationContext<LocalicaApplication>()
        app.preferences.awaitReady()
        val preferences = app.preferences.require("lyric-sources")
        val track = Track("prepared-lyrics-$choice", "Saved source", fileName = "fixture.mp3", relativePath = "fixture.mp3")
        val sources = listOf(LyricSource("fallback", "Fallback", LrcParser.parse("Fallback text")),
            LyricSource("saved", "Saved", LrcParser.parse("[00:00.00]Saved text")))
        withContext(Dispatchers.IO) { check(preferences.edit().putString(track.id, choice).commit()) }
        compose.mainClock.autoAdvance = false
        try {
            compose.setContent { LocalicaTheme {
                LyricsPanel(track, 0, true, {}, Modifier.height(360.dp), result = LyricsResult(sources))
            } }
            compose.onNodeWithText("Saved text").assertExists()
            compose.onAllNodesWithText("Fallback text").assertCountEquals(0)
            compose.mainClock.advanceTimeByFrame()
            compose.runOnIdle {
                assertEquals("saved", preferences.getString(track.id, null))
                // Apply the next accepted choice synchronously, as the source menu does.
                preferences.edit().putString(track.id, "fallback").apply()
                assertEquals("fallback", preferences.getString(track.id, null))
            }
            withContext(Dispatchers.IO) {
                check(preferences.edit().commit())
                val xml = java.io.File(app.applicationInfo.dataDir, "shared_prefs/lyric-sources.xml").readText()
                assertTrue(xml.contains("name=" + '"' + track.id + '"' + ">fallback</string>"))
            }
        } finally {
            compose.mainClock.autoAdvance = true
            withContext(Dispatchers.IO) { check(preferences.edit().remove(track.id).commit()) }
        }
    }
}
