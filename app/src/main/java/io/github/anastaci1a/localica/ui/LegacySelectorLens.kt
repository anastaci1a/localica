package io.github.anastaci1a.localica.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import io.github.anastaci1a.localica.navigation.GlassAppearance

/** Clear-overlay selectors sample current parametric layers, never a finished moving capture. */
@Composable
internal fun LegacySelectorLens(modifier: Modifier, source: FrostedBackdrop?, ink: FrostedBackdrop?,
    appearance: GlassAppearance, frame: SelectorTrackLens, shell: LensGeometry,
    shellBody: GlassShape, selectorBody: GlassShape, slots: List<SelectorInkSlot>,
    normalInk: Color, selectedInk: Color, shellOrigin: () -> androidx.compose.ui.geometry.Offset) {
    val visible = LocalGlassVisible.current
    if (visible) { BackdropLease(source); BackdropLease(ink) }
    val renderer = remember { SelectorSceneMesh() }
    val contour = remember(frame) { frame.contour() }
    val location = remember { BackdropLocation() }
    val tone = GlassTone(appearance.contrast, appearance.contrastWeight, false)
    val prepared = rememberToneSnapshot(source?.lensSnapshot ?: source?.glassFallback ?: source?.snapshot, tone)
    Canvas(Modifier.selectorTrackPlacement(frame.bounds).then(modifier).backdropLocation(location)) {
        if (!visible) return@Canvas
        val shape = LensGeometry(frame.bounds, frame.corner)
        val contentOrigin = location.position() - frame.bounds.topLeft
        val shellInContent = shell.copy(bounds = shell.bounds.translate(shellOrigin() - contentOrigin))
        val geometry = SelectorSceneGeometry(shellInContent, shape, slots,
            appearance.depth * BottomPillDepthScale * shellBody.thickness,
            appearance.depth * BottomPillDepthScale * selectorBody.thickness,
            ((1f - shellBody.thickness) / .18f).coerceIn(0f, 1f),
            ((1f - selectorBody.thickness) / .18f).coerceIn(0f, 1f))
        val clip = Path().apply { addRoundRect(contour.outline) }
        translate(-frame.bounds.left, -frame.bounds.top) {
            clipPath(clip) {
                if (prepared != null && source != null) with(renderer) {
                    draw(geometry, prepared, source.size, contentOrigin - source.origin,
                        ink?.snapshot, ink?.size ?: androidx.compose.ui.unit.IntSize.Zero,
                        normalInk.toArgb(), selectedInk.toArgb(), tone)
                }
                drawRect(Color.Black.copy(alpha = appearance.overlayDarkening), frame.bounds.topLeft, frame.bounds.size)
                drawGlassRim(frame.bounds, frame.corner)
            }
        }
    }
}
