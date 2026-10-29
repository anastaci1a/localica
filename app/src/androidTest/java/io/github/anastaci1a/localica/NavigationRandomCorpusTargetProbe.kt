package io.github.anastaci1a.localica

import android.Manifest
import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import android.provider.DocumentsContract
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructStat
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import io.github.anastaci1a.localica.library.AndroidDocuments
import io.github.anastaci1a.localica.library.LibraryDatabase
import io.github.anastaci1a.localica.library.LibrarySnapshot
import io.github.anastaci1a.localica.library.PersistedFolderPermissions
import io.github.anastaci1a.localica.library.RoomLibraryStore
import io.github.anastaci1a.localica.library.ScanOptions
import io.github.anastaci1a.localica.playback.PlaybackConnection
import io.github.anastaci1a.localica.playback.RecoveryAdmission
import io.github.anastaci1a.localica.queue.ListeningQueue
import io.github.anastaci1a.localica.queue.QueueDatabase
import io.github.anastaci1a.localica.queue.QueueLibrary
import io.github.anastaci1a.localica.queue.QueueSettings
import io.github.anastaci1a.localica.queue.SongTap
import io.github.anastaci1a.localica.scanner.ScanPreferences
import io.github.anastaci1a.localica.scanner.ScanState
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileDescriptor
import java.security.MessageDigest
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

/**
 * One explicitly selected target-UID observation/mutation, not an installation admission authority.
 * The lead must independently own the fresh install, exact APK/corpus pin, protected grant offers,
 * immutable serving root and <=1,000,000,000-byte peak ledger BEFORE selecting this hook. Matching
 * metadata, a flag, empty local state or this report cannot establish those historical host facts.
 *
 * No fallback corpus, provider activation, shell identity, lifecycle launch/stop, DB seeding,
 * permission revocation, reset or destructive cleanup exists here. A normal MainActivity must
 * already be resumed. On failure this hook does not clear/revoke/stop app state or its partial
 * report; timeout is NOT evidence that platform or app work terminated. The host owns reconciliation.
 * These warming checks establish neither cold performance nor decoded-payload/artwork validity.
 * AndroidJUnitRunner terminates its process at finish. Real execution requires the dedicated
 * resident entry point; disabling the ordinary runner's ActivityFinisher is not sufficient.
 * Resident startup still requires the host's fresh never-launched-target prerequisite.
 */
internal object NavigationRandomCorpusTargetProbe {
    const val OPT_IN_ARGUMENT = "localicaNavigationRandomOwned"
    const val OPT_IN_VALUE = "v1"
    const val RUNNER_ACTIVITY_WAIT_ARGUMENT = "waitForActivitiesToComplete"
    const val OWNED_TEST_CLASS = "io.github.anastaci1a.localica.NavigationRandomCorpusOwnedTargetTest"
    const val OWNED_TEST_METHOD = "verifyOwnedCorpusAndPersistQueue"
    const val EXPECTATION_DIRECTORY = "navigation-random-expectations-v1"
    const val REPORT_DIRECTORY = "navigation-random-target-report-v1"
    const val MAX_REPORT_BYTES = 8 * 1024 * 1024
    const val MAX_RECORD_BYTES = 4096
    const val MAX_REPORT_RECORDS = 13_600
    const val MAX_FAILURE_TEXT_CHARS = 512
    const val PREREQUISITE_TIMEOUT_MS = 30_000L
    const val COMMAND_TIMEOUT_MS = 30_000L
    const val READ_OPERATION_TIMEOUT_MS = 30_000L
    const val READ_PHASE_TIMEOUT_MS = 30 * 60 * 1000L
    const val SCAN_TIMEOUT_MS = 60 * 60 * 1000L
    const val QUEUE_TIMEOUT_MS = 2 * 60 * 1000L
    private const val AUTHORITY = NavigationRandomCorpusDocumentsProvider.AUTHORITY
    private val ROOT = DocumentsContract.buildTreeDocumentUri(AUTHORITY, NavigationRandomCorpusDocumentsProvider.ROOT)
    private val OVERLAP = DocumentsContract.buildTreeDocumentUri(AUTHORITY, NavigationRandomCorpusDocumentsProvider.OVERLAP)
    private val TREES = listOf(ROOT, OVERLAP)
    // Deliberately retained: closing a UI/service connection is not this hook's cleanup authority.
    private var retainedPlayback: PlaybackConnection? = null
    private val entered = AtomicBoolean(false)

    enum class Selection { SkipOrdinaryDiscovery, RunOwned }

    fun selection(flag: String?, classSelection: String?): Selection {
        if (flag != null) {
            require(flag == OPT_IN_VALUE) { "Invalid owned-corpus opt-in" }
            return Selection.RunOwned
        }
        val explicitlySelected = classSelection.orEmpty().split(',').map(String::trim).any {
            it == OWNED_TEST_CLASS || it.startsWith("$OWNED_TEST_CLASS#")
        }
        require(!explicitlySelected) { "Explicit owned-corpus selection requires opt-in" }
        return Selection.SkipOrdinaryDiscovery
    }

    /** Required declarations, not evidence of host lifecycle approval or surviving runner exit. */
    fun validateInvocation(flag: String?, classSelection: String?, runnerWait: String?) {
        require(selection(flag, classSelection) == Selection.RunOwned) { "Owned invocation requires opt-in" }
        require(classSelection == OWNED_TEST_CLASS || classSelection == "$OWNED_TEST_CLASS#$OWNED_TEST_METHOD") {
            "Owned invocation requires the exclusive class or method selector"
        }
        require(runnerWait == "false") { "Owned invocation must disable the runner ActivityFinisher" }
    }

    /** Hash-bound expected declarations only. No caller-selected digest or authority is accepted. */
    fun parseExpectedMetadata(manifest: ByteArray, tracks: ByteArray): NavigationRandomCorpusBinding =
        NavigationRandomCorpusJson.parse(manifest, tracks,
            NavigationRandomCorpusOwnedRoot.FROZEN_MANIFEST_SHA256, AUTHORITY)

    class EvidenceBudget {
        var records: Int = 0; private set
        var bytes: Long = 0; private set
        fun reserve(recordBytes: Int) {
            require(recordBytes in 1..MAX_RECORD_BYTES) { "Report record byte bound" }
            check(records < MAX_REPORT_RECORDS) { "Report record count bound" }
            check(recordBytes.toLong() <= MAX_REPORT_BYTES - bytes) { "Report total byte bound" }
            records++
            bytes += recordBytes
        }
    }

    fun failureText(value: String): String = value.take(MAX_FAILURE_TEXT_CHARS)

    fun runOwned(instrumentation: Instrumentation) {
        check(instrumentation is NavigationRandomOwnedSession && instrumentation.isResidentInvocation) {
            "Real owned operation requires the resident session, not terminal JUnit execution"
        }
        val arguments = InstrumentationRegistry.getArguments()
        validateInvocation(arguments.getString(OPT_IN_ARGUMENT), arguments.getString("class"),
            arguments.getString(RUNNER_ACTIVITY_WAIT_ARGUMENT))
        check(Looper.myLooper() != Looper.getMainLooper()) { "Run the hook off Main" }
        check(entered.compareAndSet(false, true)) { "Owned target hook already attempted in this process; no retry" }
        val worker = BoundedWorker()
        var journal: Journal? = null
        var primaryFailure: Throwable? = null
        var phase = "prerequisites"
        var pendingFile: Pair<String, Int>? = null
        try {
            val target = instrumentation.targetContext
            check(target.packageName == NavigationRandomCorpusOwnedRoot.TARGET_PACKAGE &&
                target.applicationInfo.uid == Process.myUid()) { "Require the actual target process UID" }
            check(Build.VERSION.SDK_INT == 26 || Build.VERSION.SDK_INT == 36) { "Only the reserved API pair is supported" }
            check(target.checkSelfPermission(Manifest.permission.DUMP) == PackageManager.PERMISSION_DENIED &&
                target.checkSelfPermission("android.permission.MANAGE_DOCUMENTS") == PackageManager.PERMISSION_DENIED) {
                "Privileged target would invalidate permission observations"
            }
            val paths = worker.call(READ_OPERATION_TIMEOUT_MS) { inputPaths(target) }
            check(!exists(paths.report)) { "Report slot already exists; no retry/adoption" }
            val binding = worker.call(READ_OPERATION_TIMEOUT_MS) {
                parseExpectedMetadata(readPrivate(File(paths.expected, "manifest.json"), NavigationRandomCorpusJson.MAX_MANIFEST_BYTES),
                    readPrivate(File(paths.expected, "tracks.jsonl"), NavigationRandomCorpusJson.MAX_TRACKS_BYTES))
            }
            val installed = worker.call(READ_OPERATION_TIMEOUT_MS) { NavigationRandomCorpusOwnedRoot.observeOwn(target) }
            worker.call(PREREQUISITE_TIMEOUT_MS) {
                val provider = target.packageManager.resolveContentProvider(AUTHORITY, 0)
                    ?: error("Fixed registered provider is unavailable")
                check(provider.packageName == NavigationRandomCorpusOwnedRoot.PROVIDER_PACKAGE &&
                    provider.applicationInfo.uid != Process.myUid() && provider.exported && provider.grantUriPermissions &&
                    provider.readPermission == "android.permission.MANAGE_DOCUMENTS") { "Unexpected provider registration/UID" }
                check(target.contentResolver.persistedUriPermissions.isEmpty()) { "Existing persisted grants are not a fresh target" }
                for (tree in TREES) {
                    checkPermission(target, document(tree, DocumentsContract.getTreeDocumentId(tree)), Intent.FLAG_GRANT_READ_URI_PERMISSION, true)
                    checkPermission(target, document(tree, binding.tracks.first().documentId), Intent.FLAG_GRANT_READ_URI_PERMISSION, true)
                    checkPermission(target, document(tree, binding.tracks.first().documentId), Intent.FLAG_GRANT_WRITE_URI_PERMISSION, false)
                }
            }
            sendStatus(instrumentation, "awaiting_normal_foreground", phase, null, null)
            awaitValue(PREREQUISITE_TIMEOUT_MS, "normally foreground MainActivity") { remaining ->
                onMain(minOf(remaining, COMMAND_TIMEOUT_MS)) { if (normallyForeground()) true else null }
            }
            val app = target.applicationContext as LocalicaApplication
            awaitValue(PREREQUISITE_TIMEOUT_MS, "ordinary app queue initialization") {
                if (!app.queues.state.value.loading && app.nativeReaders.recovery() == RecoveryAdmission.Clear) true else null
            }
            check(retainedPlayback == null) { "This process already retained an owned hook connection" }
            check(!app.scanner.state.value.running && app.scanner.state.value.folder == null) { "Existing scan work must not be adopted" }
            val settings = ScanPreferences(target)
            check(!settings.scanOnLaunch && !settings.fullScanOnLaunch && TREES.all { settings.read(it.toString()) == ScanOptions() }) {
                "Unexpected scanner settings; do not overwrite them"
            }
            check(app.queues.state.value.settings == QueueSettings() && app.queues.state.value.pending == null &&
                app.queues.state.value.problem == null) { "Unexpected queue settings or pending work" }
            awaitValue(PREREQUISITE_TIMEOUT_MS, "normally initialized app databases") { remaining ->
                worker.call(minOf(remaining, READ_OPERATION_TIMEOUT_MS)) {
                    if (exists(target.getDatabasePath("library.db")) && exists(target.getDatabasePath("queues.db"))) true else null
                }
            }
            val freshLibrary = readLibrary(target, worker, PREREQUISITE_TIMEOUT_MS)
            val freshQueues = readQueues(target, worker, PREREQUISITE_TIMEOUT_MS)
            check(freshLibrary.folders.isEmpty() && freshLibrary.tracks.isEmpty() && freshLibrary.metadataFailures.isEmpty()) {
                "Existing library data must not be adopted or cleared"
            }
            check(freshQueues.queues.isEmpty() && freshQueues.activeId == null && app.queues.state.value.library == freshQueues) {
                "Existing queue data must not be adopted or cleared"
            }
            // No report, persistable take or application command is issued until the above gates pass.
            journal = Journal.create(paths.report)
            journal.append(event("input_declarations").put("manifest_sha256", binding.declaredManifestSha256)
                .put("scope", binding.evidenceScope).put("expected_tracks", binding.tracks.size)
                .put("host_ownership_verified_by_hook", false).put("peak_budget_verified_by_hook", false))
            journal.append(event("local_process_observation").put("uid", Process.myUid()).put("pid", Process.myPid())
                .put("api", Build.VERSION.SDK_INT).put("package", installed.packageName)
                .put("apk_sha256", installed.apkSha256).put("signer_sha256", installed.signerSha256)
                .put("first_install_ms", installed.firstInstallMs).put("last_update_ms", installed.lastUpdateMs)
                .put("version_code", installed.versionCode))

            phase = "target_grants_and_bytes"
            journal.phase(phase, "started")
            val permissions = PersistedFolderPermissions(target.contentResolver)
            for (tree in TREES) {
                journal.append(event("persistable_take_attempt").put("tree", tree.toString()))
                worker.call(READ_OPERATION_TIMEOUT_MS) { permissions.acquire(tree.toString()) }
                journal.append(event("persistable_take_returned").put("tree", tree.toString()))
            }
            worker.call(READ_OPERATION_TIMEOUT_MS) { requirePersistedReadOnly(target) }
            journal.append(event("persisted_readback").put("roots", 2).put("read", true).put("write", false))
            val readDeadline = Deadline(READ_PHASE_TIMEOUT_MS, phase)
            val source = AndroidDocuments(target)
            for ((treeIndex, tree) in TREES.withIndex()) {
                val treeName = if (treeIndex == 0) "root" else "overlap"
                val expected = if (treeIndex == 0) binding.tracks else binding.tracks.take(NavigationScaleCatalog.QUEUE_ENTRY_COUNT)
                val root = worker.call(readDeadline.remaining(READ_OPERATION_TIMEOUT_MS)) { source.root(tree.toString()) }
                check(root.id == DocumentsContract.getTreeDocumentId(tree) && root.isDirectory) { "Unexpected granted root document" }
                val rows = worker.call(readDeadline.remaining(READ_OPERATION_TIMEOUT_MS)) { source.children(tree.toString(), root.id) }
                check(rows.size == expected.size && rows.map { it.id }.toSet() == expected.map { it.documentId }.toSet()) {
                    "Granted enumeration does not match expected identities"
                }
                val byId = rows.associateBy { it.id }
                for (track in expected) {
                    pendingFile = treeName to track.index
                    val row = byId.getValue(track.documentId)
                    val uri = document(tree, track.documentId)
                    check(!row.isDirectory && row.mimeType == "audio/mpeg" && row.name == track.relativeMp3Path.substringAfterLast('/') &&
                        row.uri == uri.toString() && row.identity == canonical(track.documentId)) { "Unexpected observed document identity/metadata" }
                    val observed = worker.call(readDeadline.remaining(READ_OPERATION_TIMEOUT_MS)) { operation ->
                        readGranted(target, uri, track.declaredFileBytes, operation)
                    }
                    journal.append(event("file_observed").put("tree", treeName).put("index", track.index)
                        .put("document_id", track.documentId).put("bytes", observed.first).put("sha256", observed.second)
                        .put("matches_expected", observed.second == track.fileSha256))
                    check(observed.second == track.fileSha256) { "Granted file hash differs at ${pendingFile}" }
                    pendingFile = null
                }
                journal.append(event("tree_observed").put("tree", if (treeIndex == 0) "root" else "overlap").put("files", expected.size))
            }
            for ((kind, uri, mode) in listOf(
                Triple("canonical_uri_not_granted", Uri.parse(canonical(binding.tracks.first().documentId)), "r"),
                Triple("overlap_outside_membership", document(OVERLAP, binding.tracks[100].documentId), "r"),
                Triple("root_write_denied", document(ROOT, binding.tracks.first().documentId), "rw"),
                Triple("overlap_write_denied", document(OVERLAP, binding.tracks.first().documentId), "rw"),
            )) {
                if (mode == "rw") checkPermission(target, uri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION, false)
                worker.call(readDeadline.remaining(READ_OPERATION_TIMEOUT_MS)) { operation ->
                    try {
                        target.contentResolver.openFileDescriptor(uri, mode, operation.signal)?.use { }
                        error("Expected SecurityException for $kind")
                    } catch (_: SecurityException) { /* Only the intended framework denial class is accepted. */ }
                }
                journal.append(event("access_denied").put("check", kind).put("exception", "java.lang.SecurityException"))
            }
            journal.phase(phase, "observed")

            phase = "production_scan_persistence"
            journal.phase(phase, "started")
            onMain {
                check(normallyForeground()) { "Normal foreground activity was lost" }
                check(!app.scanner.state.value.running && app.scanner.state.value.folder == null) { "Unexpected scan work appeared" }
            }
            for (tree in TREES) worker.call(COMMAND_TIMEOUT_MS) { runBlocking { app.library.addFolder(tree.toString()) } }
            val monitor = ScanMonitor()
            val observer = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            observer.launch(start = CoroutineStart.UNDISPATCHED) { app.scanner.state.collect { monitor.observe(it) } }
            try {
                onMain { app.scanner.start(TREES.map(Uri::toString)) } // Unit, not a successful-scan receipt.
                val terminal = awaitValue(SCAN_TIMEOUT_MS, "production scan terminal state") { monitor.terminal() }
                check(terminal.message == "Scan complete." && terminal.completedFolders == 2 && terminal.totalFolders == 2 && terminal.count == 13_413 &&
                    !monitor.hasUnexpectedRoot() && monitor.observedRoots() == TREES.map(Uri::toString).toSet()) { "Production scan did not observe both successful roots" }
                journal.append(event("scan_terminal").put("completed_roots", terminal.completedFolders)
                    .put("inspected_locations", terminal.count).put("message", terminal.message))
            } finally { observer.cancel() } // Observation only; NEVER cancel the app's scanner job.
            val snapshot = readLibrary(target, worker, COMMAND_TIMEOUT_MS)
            requireScanned(binding, snapshot)
            journal.append(event("scan_persisted").put("canonical_tracks", snapshot.tracks.size)
                .put("locations", snapshot.tracks.sumOf { it.locations.size })
                .put("metadata_failure_locations", snapshot.metadataFailures.size)
                .put("metadata_failure_unique_tracks", snapshot.metadataFailures.map { it.trackId }.toSet().size)
                .put("root_metadata_failures", snapshot.metadataFailures.count { it.folderUri == ROOT.toString() })
                .put("overlap_metadata_failures", snapshot.metadataFailures.count { it.folderUri == OVERLAP.toString() }))
            journal.phase(phase, "observed")

            phase = "queue_persistence_and_live_pause"
            journal.phase(phase, "started")
            val queueDeadline = Deadline(QUEUE_TIMEOUT_MS, phase)
            val byIdentity = snapshot.tracks.associateBy { it.id }
            val requested = binding.tracks.take(NavigationScaleCatalog.QUEUE_ENTRY_COUNT).map { byIdentity.getValue(canonical(it.documentId)) }
            val expectedIds = requested.map { it.id }
            val playback = onMain(queueDeadline.remaining(COMMAND_TIMEOUT_MS)) {
                check(normallyForeground()) { "Normal foreground activity was lost" }
                PlaybackConnection(target).also { retainedPlayback = it }
            }
            awaitValue(queueDeadline.remaining(), "connected production player") {
                val state = playback.state.value
                check(state.problem == null) { "Production playback connection failed" }
                if (state.connected) true else null
            }
            val created = CompletableFuture<Boolean>()
            onMain(queueDeadline.remaining(COMMAND_TIMEOUT_MS)) {
                app.queues.act(requested, SongTap.NewQueue) { created.complete(it) }
                app.queues.playbackRequested(false) // Publish pause in the same Main turn, not after completion.
            }
            check(awaitFuture(created, queueDeadline.remaining(COMMAND_TIMEOUT_MS), "new queue completion")) { "Normal NewQueue action was rejected" }
            val actual = app.queues.state.value.library.active ?: error("No observed active queue after completion")
            requireQueue(actual, expectedIds)
            val selectedEntry = actual.entries[50].id
            onMain(queueDeadline.remaining(COMMAND_TIMEOUT_MS)) {
                app.queues.selectEntry(selectedEntry)
                app.queues.playbackRequested(false)
            }
            val visit = awaitValue(queueDeadline.remaining(), "matching prepared paused visit") {
                val active = app.queues.state.value.library.active
                val state = playback.state.value
                check(app.queues.state.value.problem == null && state.problem == null) { "Queue/player reported a problem" }
                val current = active?.journey?.current
                if (active != null && active.id == actual.id && active.currentEntryId == selectedEntry && current != null && current.entryId == selectedEntry &&
                    state.connected && state.playbackVisit == current.token && state.trackId == expectedIds[50] &&
                    state.durationMs?.let { it > 0L } == true && !state.isBuffering && !state.ended &&
                    !state.playWhenReady && !state.isPlaying) current.token else null
            }
            val requestId = onMain(queueDeadline.remaining(COMMAND_TIMEOUT_MS)) { playback.requestSeek(0L, visit) }
            awaitValue(queueDeadline.remaining(), "matching zero seek acknowledgement") {
                val state = playback.state.value
                check(state.problem == null) { "Player reported a seek problem" }
                val receipt = state.seekAcknowledgement
                if (receipt?.requestId == requestId) {
                    check(receipt.applied && receipt.playbackVisit == visit && receipt.positionMs == 0L) { "Zero seek was not applied to the expected visit" }
                    true
                } else null
            }
            // Native seek acknowledgement occurs BEFORE capture/persistence in QueueController.
            val durable = awaitValue(queueDeadline.remaining(), "independent durable queue readback") {
                val library = readQueues(target, worker, queueDeadline.remaining(COMMAND_TIMEOUT_MS))
                val active = library.active
                if (library.queues.size == 1 && active != null && active.id == actual.id && active.currentEntryId == selectedEntry &&
                    active.positionMs == 0L && active.journey?.current?.token == visit) library else null
            }
            val saved = requireNotNull(durable.active)
            requireQueue(saved, expectedIds)
            check(saved.entries.map { it.id } == actual.entries.map { it.id } && saved.entries.indexOfFirst { it.id == saved.currentEntryId } == 50) {
                "Durable queue occurrence IDs/order/cursor differ"
            }
            journal.append(event("queue_persisted").put("queue_id", saved.id).put("active_id", durable.activeId)
                .put("current_entry_id", saved.currentEntryId).put("cursor_ordinal", 50).put("position_ms", saved.positionMs)
                .put("visit", visit).put("entries", saved.entries.size).put("queue_db_has_playing_bit", false))
            for ((index, entry) in saved.entries.withIndex()) journal.append(event("queue_entry_observed")
                .put("ordinal", index).put("entry_id", entry.id).put("track_id", entry.track.id))
            val live = awaitValue(queueDeadline.remaining(), "final connected live paused state") {
                val state = playback.state.value
                check(state.problem == null) { "Final live playback has a problem" }
                if (state.connected && state.trackId == expectedIds[50] && state.playbackVisit == visit && state.positionMs == 0L &&
                    !state.playWhenReady && !state.isPlaying && !state.isBuffering && !state.ended &&
                    state.seekAcknowledgement?.requestId == requestId) state else null
            }
            worker.call(queueDeadline.remaining(READ_OPERATION_TIMEOUT_MS)) { requirePersistedReadOnly(target) }
            journal.append(event("live_paused_observation").put("visit", visit).put("position_ms", live.positionMs)
                .put("play_when_ready", live.playWhenReady).put("is_playing", live.isPlaying).put("connected", live.connected)
                .put("sampled_at_elapsed_ms", live.sampledAtMs).put("process_restart_tested", false))
            journal.phase(phase, "observed")
            journal.terminal(event("all_phases_observed").put("host_ownership_verified_by_hook", false)
                .put("peak_budget_verified_by_hook", false).put("performance_claim", false))
            sendStatus(instrumentation, "observed", phase, journal.path, null)
        } catch (failure: Throwable) {
            primaryFailure = failure
            val record = event("failed").put("phase", phase).put("exception", failure.javaClass.name)
                .put("message", failureText(failure.message.orEmpty())).put("work_may_continue", true)
            pendingFile?.let { record.put("pending_tree", it.first).put("pending_index", it.second) }
            (failure as? FileReadFailure)?.let {
                record.put("bytes_observed", it.bytesObserved).put("eof_observed", it.eof)
                    .put("complete_sha256", it.completeSha256 ?: JSONObject.NULL)
            }
            try { journal?.terminal(record) } catch (recordFailure: Throwable) { failure.addSuppressed(recordFailure) }
            try { sendStatus(instrumentation, "failed", phase, journal?.path, failure.javaClass.name) }
            catch (statusFailure: Throwable) { failure.addSuppressed(statusFailure) }
            throw AssertionError("Owned target hook failed at $phase; partial report=${journal?.path ?: "not created before prerequisites"}; no reset/retry/cleanup", failure)
        } finally {
            try { journal?.close() }
            catch (closing: Throwable) {
                if (primaryFailure != null) primaryFailure.addSuppressed(closing)
                else {
                    try { sendStatus(instrumentation, "failed", "report_close", journal?.path, closing.javaClass.name) }
                    catch (statusFailure: Throwable) { closing.addSuppressed(statusFailure) }
                    throw AssertionError("Owned report close failed; effects remain and require reconciliation", closing)
                }
            } finally { worker.close() } // Owned read worker only; no app job/service/connection teardown here.
        }
    }

    private fun requireQueue(queue: ListeningQueue, ids: List<String>) {
        check(queue.id.isNotBlank() && queue.entries.size == 100 && queue.entries.map { it.id }.toSet().size == 100 &&
            queue.entries.all { it.id.isNotBlank() && it.sourcePlaylistId == null } && queue.entries.map { it.track.id } == ids) {
            "Actual queue does not match the deterministic requested identities"
        }
    }

    private fun requireScanned(binding: NavigationRandomCorpusBinding, snapshot: LibrarySnapshot) {
        val expected = binding.tracks.associateBy { canonical(it.documentId) }
        check(snapshot.folders.map { it.uri }.toSet() == TREES.map(Uri::toString).toSet() && snapshot.folders.size == 2 &&
            snapshot.tracks.size == 13_313 && snapshot.tracks.map { it.id }.toSet() == expected.keys) { "Persisted canonical library membership differs" }
        for (track in snapshot.tracks) {
            val declared = expected.getValue(track.id)
            val trees = if (declared.index < 100) TREES else listOf(ROOT)
            check(track.fileName == declared.relativeMp3Path.substringAfterLast('/') && track.locations.size == trees.size &&
                track.locations.map { it.folderUri }.toSet() == trees.map(Uri::toString).toSet() && track.locations.all {
                    it.uri == document(Uri.parse(it.folderUri), declared.documentId).toString()
                }) { "Persisted track locations differ" }
        }
        check(snapshot.tracks.sumOf { it.locations.size } == 13_413 && snapshot.metadataFailures.all {
            it.trackId in expected && it.folderUri in TREES.map(Uri::toString) &&
                (it.folderUri == ROOT.toString() || expected.getValue(it.trackId).index < 100)
        }) { "Persisted metadata-failure membership differs" }
    }

    private fun readLibrary(context: Context, worker: BoundedWorker, timeout: Long): LibrarySnapshot = worker.call(timeout) {
        requireExistingDatabase(context, "library.db")
        // Current schema only: no migrations, create-from-asset, fallback or direct writes.
        val database = Room.databaseBuilder(context, LibraryDatabase::class.java, "library.db").build()
        try { runBlocking { RoomLibraryStore(database.libraryDao()).current() } }
        finally { database.close() }
    }

    private fun readQueues(context: Context, worker: BoundedWorker, timeout: Long): QueueLibrary = worker.call(timeout) {
        requireExistingDatabase(context, "queues.db")
        val database = Room.databaseBuilder(context, QueueDatabase::class.java, "queues.db").build()
        try { runBlocking { database.queues().read() } }
        finally { database.close() }
    }

    private fun requireExistingDatabase(context: Context, name: String) {
        val file = context.getDatabasePath(name).canonicalFile
        val stat = Os.lstat(file.path)
        check(file.path == file.canonicalPath && OsConstants.S_ISREG(stat.st_mode) && stat.st_uid == Process.myUid() && stat.st_nlink == 1L) {
            "Expected current app database is absent or aliased"
        }
    }

    private class ScanMonitor {
        private var started = false
        private val roots = linkedSetOf<String>()
        private var unexpectedRoot = false
        private var result: ScanState? = null
        @Synchronized fun observe(state: ScanState) {
            if (state.running) {
                started = true
                state.folder?.let { if (it in TREES.map(Uri::toString)) roots += it else unexpectedRoot = true }
            } else if (started) result = state
        }
        @Synchronized fun terminal(): ScanState? = result
        @Synchronized fun observedRoots(): Set<String> = roots.toSet()
        @Synchronized fun hasUnexpectedRoot(): Boolean = unexpectedRoot
    }

    private fun requirePersistedReadOnly(context: Context) {
        val grants = context.contentResolver.persistedUriPermissions
        check(grants.size == 2 && grants.map { it.uri }.toSet() == TREES.toSet() && grants.all { it.isReadPermission && !it.isWritePermission }) {
            "Actual target persisted grants differ"
        }
    }

    private fun checkPermission(context: Context, uri: Uri, mode: Int, allowed: Boolean) {
        val expected = if (allowed) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED
        check(context.checkUriPermission(uri, Process.myPid(), Process.myUid(), mode) == expected) { "Unexpected target URI permission: $uri mode=$mode" }
    }

    private class FileReadFailure(val bytesObserved: Long, val eof: Boolean, val completeSha256: String?, message: String) :
        IllegalStateException(message)

    private fun readGranted(context: Context, uri: Uri, expectedBytes: Long, operation: Operation): Pair<Long, String> {
        val descriptor = context.contentResolver.openFileDescriptor(uri, "r", operation.signal) ?: error("Null granted read descriptor")
        return operation.useOwned(ParcelFileDescriptor.AutoCloseInputStream(descriptor)) { input ->
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(64 * 1024)
            var bytes = 0L
            while (true) {
                check(!Thread.currentThread().isInterrupted) { "Granted read interrupted" }
                val count = input.read(buffer)
                if (count < 0) break
                if (count == 0) throw FileReadFailure(bytes, false, null, "Granted read made no progress")
                bytes = Math.addExact(bytes, count.toLong())
                digest.update(buffer, 0, count)
                if (bytes > expectedBytes) throw FileReadFailure(bytes, false, null, "Granted read exceeds expected length")
            }
            val sha256 = hex(digest.digest())
            if (bytes != expectedBytes) throw FileReadFailure(bytes, true, sha256, "Granted read ended at a different length")
            bytes to sha256
        }
    }

    private data class InputPaths(val expected: File, val report: File)
    private fun inputPaths(context: Context): InputPaths {
        val files = context.filesDir.canonicalFile // Framework alias only, never a caller path.
        val stat = Os.lstat(files.path)
        check(OsConstants.S_ISDIR(stat.st_mode) && stat.st_uid == Process.myUid()) { "Wrong target files root" }
        val expected = File(files, EXPECTATION_DIRECTORY)
        privateDirectory(expected)
        check(expected.list()?.toSet() == setOf("manifest.json", "tracks.jsonl")) { "Expected exactly the two pinned metadata inputs" }
        return InputPaths(expected, File(files, REPORT_DIRECTORY))
    }

    private fun privateDirectory(file: File) {
        check(file.isAbsolute && file.path == file.canonicalPath) { "Private directory must be canonical" }
        var parent: File? = file
        while (parent != null) {
            check(OsConstants.S_ISDIR(Os.lstat(parent.path).st_mode)) { "Symlink/non-directory ancestor" }
            parent = parent.parentFile
        }
        val stat = Os.lstat(file.path)
        check(stat.st_uid == Process.myUid() && (stat.st_mode and 63) == 0) { "Private directory owner/mode differs" }
    }

    private fun readPrivate(file: File, limit: Int): ByteArray {
        val before = Os.lstat(file.path)
        check(OsConstants.S_ISREG(before.st_mode) && before.st_nlink == 1L && before.st_uid == Process.myUid() && (before.st_mode and 63) == 0) {
            "Expected private unaliased target-owned metadata file"
        }
        require(before.st_size in 1..limit.toLong()) { "Expected metadata byte bound" }
        val descriptor = Os.open(file.path, OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW or OsConstants.O_CLOEXEC or OsConstants.O_NONBLOCK, 0)
        try {
            check(sameFile(before, Os.fstat(descriptor))) { "Metadata changed at open" }
            val bytes = ByteArrayOutputStream(minOf(before.st_size, 65536L).toInt())
            val buffer = ByteArray(65536)
            while (true) {
                check(!Thread.currentThread().isInterrupted) { "Metadata read interrupted" }
                val count = Os.read(descriptor, buffer, 0, buffer.size)
                if (count == 0) break
                require(count > 0 && count <= limit - bytes.size()) { "Expected metadata byte bound" }
                bytes.write(buffer, 0, count)
            }
            check(bytes.size().toLong() == before.st_size && sameFile(before, Os.fstat(descriptor)) && sameFile(before, Os.lstat(file.path))) {
                "Metadata changed during read"
            }
            return bytes.toByteArray()
        } finally { Os.close(descriptor) }
    }

    private fun sameFile(a: StructStat, b: StructStat): Boolean = a.st_dev == b.st_dev && a.st_ino == b.st_ino &&
        a.st_mode == b.st_mode && a.st_uid == b.st_uid && a.st_gid == b.st_gid && a.st_nlink == b.st_nlink &&
        a.st_size == b.st_size && a.st_mtime == b.st_mtime && a.st_ctime == b.st_ctime

    private fun exists(file: File): Boolean = try { Os.lstat(file.path); true }
        catch (failure: ErrnoException) { if (failure.errno == OsConstants.ENOENT) false else throw failure }

    private class Journal private constructor(private val descriptor: FileDescriptor, val path: String) : Closeable {
        private val budget = EvidenceBudget()
        fun append(record: JSONObject) = write(record, terminal = false)
        fun phase(name: String, outcome: String) { append(event("phase").put("phase", name).put("outcome", outcome)); Os.fsync(descriptor) }
        fun terminal(record: JSONObject) { write(record, terminal = true); Os.fsync(descriptor) }
        private fun write(record: JSONObject, terminal: Boolean) {
            val bytes = (record.toString() + "\n").toByteArray(Charsets.UTF_8)
            // Reserve two records/8KiB for a terminal outcome and a possible later write/close failure.
            if (!terminal) check(budget.records < MAX_REPORT_RECORDS - 2 &&
                bytes.size.toLong() <= MAX_REPORT_BYTES - 2L * MAX_RECORD_BYTES - budget.bytes) { "Report terminal reserve reached" }
            budget.reserve(bytes.size)
            var offset = 0
            while (offset < bytes.size) {
                val written = Os.write(descriptor, bytes, offset, bytes.size - offset)
                check(written > 0) { "Report write made no progress" }
                offset += written
            }
        }
        override fun close() { Os.close(descriptor) }
        companion object {
            fun create(root: File): Journal {
                Os.mkdir(root.path, 448) // O_EXCL-equivalent directory creation; never reuse a slot.
                privateDirectory(root)
                val file = File(root, "observations.jsonl")
                val descriptor = Os.open(file.path, OsConstants.O_WRONLY or OsConstants.O_CREAT or OsConstants.O_EXCL or
                    OsConstants.O_NOFOLLOW or OsConstants.O_CLOEXEC, 384)
                try {
                    fsyncDirectory(root)
                    fsyncDirectory(requireNotNull(root.parentFile))
                    return Journal(descriptor, file.path)
                } catch (failure: Throwable) { try { Os.close(descriptor) } catch (closing: Throwable) { failure.addSuppressed(closing) }; throw failure }
            }
            private fun fsyncDirectory(file: File) {
                val fd = Os.open(file.path, OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW or
                    OsConstants.O_CLOEXEC or OsConstants.O_NONBLOCK, 0)
                try {
                    check(OsConstants.S_ISDIR(Os.fstat(fd).st_mode))
                    Os.fsync(fd)
                } finally { Os.close(fd) }
            }
        }
    }

    private class Deadline(timeout: Long, private val label: String) {
        private val end = SystemClock.elapsedRealtime() + timeout
        fun remaining(maximum: Long = Long.MAX_VALUE): Long {
            val remaining = end - SystemClock.elapsedRealtime()
            check(remaining > 0) { "Timed out: $label; work may continue" }
            return minOf(remaining, maximum)
        }
    }

    private fun <T : Any> awaitValue(timeout: Long, label: String, observation: (Long) -> T?): T {
        val deadline = Deadline(timeout, label)
        while (true) {
            val result = observation(deadline.remaining())
            deadline.remaining()
            if (result != null) return result
            Thread.sleep(minOf(50L, deadline.remaining()))
        }
    }

    private fun <T> awaitFuture(future: java.util.concurrent.Future<T>, timeout: Long, label: String): T = try {
        future.get(timeout, TimeUnit.MILLISECONDS)
    } catch (failure: ExecutionException) { throw requireNotNull(failure.cause) }
    catch (failure: TimeoutException) { throw IllegalStateException("Timed out: $label; work may continue", failure) }

    private fun <T> onMain(timeout: Long = COMMAND_TIMEOUT_MS, block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        val handler = Handler(Looper.getMainLooper())
        val task = FutureTask<T> { block() }
        check(handler.post(task)) { "Main handler rejected observation/command" }
        try { return awaitFuture(task, timeout, "Main handoff") }
        finally { if (!task.isDone) { task.cancel(false); handler.removeCallbacks(task) } }
    }

    private fun normallyForeground(): Boolean {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        return if (instrumentation is NavigationRandomOwnedSession) instrumentation.hasResumedMainActivity()
        else ActivityLifecycleMonitorRegistry.getInstance()
            .getActivitiesInStage(Stage.RESUMED).let { it.size == 1 && it.single() is MainActivity }
    }

    private class Operation {
        val signal = CancellationSignal()
        private val handles = linkedSetOf<Closeable>()
        private var cancelled = false
        fun <T : Closeable, R> useOwned(handle: T, block: (T) -> R): R {
            val accepted = synchronized(handles) {
                if (cancelled) false else { handles += handle; true }
            }
            if (!accepted) {
                handle.close()
                error("Late owned handle after cancellation")
            }
            var primary: Throwable? = null
            try { return block(handle) }
            catch (failure: Throwable) { primary = failure; throw failure }
            finally {
                synchronized(handles) { handles.remove(handle) }
                try { handle.close() } catch (closing: Throwable) { if (primary == null) throw closing else primary.addSuppressed(closing) }
            }
        }
        fun requestCancellation() {
            // Platform cancellation/close may itself stall; never wait for it or claim termination.
            val owned = synchronized(handles) { cancelled = true; handles.toList() }
            Thread({
                try { signal.cancel() } catch (_: Exception) { }
            }, "navigation-owned-signal-cancel").apply { isDaemon = true; start() }
            Thread({
                owned.forEach { try { it.close() } catch (_: Exception) { } }
            }, "navigation-owned-handle-close").apply { isDaemon = true; start() }
        }
    }

    private class BoundedWorker : Closeable {
        private val executor = Executors.newSingleThreadExecutor { task -> Thread(task, "navigation-owned-reads").apply { isDaemon = true } }
        private var failed = false
        fun <T> call(timeout: Long, block: (Operation) -> T): T {
            check(!failed) { "Timed-out worker cannot be reused" }
            val operation = Operation()
            val future = executor.submit<T> { block(operation) }
            try { return awaitFuture(future, timeout, "owned read/operation") }
            catch (failure: Throwable) {
                failed = true
                future.cancel(true)
                operation.requestCancellation()
                executor.shutdownNow()
                throw failure
            }
        }
        override fun close() { executor.shutdownNow() }
    }

    private fun event(kind: String): JSONObject = JSONObject().put("schema_version", 1).put("event", kind)
        .put("elapsed_realtime_ms", SystemClock.elapsedRealtime())
    private fun canonical(id: String): String = DocumentsContract.buildDocumentUri(AUTHORITY, id).toString()
    private fun document(tree: Uri, id: String): Uri = DocumentsContract.buildDocumentUriUsingTree(tree, id)
    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
    private fun sendStatus(instrumentation: Instrumentation, outcome: String, phase: String, path: String?, failureClass: String?) {
        instrumentation.sendStatus(2, Bundle().apply {
            putString("localica_owned_outcome", outcome); putString("localica_owned_phase", phase)
            putString("localica_owned_report", path); putString("localica_owned_failure_class", failureClass)
        })
    }
}
