package io.github.anastaci1a.localica.ui

import android.os.Trace
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent

/** Main-thread display-list cost; native raster costs are separately identified in traces. */
internal fun Modifier.structuralTrace(owner: String): Modifier = drawWithContent {
    Trace.beginSection("structural:" + owner)
    try { drawContent() } finally { Trace.endSection() }
}
