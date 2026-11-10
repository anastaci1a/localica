package io.github.anastaci1a.localica

import androidx.compose.ui.test.*
import io.github.anastaci1a.localica.applicationRootAndroidComposeRule as createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.editing.AndroidEditableDocuments
import io.github.anastaci1a.localica.editing.RecoverableTagWriter
import io.github.anastaci1a.localica.editing.TagEditingController
import io.github.anastaci1a.localica.library.Track
import io.github.anastaci1a.localica.library.TrackSnapshot
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement
import java.io.File
import java.security.MessageDigest

/** The real MainActivity launches with a real application recovery already pending. */
class TagRecoveryLaunchTest {
    private val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(BrowsePreferenceIsolation(
        listOf("interface", "browse-style", "library-query", "library-filters")))
        .around(PendingApplicationRecovery()).around(compose)

    @Test fun pendingRecoverySurvivesActivityLaunchRecreateAndDismissedRelaunch() {
        val app = compose.activity.application as LocalicaApplication
        assertEquals("root-recovery.mp3", app.tagEditing.state.value.recovery)
        assertTrue(app.tagEditing.state.value.tracks.isEmpty())
        compose.onNodeWithText("Interrupted tag edit").assertIsDisplayed()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("Interrupted tag edit").assertIsDisplayed()
        compose.onNodeWithText("Later").performClick()
        compose.onNodeWithText("Interrupted tag edit").assertDoesNotExist()
        assertEquals("Dismissal must retain recovery", "root-recovery.mp3", app.tagEditing.state.value.recovery)
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("Interrupted tag edit").assertIsDisplayed()
        compose.onNodeWithText("Later").performClick()
    }
}

/** Test-only process isolation: preserve application delegates and never restore or edit a real song. */
private class PendingApplicationRecovery : TestRule {
    override fun apply(base: Statement, description: Description): Statement = object : Statement() {
        override fun evaluate() {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val app = instrumentation.targetContext.applicationContext as LocalicaApplication
            fun onMain(action: () -> Unit) {
                var failure: Throwable? = null
                instrumentation.runOnMainSync { try { action() } catch (error: Throwable) { failure = error } }
                failure?.let { throw it }
            }
            fun controllerField(name: String) = TagEditingController::class.java.getDeclaredField(name).apply { isAccessible = true }
            val initialized = controllerField("initialized")
            val scopeField = controllerField("scope")
            lateinit var previousController: TagEditingController
            onMain { previousController = app.tagEditing }
            runBlocking { withTimeout(15_000) { (initialized.get(previousController) as Deferred<*>).await() } }
            check(!previousController.state.value.busy && previousController.state.value.batchId == null &&
                previousController.state.value.tracks.isEmpty() && !previousController.state.value.jobsVisible) {
                "Do not replace active tag-edit state"
            }
            val previousAdmission = app.nativeReaders.recovery()
            val directory = File(app.filesDir, "tag-recovery")
            val journal = File(directory, "pending.json")
            check(listOf("pending.json", "pending.json.bak", "pending.json.new").none { File(directory, it).exists() }) {
                "Existing recovery data must be preserved"
            }
            check(previousController.state.value.recovery == null) { "Existing recovery state must be preserved" }
            val directoryExisted = directory.exists()
            val original = File(directory, "original.root-fixture-${java.util.UUID.randomUUID()}")
            val bytes = "retained root launch recovery".toByteArray()
            val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            val track = Track("content://root-recovery.fixture/document/song", "Root recovery", fileName = "root-recovery.mp3", relativePath = "root-recovery.mp3")
            val record = JSONObject().put("track", TrackSnapshot.encode(track)).put("uri", track.id)
                .put("original", original.name).put("hash", hash).toString().toByteArray()
            val writerField = LocalicaApplication::class.java.getDeclaredField("tagWriter\$delegate").apply { isAccessible = true }
            val editingField = LocalicaApplication::class.java.getDeclaredField("tagEditing\$delegate").apply { isAccessible = true }
            val previousWriter = writerField.get(app)
            val previousEditing = editingField.get(app)
            var fixtureController: TagEditingController? = null
            var failure: Throwable? = null
            try {
                // Keep the old writer untouched; the real application getters now initialize from our journal.
                check(directory.isDirectory || directory.mkdirs()) { "Could not create fixture recovery directory" }
                original.writeBytes(bytes)
                journal.writeBytes(record)
                onMain {
                    writerField.set(app, lazy { RecoverableTagWriter(directory, AndroidEditableDocuments(app), app.nativeReaders) })
                    editingField.set(app, lazy { TagEditingController(app, app.library, app.queues, app.playlists, app.tagWriter) })
                    fixtureController = app.tagEditing
                }
                runBlocking { withTimeout(15_000) { (initialized.get(checkNotNull(fixtureController)) as Deferred<*>).await() } }
                assertEquals("root-recovery.mp3", fixtureController!!.state.value.recovery)
                assertNull(fixtureController!!.state.value.recoveryProblem)
                // The inner raw Activity rule starts only after recovery is pending in app.tagEditing.
                base.evaluate()
                assertArrayEquals("The launch path retains the journal", record, journal.readBytes())
                assertArrayEquals("The launch path retains original bytes", bytes, original.readBytes())
            } catch (error: Throwable) { failure = error }
            // The inner Activity rule has disposed before any application delegate is restored.
            val cleanup = FixtureRestoration(failure)
            var fixtureScope: CoroutineScope? = null
            cleanup.attempt("cancel test controller") {
                fixtureScope = fixtureController?.let { scopeField.get(it) as CoroutineScope }
                onMain { fixtureScope?.cancel() }
            }
            cleanup.attempt("restore application controller delegate") { onMain { editingField.set(app, previousEditing) } }
            cleanup.attempt("restore application writer delegate") { onMain { writerField.set(app, previousWriter) } }
            cleanup.attempt("join test controller") {
                runBlocking { withTimeout(15_000) { fixtureScope?.coroutineContext?.get(Job)?.join() } }
            }
            cleanup.attempt("remove exact test journal") {
                if (journal.exists()) {
                    check(journal.readBytes().contentEquals(record)) { "Unexpected journal mutation; retained for inspection" }
                    check(journal.delete()) { "Could not remove the test journal" }
                }
            }
            cleanup.attempt("remove exact test original") {
                if (original.exists()) {
                    check(original.readBytes().contentEquals(bytes)) { "Unexpected original mutation; retained for inspection" }
                    check(original.delete()) { "Could not remove the test original" }
                }
            }
            cleanup.attempt("remove empty test directory") {
                if (!directoryExisted && directory.listFiles()?.isEmpty() == true) check(directory.delete())
            }
            cleanup.attempt("restore exact native-reader admission") { app.nativeReaders.publishRecovery(previousAdmission) }
            cleanup.finish()
        }
    }
}
