package io.github.anastaci1a.localica

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.library.Track
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain

/** Uses the Activity's production root, without the fixture EngineRecord wrapper. */
class TagEditorRootContextTest {
    private val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(BrowsePreferenceIsolation(
        listOf("interface", "browse-style", "library-query", "library-filters"))).around(compose)
    private val controller get() = (InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        as LocalicaApplication).tagEditing

    @After fun closeEditor() {
        compose.runOnIdle { controller.close(); controller.hideJobs() }
    }

    @Test fun activityRootProvidesEngineAndSpecToEditorAndJobsOutsideScreen() {
        compose.runOnIdle {
            assertFalse("The root regression must not interrupt a tag write", controller.state.value.busy)
            controller.open(listOf(Track("root-context:missing", "Root context song")))
        }
        compose.onNodeWithText("Edit 1 song").assertIsDisplayed()
        compose.onNodeWithText("Close editor").performClick()
        compose.runOnIdle { controller.showJobs() }
        compose.onNodeWithText("Tag editing jobs").assertIsDisplayed()
        compose.onNodeWithText("Close").performClick()
        compose.activityRule.scenario.recreate()
        compose.runOnIdle { controller.showJobs() }
        compose.onNodeWithText("Tag editing jobs").assertIsDisplayed()
    }
}
