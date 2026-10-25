package io.github.anastaci1a.localica

import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.queue.QueuePreferences
import io.github.anastaci1a.localica.queue.QueueSettings
import io.github.anastaci1a.localica.ui.LocalicaTheme
import io.github.anastaci1a.localica.ui.QueueSettingsControls
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.UUID

class BoundaryDeclickSettingsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun freshPreferencesDefaultOffAndRecreationPersistsOnlyExplicitChoice() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "boundary-declick-test-${UUID.randomUUID()}"
        val context = object : ContextWrapper(base) {
            override fun getSharedPreferences(ignored: String, mode: Int) = base.getSharedPreferences(name, mode)
        }
        try {
            val store = QueuePreferences(context)
            assertFalse(store.read().boundaryDeclick)
            val selected = store.read().copy(boundaryDeclick = true, preloadNext = false)
            store.write(selected)
            assertEquals(selected, QueuePreferences(context).read())
            store.write(store.read().copy(shuffle = true))
            assertTrue(QueuePreferences(context).read().boundaryDeclick)
            store.write(store.read().copy(boundaryDeclick = false))
            assertFalse(QueuePreferences(context).read().boundaryDeclick)
            assertFalse(store.read().preloadNext)
            assertTrue(store.read().shuffle)
        } finally {
            base.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
            base.deleteSharedPreferences(name)
        }
    }

    @Test fun accessibleSettingStartsOffAndPreservesOtherPlaybackChoices() {
        var settings by mutableStateOf(QueueSettings(preloadNext = false))
        compose.setContent { LocalicaTheme { Column {
            QueueSettingsControls(settings) { change -> settings = change(settings) }
        } } }
        val toggle = compose.onNodeWithText("Soften track boundary clicks")
        toggle.assertIsOff().performClick().assertIsOn()
        compose.runOnIdle { assertTrue(settings.boundaryDeclick); assertFalse(settings.preloadNext) }
        toggle.performClick().assertIsOff()
        compose.runOnIdle { assertFalse(settings.boundaryDeclick); assertFalse(settings.preloadNext) }
    }
}
