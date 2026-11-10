package io.github.anastaci1a.localica.preferences

import android.content.Context
import android.content.SharedPreferences
import io.github.anastaci1a.localica.LocalicaApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** One process owner. Publication means both framework handles and their XML values are in memory. */
class PreparedPreferences(context: Context, scope: CoroutineScope) {
    private val mutableReady = MutableStateFlow(false)
    val ready = mutableReady.asStateFlow()
    @Volatile private var handles: Map<String, SharedPreferences>? = null
    private val preparation = scope.async(Dispatchers.IO) {
        val loaded = names.associateWith { name ->
            context.getSharedPreferences(name, Context.MODE_PRIVATE).also { it.contains("") }
        }
        handles = loaded
        mutableReady.value = true
    }

    suspend fun awaitReady() { preparation.await() }

    /** Never opens a file, falls back to an unprepared handle, or waits for preparation. */
    fun require(name: String): SharedPreferences = checkNotNull(handles) {
        "Await preference readiness before creating preference consumers"
    }.getValue(name)

    companion object {
        private val names = listOf("interface", "queue-settings", "scanner", "scan-progress", "browse-style",
            "library-query", "library-filters", "pins", "player-actions", "tag-jobs", "cover-images",
            "listening-identity", "lyric-sources", "icon-packs")

        fun get(context: Context, name: String): SharedPreferences =
            (context.applicationContext as LocalicaApplication).preferences.require(name)
    }
}
