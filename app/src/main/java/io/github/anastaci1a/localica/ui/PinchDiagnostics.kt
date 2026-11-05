package io.github.anastaci1a.localica.ui

import androidx.compose.runtime.*
import io.github.anastaci1a.localica.diagnostics.Diagnostics
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop

/** Source tag for pinch stuck-state reports and breadcrumbs in the diagnostics recorder (#535 b). */
internal const val PinchDiagnosticsTag = "PinchDiagnostics"
internal const val PinchStuckMillis = 2000L

/**
 * Reports a stuck state that outlives its gesture by [delayMillis], then its recovery, into the
 * in-app diagnostics buffer. The state is observed through a snapshot flow, so a healthy run does
 * no work between transitions. Reports read state only.
 */
@Composable
internal fun StuckStateWatchdog(lingering: () -> Boolean, lingeringReport: () -> String, recoveredReport: () -> String,
    delayMillis: Long = PinchStuckMillis) {
    val isLingering by rememberUpdatedState(lingering)
    val lingeringText by rememberUpdatedState(lingeringReport)
    val recoveredText by rememberUpdatedState(recoveredReport)
    LaunchedEffect(delayMillis) {
        var reported = false
        snapshotFlow { isLingering() }.collectLatest { stuck ->
            if (!stuck) {
                if (reported) Diagnostics.info(PinchDiagnosticsTag, "recovered ${recoveredText()}")
                reported = false
                return@collectLatest
            }
            delay(delayMillis)
            reported = true
            Diagnostics.warn(PinchDiagnosticsTag, "lingering ${lingeringText()}")
        }
    }
}

/** A bounded trail of discrete pinch phase transitions; it never samples per frame. */
@Composable
internal fun PinchBreadcrumbs(crumb: () -> String) {
    val current by rememberUpdatedState(crumb)
    LaunchedEffect(Unit) {
        snapshotFlow { current() }.distinctUntilChanged().drop(1).collect { Diagnostics.info(PinchDiagnosticsTag, it) }
    }
}
