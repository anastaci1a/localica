package io.github.anastaci1a.localica.ui

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import io.github.anastaci1a.localica.ui.components.rememberFaceExpansion
import io.github.anastaci1a.localica.ui.engine.CompositorFace
import io.github.anastaci1a.localica.ui.engine.Curve
import io.github.anastaci1a.localica.ui.engine.centeredFrame

/** A compact surface resolves into its resting footprint from this scale on its expansion's phase. */
private const val PresenceScale = .82f

/** Nonmodal glass emergence with stable measurement and a draw-only retained exit.
 * Unplaced children cannot receive taps/focus/accessibility during disappearance.
 * Keep this call composed when hidden; a reversal continues from its displayed phase. */
@Composable
internal fun GlassPresence(visible: Boolean, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    // U04.02a: one ExpandCollapse on the #608 law (D06(a)), from the scaled footprint to the measured one.
    val face = rememberFaceExpansion(visible)
    val inset = glassShadowInset()
    // The retained layer owns the whole material, including its shadow. Hoisting
    // that shadow outside this lifetime would leave a dark silhouette on exit.
    CompositionLocalProvider(LocalSurfaceShadows provides null, LocalPageShadows provides null) {
        CompositorFace(visible, face.view, { face.bounds }, Curve.linear(0f,1f), modifier, inset, scale = PresenceScale,
            onMeasured = { face.measured(centeredFrame(it, PresenceScale), centeredFrame(it)) }, content = content)
    }
}
