package io.github.anastaci1a.localica.preferences

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import io.github.anastaci1a.localica.LocalicaApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** One process owner. Publication means both framework handles and their XML values are in memory. */
class PreparedPreferences(context: Context, scope: CoroutineScope, names: List<String> = defaultNames) {
    /** Explicit owner injection preserves isolated context/storage boundaries without reopening on Main. */
    internal interface Owner { val preparedPreferences: PreparedPreferences }
    private data class Prepared(val handles: Map<String, SharedPreferences>, val filesDirectory: java.io.File)
    private val mutableReady = MutableStateFlow(false)
    val ready = mutableReady.asStateFlow()
    @Volatile private var prepared: Prepared? = null
    private val preparation = scope.async(Dispatchers.IO) {
        val filesDirectory = context.filesDir
        val loaded = names.associateWith { name ->
            context.getSharedPreferences(name, Context.MODE_PRIVATE).also { it.contains("") }
        }
        prepared = Prepared(loaded, filesDirectory)
        mutableReady.value = true
    }

    suspend fun awaitReady() { preparation.await() }

    private val entryTails = mutableMapOf<CoroutineScope, Job>()

    /** Link entries before starting them, so a newly ready call cannot overtake a queued continuation. */
    fun launchWhenReady(scope: CoroutineScope, action: suspend () -> Unit): Job {
        // This lock only links lazy jobs. Neither preference preparation nor consumer work runs inside it.
        val job = synchronized(entryTails) {
            val previous = entryTails[scope]
            scope.launch(start = CoroutineStart.LAZY) {
                previous?.join()
                awaitReady()
                // Child completion can resume a successor inline while parent cancellation visits its children.
                scope.ensureActive()
                action()
            }.also { entryTails[scope] = it }
        }
        job.invokeOnCompletion {
            synchronized(entryTails) { if (entryTails[scope] === job) entryTails.remove(scope) }
        }
        job.start()
        return job
    }

    /** Never opens a file, falls back to an unprepared handle, or waits for preparation. */
    private fun requirePrepared(): Prepared = checkNotNull(prepared) {
        "Await preference readiness before creating preference consumers"
    }
    fun require(name: String): SharedPreferences = requirePrepared().handles.getValue(name)

    companion object {
        internal val defaultNames = listOf("interface", "queue-settings", "scanner", "scan-progress", "browse-style",
            "library-query", "library-filters", "pins", "player-actions", "tag-jobs", "cover-images",
            "listening-identity", "lyric-sources", "icon-packs")

        private fun owner(context: Context): PreparedPreferences {
            var current = context
            while (true) {
                if (current is Owner) return current.preparedPreferences
                if (current !is ContextWrapper || current.baseContext === current) break
                current = current.baseContext
            }
            return (context.applicationContext as LocalicaApplication).preferences
        }
        fun get(context: Context, name: String): SharedPreferences = owner(context).require(name)
        fun filesDirectory(context: Context): java.io.File = owner(context).requirePrepared().filesDirectory
    }
}
