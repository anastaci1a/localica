package io.github.anastaci1a.localica

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import io.github.anastaci1a.localica.library.Track
import io.github.anastaci1a.localica.ui.*
import org.junit.Rule
import org.junit.Test

class SongInformationTest {
    @get:Rule val compose = createComposeRule()
    @Test fun artworkViewerReturnsToTheInformationSheet() {
        val track = Track("file:///missing.mp3", "Information song", fileName = "missing.mp3", relativePath = "missing.mp3")
        compose.setContent { LocalicaTheme { SongInformation(track, {}, {}, {}) } }
        compose.onNode(SemanticsMatcher("View artwork action") {
            it.config.getOrNull(SemanticsActions.OnClick)?.label == "View artwork"
        }).performClick()
        compose.onNodeWithContentDescription("Close artwork").assertIsDisplayed().performClick()
        compose.onNodeWithText("Information song").assertIsDisplayed()
    }
}
