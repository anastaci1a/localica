package io.github.anastaci1a.localica.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material3.Text
import io.github.anastaci1a.localica.ui.engine.*
import io.github.anastaci1a.localica.ui.spec.PopupKind

/** The popup's existing owner and one movable shared instance, borrowed by both native roots. */
@Stable internal class PopupSharedContent internal constructor(
    internal val motion: PopupWindowMotion,
    private val host: MorphHost,
) {
    private val key = Any()
    private var nativeDrawing by mutableStateOf(false)
    private var sourceDrawing by mutableStateOf(true)

    @Composable fun Compact(modifier: Modifier = Modifier) {
        Box(modifier.then(host.slots.externalHost { sourceDrawing })) {
            host.Slot(key, 0, Modifier.fillMaxWidth())
        }
    }
    @Composable fun Expanded(modifier: Modifier = Modifier) = host.Slot(key, 1, modifier)
    @Composable fun CompactCopy(modifier: Modifier = Modifier) = MeasureCopy(0, modifier)
    @Composable fun ExpandedCopy(modifier: Modifier = Modifier) = MeasureCopy(1, modifier)
    @Composable private fun MeasureCopy(face: Int, modifier: Modifier) {
        Layout({ host.Copy(key, face) }, modifier.clearAndSetSemantics { }) { children, constraints ->
            val copy = children.single().measure(constraints)
            layout(copy.width, copy.height) { }
        }
    }

    internal fun admitNative() { host.expanded = true; sourceDrawing = false; nativeDrawing = true }
    // The source restoration receipt must include the shared recording. The existing native
    // surface keeps replaying that same instance until the real source draw is acknowledged.
    internal fun restoreInstance() { host.expanded = false; sourceDrawing = true }
    internal fun releaseNative() { nativeDrawing = false }
    internal fun destination(coordinates: LayoutCoordinates?) = host.slots.destinationLayout(coordinates)
    internal fun destinationOrigin(origin: Offset) = host.slots.destinationOrigin(origin)
    internal fun drawNative(scope: DrawScope, coordinates: LayoutCoordinates, view: android.view.View) {
        if (nativeDrawing) host.slots.drawExternal(scope, coordinates, view)
    }
}

@Composable internal fun rememberPopupSharedContent(key: Any, kind: PopupKind = PopupKind.Legacy,
    shared: @Composable SharedScope.() -> Unit): PopupSharedContent {
    val motion = rememberPopupWindowMotion(key, prepared = false, quiet = false, kind = kind)
    val slots = rememberMorphSlots(motion.expansion) { motion.bounds }
    val host = remember(slots) { MorphHost(slots) }
    host.shared = { shared() }
    return remember(motion, host) { PopupSharedContent(motion, host) }
}

/** One semantic/container identity survives the endpoint-specific text renderers. The caller
 * supplies its existing marquee, so this shared element introduces no additional text clock. */
@Composable internal fun SharedScope.MorphPersistentTitle(text: String, compact: TextStyle, expanded: TextStyle,
    expandedContent: @Composable () -> Unit) {
    when (endpoint) {
        0 -> Text(text, style = compact, maxLines = 1, overflow = TextOverflow.Ellipsis)
        1 -> Text(text, style = expanded, maxLines = 1, overflow = TextOverflow.Ellipsis)
        else -> Box(Modifier.clearAndSetSemantics { this.text = AnnotatedString(text) }) {
            if (restingExpanded) expandedContent() else MorphText(text, compact, expanded)
        }
    }
}
