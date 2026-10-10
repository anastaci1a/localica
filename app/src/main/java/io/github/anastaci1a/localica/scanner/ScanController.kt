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
    private val pending = linkedSetOf<String>()
    private var job: Job? = null
    private var current: String? = null
    private var interruption: String? = null
    private var canceledBeforeStart = false
    init { marker.edit().putBoolean("running", false).apply() }

    fun start(roots: List<String>) {
        if (roots.isEmpty()) return
        try {
            if (!state.value.running) canceledBeforeStart = false
            mutable.update { it.copy(running = true, message = null) }
            ContextCompat.startForegroundService(context, Intent(context, ScanService::class.java)
                .putStringArrayListExtra("roots", ArrayList(roots.distinct())))
        } catch (_: Exception) {
            mutable.update { it.copy(running = false, message = "Android couldn’t start background scanning. Keep Localica open and check its app settings.") }
        }
    }

    internal fun run(roots: List<String>, finished: () -> Unit) {
        if (canceledBeforeStart) {
            mutable.update { it.copy(running = false, message = "Scan canceled.") }; finished(); return
        }
        pending.addAll(roots.filter { it != current })
        if (job?.isActive == true) {
            mutable.update { it.copy(totalFolders = it.completedFolders + pending.size + if (current != null) 1 else 0) }
            return
        }
        interruption = null
        mutable.value = ScanState(running = true, totalFolders = pending.size)
        marker.edit().putBoolean("running", true).apply()
        job = scope.launch {
            var count = 0
            val failures = mutableListOf<String>()
            try {
                withTimeout(6 * 60 * 60 * 1000L - 60_000L) {
                    while (pending.isNotEmpty()) {
                        val root = pending.first().also { pending.remove(it); current = it }
                        mutable.update { it.copy(folder = root) }
                        try {
                            library.scan(root, ScanPreferences(context).read(root)) { scanned ->
                                mutable.update { it.copy(count = count + scanned) }
                            }
                            ArtworkCache.invalidate()
                            mutable.update { it.copy(completedFolders = it.completedFolders + 1) }
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) {
                            failures += root
                            mutable.update { it.copy(completedFolders = it.completedFolders + 1) }
                        }
                        count = state.value.count
                        current = null
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
                current = null; pending.clear()
                mutable.update { it.copy(running = false, folder = null) }
                marker.edit().putBoolean("running", false).apply()
                finished()
            }
        }
    }
    fun cancel(message: String? = null) {
        if (message != null) interruption = message
        canceledBeforeStart = true; pending.clear(); job?.cancel()
        if (job == null) mutable.update { it.copy(running = false, message = message ?: "Scan canceled.") }
    }
    internal fun failedToStart() {
        mutable.update { it.copy(running = false, message = "Android couldn’t start background scanning. Check Localica’s app settings and try again.") }
    }
}
