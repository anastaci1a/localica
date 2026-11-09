package io.github.anastaci1a.localica

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.editing.*
import io.github.anastaci1a.localica.library.Track
import io.github.anastaci1a.localica.ui.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.io.InputStream
import java.util.UUID

/** Real editor/controller at the accepted service-start boundary; no provider write is permitted. */
class TagSaveProgressPopupTest {
    @get:Rule val compose = localicaComposeRule()
    private val target get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val app get() = target.applicationContext as LocalicaApplication
    private lateinit var owned: HeldTagServiceContext
    private lateinit var controller: TagEditingController
    private val tracks = (1..2).map { Track("popup-proof:$it", "Before name", fileName = "$it.mp3", relativePath = "$it.mp3") }

    @Before fun prepare() {
        owned = HeldTagServiceContext(target)
        val documents = object : EditableDocuments {
            override fun writable(track: Track): String? = null
            override fun open(uri: String): InputStream = error("Popup fixture must not read audio")
            override fun replace(uri: String, source: File): Unit = error("Popup fixture must not write audio")
        }
        compose.runOnIdle {
            controller = TagEditingController(owned, app.library, app.queues, app.playlists,
                RecoverableTagWriter(File(owned.filesDir, "tag-recovery"), documents))
        }
        compose.setContent { LocalicaTheme {
            val backdrop = rememberFrostedBackdrop(Unit, dark = false)
            Box(Modifier.fillMaxSize()) {
                Canvas(Modifier.matchParentSize().recordFrostedBackdrop(backdrop)) { drawRect(Color.White) }
                CompositionLocalProvider(LocalFrostedBackdrop provides backdrop, LocalModalBackdrop provides backdrop,
                    LocalReducedMotion provides true) {
                    TagEditorHost(controller) {
                        ActionTextButton(onClick = { controller.open(tracks) }) { Text("Open test editor") }
                    }
                }
            }
        } }
    }

    private fun submit() {
        compose.onNodeWithText("Open test editor").performClick()
        compose.awaitExpectedWindowFocus(compose.onNodeWithTag("tag-editor-sheet", useUnmergedTree = true))
        compose.onNode(hasSetTextAction() and hasText("Before name")).performScrollTo().performTextReplacement("Draft name")
        compose.onNodeWithText("Review changes").performClick()
        compose.onNodeWithText("Save changes").performClick()
        compose.runOnIdle {
            assertTrue("Save must have accepted the service launch", owned.started)
            assertTrue(controller.state.value.busy)
            assertTrue(controller.state.value.results.isEmpty())
        }
    }

    @Test fun saveOpensRetainedPopupWithVerifiedCountAndExplicitActions() {
        submit()
        compose.onNodeWithText("Saved 0 of 2").assertIsDisplayed()
        compose.onNodeWithText("Continue in background").assertIsDisplayed().assertHasClickAction()
        compose.onNodeWithText("Cancel", substring = false).assertIsDisplayed().assertHasClickAction()
        compose.onNodeWithTag("tag-editor-sheet", useUnmergedTree = true).assertExists()
    }

    @Test fun savingKeepsDraftFieldEnabledBehindTheModal() {
        submit()
        compose.onNode(hasText("Draft name") and SemanticsMatcher.keyIsDefined(SemanticsProperties.EditableText),
            useUnmergedTree = true).assertIsEnabled()
    }

    @After fun retire() {
        if (::controller.isInitialized) {
            // Release the accepted pending work through its ordinary path; every document is
            // explicitly unwritable, so cleanup cannot touch any user or fixture audio.
            runBlocking { withContext(Dispatchers.Main) { controller.runPending(); controller.close(); controller.hideJobs() } }
        }
        if (::owned.isInitialized) owned.retire()
    }
}

/** Hold foreground-service dispatch without mutating the application controller or using reflection. */
private class HeldTagServiceContext(base: Context) : ContextWrapper(base) {
    private val token = "popup-${UUID.randomUUID()}"
    private val directory = File(base.cacheDir, token).apply { check(mkdirs()) }
    private val preferences = mutableSetOf<String>()
    var started = false
        private set
    override fun getFilesDir(): File = directory
    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
        val owned = "$token-$name"
        synchronized(preferences) { preferences += owned }
        return baseContext.getSharedPreferences(owned, mode)
    }
    override fun startForegroundService(service: Intent): ComponentName {
        check(service.component?.className == TagEditService::class.java.name)
        started = true
        return requireNotNull(service.component)
    }
    fun retire() {
        synchronized(preferences) { preferences.forEach { baseContext.deleteSharedPreferences(it) } }
        check(directory.deleteRecursively())
    }
}
