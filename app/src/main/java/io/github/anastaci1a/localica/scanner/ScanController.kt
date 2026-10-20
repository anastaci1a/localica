package io.github.anastaci1a.localica.scanner

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import io.github.anastaci1a.localica.library.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class ScanState(val running: Boolean = false, val count: Int = 0, val folder: String? = null,
    val completedFolders: Int = 0, val totalFolders: Int = 0, val message: String? = null)

/** App-owned state survives activity recreation; the foreground service owns the visible work lifetime. */
class ScanController(private val context: Context, private val library: LibraryRepository) {
    private val marker = context.getSharedPreferences("scan-progress", 0)
    private val mutable = MutableStateFlow(ScanState(message = if (marker.getBoolean("running", false))
        "The previous scan was interrupted. Scan again to finish updating your library." else null))
    val state = mutable.asStateFlow()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val requests = ScanRequests()
    private var job: Job? = null
    private var interruption: String? = null
    private var canceledBeforeStart = false
    init { marker.edit().putBoolean("running", false).apply() }

    fun start(roots: List<String>) { start(roots, ScanMode.Full) }

    fun startNewFiles(roots: List<String>): Boolean = start(roots, ScanMode.NewFiles)

    private fun start(roots: List<String>, mode: ScanMode): Boolean {
        if (roots.isEmpty()) return false
        try {
            if (!state.value.running) canceledBeforeStart = false
            mutable.update { it.copy(running = true, message = null) }
            ContextCompat.startForegroundService(context, Intent(context, ScanService::class.java)
                .putStringArrayListExtra("roots", ArrayList(roots.distinct()))
                .putExtra("new-files-only", mode == ScanMode.NewFiles))
            return true
        } catch (_: Exception) {
            mutable.update { it.copy(running = false, message = "Android couldn’t start background scanning. Keep Localica open and check its app settings.") }
            return false
        }
    }

    internal fun run(roots: List<String>, newFilesOnly: Boolean = false, finished: () -> Unit) {
        if (canceledBeforeStart) {
            mutable.update { it.copy(running = false, message = "Scan canceled.") }; finished(); return
        }
        requests.add(roots, if (newFilesOnly) ScanMode.NewFiles else ScanMode.Full)
        if (job?.isActive == true) {
            mutable.update { it.copy(totalFolders = it.completedFolders + requests.size) }
            return
        }
        interruption = null
        mutable.value = ScanState(running = true, totalFolders = requests.size)
        marker.edit().putBoolean("running", true).apply()
        job = scope.launch {
            var count = 0
            val failures = mutableListOf<String>()
            try {
                withTimeout(6 * 60 * 60 * 1000L - 60_000L) {
                    while (true) {
                        val request = requests.take() ?: break
                        val root = request.root
                        mutable.update { it.copy(folder = root) }
                        try {
                            val progress: (Int) -> Unit = { scanned ->
                                mutable.update { it.copy(count = count + scanned) }
                            }
                            val options = ScanPreferences(context).read(root)
                            if (request.mode == ScanMode.NewFiles) library.scanNewFiles(root, options, progress)
                            else {
                                library.scan(root, options, progress)
                                ArtworkCache.invalidate()
                            }
                            mutable.update { it.copy(completedFolders = it.completedFolders + 1) }
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) {
                            failures += root
                            mutable.update { it.copy(completedFolders = it.completedFolders + 1) }
                        }
                        count = state.value.count
                        requests.complete()
                    }
                }
                mutable.update { it.copy(message = if (failures.isEmpty()) "Scan complete." else
                    "Couldn’t scan ${failures.size} folder(s). Their previous songs were kept. Check folder access and scan again.") }
            } catch (_: TimeoutCancellationException) {
                mutable.update { it.copy(message = "Scanning reached Android’s time limit. Completed folders were saved; scan again to continue.") }
            } catch (cancelled: CancellationException) {
                mutable.update { it.copy(message = interruption ?: "Scan canceled. Completed folders were saved; the current folder was kept unchanged.") }
                throw cancelled
            } finally {
                requests.clear()
                mutable.update { it.copy(running = false, folder = null) }
                marker.edit().putBoolean("running", false).apply()
                finished()
            }
        }
    }
    fun cancel(message: String? = null) {
        if (message != null) interruption = message
        canceledBeforeStart = true; requests.clear(); job?.cancel()
        if (job == null) mutable.update { it.copy(running = false, message = message ?: "Scan canceled.") }
    }
    internal fun failedToStart() {
        mutable.update { it.copy(running = false, message = "Android couldn’t start background scanning. Check Localica’s app settings and try again.") }
    }
}
