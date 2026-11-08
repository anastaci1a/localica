package io.github.anastaci1a.localica.ui.components

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import io.github.anastaci1a.localica.ui.engine.TransportContourBuffer
import io.github.anastaci1a.localica.ui.engine.ownedTransportLayer

/** The morph plan owns its persistent contour; callers retain their exact interpolation. */
internal class TransportContour {
    private val buffer = TransportContourBuffer()
    fun draw(scope: DrawScope, color: Color, build: Path.() -> Unit) = buffer.draw(scope, color, build)
}

/** Scope the existing caller's temporary mask layer without changing bounds or blend math. */
internal inline fun DrawScope.withTransportLayer(bounds: Rect, blendMode: BlendMode = BlendMode.SrcOver,
    content: DrawScope.() -> Unit) = ownedTransportLayer(bounds, blendMode, content)
