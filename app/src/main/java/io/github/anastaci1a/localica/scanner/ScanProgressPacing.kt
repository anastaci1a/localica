package io.github.anastaci1a.localica.scanner

import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** Default pace for count-only scan updates reaching the UI collector. */
internal const val SCAN_PROGRESS_INTERVAL_MILLIS = 250L

/**
 * The scanner reports a count every 25 files. A quick scan of a large library therefore
 * changes [ScanState.count] on nearly every frame, and each change republishes the whole
 * library UI state. Count-only changes during a running scan are paced to at most one per
 * [intervalMillis]; the latest count is always delivered. Every other change (start, folder,
 * folder completion, message, stop) is delivered immediately and already carries the latest count.
 */
internal fun Flow<ScanState>.pacedProgress(intervalMillis: Long = SCAN_PROGRESS_INTERVAL_MILLIS): Flow<ScanState> = channelFlow {
    var published: ScanState? = null
    var cooldown: Job? = null
    collectLatest { scan ->
        val previous = published
        if (previous != null && previous.running && scan.running && scan.copy(count = previous.count) == previous) {
            cooldown?.join()
        }
        send(scan)
        published = scan
        cooldown = launch { delay(intervalMillis) }
    }
}
