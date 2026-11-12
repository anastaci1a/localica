package io.github.anastaci1a.localica.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import io.github.anastaci1a.localica.ui.engine.PreviewContact
import io.github.anastaci1a.localica.ui.engine.glassContactPressure
import io.github.anastaci1a.localica.ui.engine.glassMorphAnimationSpec
import io.github.anastaci1a.localica.ui.engine.previewContactLayer
import io.github.anastaci1a.localica.ui.engine.rememberPreviewContactOwner

/** The glass body consumes contact data through components; playback remains owned by Engine. */
@Stable internal class PreviewContactHandle internal constructor(internal val motion: PreviewContact) {
    val position get() = motion.position
    val pressure get() = motion.pressure
    val pressed get() = motion.pressed
    fun contact(value: Boolean) = motion.contact(value)
    fun start() = motion.start()
    fun dragBy(delta: Float) = motion.dragBy(delta)
    fun release() = motion.release()
}

@Composable internal fun rememberPreviewContactHandle(limit: () -> Float, reduced: Boolean): PreviewContactHandle {
    val owner = rememberPreviewContactOwner(limit, reduced)
    return remember(owner) { PreviewContactHandle(owner) }
}

internal fun previewEdgePressure(excess: Float) = glassContactPressure(excess)
internal fun previewGlassSpring(speed: Float) = glassMorphAnimationSpec(speed)
internal fun Modifier.previewContactBinding(contact: PreviewContactHandle, scaleX: () -> Float, scaleY: () -> Float): Modifier =
    previewContactLayer(contact.motion, scaleX, scaleY)
