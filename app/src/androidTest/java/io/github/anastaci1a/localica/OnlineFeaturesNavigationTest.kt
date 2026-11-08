package io.github.anastaci1a.localica

import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import kotlinx.coroutines.test.StandardTestDispatcher
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Real notification intents enter the same settings popup on cold and warm launches. */
@OptIn(ExperimentalTestApi::class)
class OnlineFeaturesNavigationTest {
    @get:Rule val compose = createEmptyComposeRule(effectContext = StandardTestDispatcher())
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun intent(open: Boolean) = Intent(context, MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        .putExtra("localica.openLastFmSettings", open)
    private fun lastFm() {
        compose.waitUntil(10000) { compose.onAllNodesWithTag("lastfm-username").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("lastfm-username").assertExists()
        compose.onNodeWithContentDescription("Search settings input").assertExists()
    }
    @Test fun coldNotificationConsumesItsExtraAndOpensLastFm() {
        ActivityScenario.launch<MainActivity>(intent(true)).use { scenario ->
            lastFm()
            scenario.onActivity { assertFalse(it.intent.hasExtra("localica.openLastFmSettings")) }
        }
    }
    @Test fun zeroIsNeutralAndWarmRepeatedNotificationsRerouteTheOpenPopup() {
        ActivityScenario.launch<MainActivity>(intent(false)).use { scenario ->
            compose.waitForIdle()
            compose.onNodeWithTag("lastfm-username").assertDoesNotExist()
            compose.onNodeWithContentDescription("Search settings input").assertDoesNotExist()
            context.startActivity(intent(true))
            lastFm()
            scenario.onActivity { assertFalse(it.intent.hasExtra("localica.openLastFmSettings")) }
            compose.onNodeWithContentDescription("Search settings input").performTextReplacement("Photo cache size")
            compose.onNodeWithTag("search-result:Photo cache size").performClick()
            compose.onNodeWithTag("setting:Photo cache size").assertIsDisplayed()
            compose.onNodeWithTag("lastfm-username").assertDoesNotExist()
            context.startActivity(intent(true))
            lastFm()
            scenario.onActivity { assertFalse(it.intent.hasExtra("localica.openLastFmSettings")) }
        }
    }
}
