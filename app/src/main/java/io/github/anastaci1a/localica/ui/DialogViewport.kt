package io.github.anastaci1a.localica.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp

/** Fixed chrome when it fits; oversized chrome remains reachable in the same bounded window. */
@Composable
internal fun DialogViewport(modifier: Modifier = Modifier, header: (@Composable () -> Unit)?,
    body: (@Composable () -> Unit)?, footer: @Composable () -> Unit) {
    val profile = DialogChrome.Regular
    BoxWithConstraints(modifier.fillMaxWidth()) {
        require(constraints.hasBoundedHeight && constraints.hasBoundedWidth)
        val viewportHeight = constraints.maxHeight
        val overflow = rememberScrollState()
        Layout(modifier = Modifier.fillMaxWidth().scrollEdgeFade(overflow).controlVerticalScroll(overflow).padding(profile.perimeterDp.dp),
            content = {
                Box { header?.invoke() }
                Box { footer() }
                if (body != null) Box { body() }
            }) { children, constraints ->
            val gap = profile.gapDp.dp.roundToPx()
            val innerHeight = (viewportHeight - profile.totalPerimeterDp.dp.roundToPx()).coerceAtLeast(0)
            val width = constraints.maxWidth
            val resolved = constraints.copy(minWidth = width, maxWidth = width)
            val chromeConstraints = resolved.copy(minHeight = 0, maxHeight = Constraints.Infinity)
            val top = children[0].measure(chromeConstraints)
            val bottom = children[1].measure(chromeConstraints)
            val gaps = gap * profile.gapCount(header != null, body != null)
            val chrome = top.height + bottom.height + gaps
            // Do not squeeze an existing body to zero merely to keep unusually tall chrome fixed.
            // The minimum itself is capped by this actual window, including large-font/resize cases.
            val bodyLimit = popupBodyHeightLimit(innerHeight, chrome, 48.dp.roundToPx())
            val middle = if (body != null) children[2].measure(resolved.copy(minHeight = 0, maxHeight = bodyLimit)) else null
            val height = chrome + (middle?.height ?: 0)
            layout(width, height) {
                top.place(0, 0)
                middle?.place(0, top.height + if (header == null) 0 else gap)
                bottom.place(0, height - bottom.height)
            }
        }
    }
}

/** Chrome may scroll only when it cannot leave a usable body inside the window. */
internal fun popupBodyHeightLimit(innerHeight: Int, chromeHeight: Int, minimumBody: Int): Int =
    (innerHeight - chromeHeight).coerceAtLeast(minimumBody.coerceAtMost(innerHeight)).coerceAtLeast(0)
