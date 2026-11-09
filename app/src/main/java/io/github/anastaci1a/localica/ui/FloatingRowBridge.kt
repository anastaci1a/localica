package io.github.anastaci1a.localica.ui

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import io.github.anastaci1a.localica.ui.components.*

/** Appearance-only bridge. Native View, focus, lifecycle and activity-result locals come from root.
 * Menu/import/launcher state stays in the source that supplies this face lambda. */
@Composable internal fun HoistedFloatingFace(slot: Any, modifier: Modifier = Modifier,
    visible: Boolean = true, interactive: Boolean = visible, onMeasured: ((IntSize) -> Unit)? = null,
    content: @Composable () -> Unit) {
    val source = LocalFrostedBackdrop.current
    val appearance = LocalGlassAppearance.current
    val home = LocalGlassPopupHome.current
    val sourceVisible = LocalGlassVisible.current
    val reduced = LocalReducedMotion.current
    val speed = LocalMotionSpeed.current
    val ink = LocalContentColor.current
    val style = LocalTextStyle.current
    val colors = MaterialTheme.colorScheme
    val typography = MaterialTheme.typography
    val shapes = MaterialTheme.shapes
    val size = with(LocalDensity.current) { CompactControlSize.roundToPx() }
    val face: @Composable () -> Unit = {
        val admission = LocalRowFaceAdmission.current
        MaterialTheme(colorScheme = colors, typography = typography, shapes = shapes) {
            CompositionLocalProvider(LocalFrostedBackdrop provides source, LocalGlassAppearance provides appearance,
                LocalGlassPopupHome provides home, LocalGlassVisible provides (sourceVisible && admission.drawn),
                LocalReducedMotion provides reduced, LocalMotionSpeed provides speed,
                LocalContentColor provides ink, LocalTextStyle provides style) {
                FloatingRowInput(admission.interactive) { content() }
            }
        }
    }
    FloatingRowFace(slot, IntSize(size, size), visible && sourceVisible, interactive, modifier, onMeasured,
        inline = { FloatingRowInline(modifier, visible, interactive, onMeasured, face) }, face = face)
}
