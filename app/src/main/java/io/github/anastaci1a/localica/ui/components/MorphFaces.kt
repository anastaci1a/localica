package io.github.anastaci1a.localica.ui.components

import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import io.github.anastaci1a.localica.ui.engine.*

/** U04.02a (D06(a), D33): a retained face host's one ExpandCollapse on the #608 law. Layout hands it the measured endpoint frames;
 * a request made before them waits. An unchanged basis is no new intent, so hosts may report it on every pass. */
@Stable class FaceExpansion internal constructor(private val motion: PopupMotion, private val density: Float) {
    private var last: ExpansionBasis? = null
    val view: ExpansionView get() = motion.expansion
    fun request(expanded: Boolean) = motion.show(expanded)
    fun measured(start: Rect, end: Rect) { val basis = ExpansionBasis(start, end, density); if (basis != last) { last = basis; motion.place(basis) } }
}
@Composable fun rememberFaceExpansion(expanded: Boolean): FaceExpansion {
    val motion = rememberPopupMotion(remember { Any() }, expanded)
    val density = LocalDensity.current.density
    val face = remember(motion, density) { FaceExpansion(motion, density) }
    SideEffect { face.request(expanded) }
    return face
}

/** D09/B4: a shared element sees the morph as [progress], 0 (compact) to 1 (expanded), sampled only in its own layout or draw. */
@Stable interface SharedScope { val progress: () -> Float }
/** One face of [MorphFaces]. Content outside [Shared] belongs to this face alone and fades with it. */
@Stable class MorphScope internal constructor(private val host: MorphHost, private val face: Int) {
    /** Where shared [key] sits in this face; [modifier] sizes the slot, which takes the element's size at this face's end. */
    @Composable fun Shared(key: Any, modifier: Modifier = Modifier) = host.Slot(key, face, modifier)
}
internal class MorphHost(val slots: MorphSlots) {
    var expanded = false
    var shared: @Composable SharedScope.(Any) -> Unit = {}
    private val live = object : SharedScope { override val progress = slots.progress }
    private val ends = listOf(0f, 1f).map { end -> object : SharedScope { override val progress = { end } } }
    private val instances = mutableMapOf<Any, @Composable (@Composable SharedScope.(Any) -> Unit) -> Unit>()
    @Composable fun Slot(key: Any, face: Int, modifier: Modifier) {
        val content = shared
        val instance = instances.getOrPut(key) { movableContentOf { c: @Composable SharedScope.(Any) -> Unit -> c(live, key) } }
        slots.Slot(key, face, (face == 1) == expanded, LocalContentColor.current, modifier, { content(ends[face], key) }) { instance(content) }
    }
}

/** D09 (I-new): two faces of one surface on one ExpandCollapse. A [shared] element (rendered per key) is ONE movableContentOf instance,
 * placed in the destination face and drawn at the eased frame between its two slots; each slot's size comes from a measure-only copy at
 * that face's end (lead 11:35 AM (1)). Everything else crossfades with its face. */
@Composable fun MorphFaces(expanded: Boolean, shared: @Composable SharedScope.(key: Any) -> Unit, modifier: Modifier = Modifier,
    compact: @Composable MorphScope.() -> Unit, content: @Composable MorphScope.() -> Unit) {
    val expansion = rememberFaceExpansion(expanded)
    val slots = rememberMorphSlots(expansion.view.phase)
    val host = remember(slots) { MorphHost(slots) }
    host.expanded = expanded; host.shared = shared
    slots.Faces(expanded, { small, large -> expansion.measured(centeredFrame(small), centeredFrame(large)) }, modifier,
        { MorphScope(host, 0).compact() }, { MorphScope(host, 1).content() })
}

/** B4: a shared label whose style eases from [compact] to [expanded] on the morph's clock. It lays out and draws per frame and never
 * recomposes for the phase; one line, ellipsized to its slot. */
@Composable fun SharedScope.MorphText(text: String, compact: TextStyle, expanded: TextStyle) {
    val measurer = rememberTextMeasurer()
    val ink = LocalContentColor.current
    val shown = remember { arrayOfNulls<TextLayoutResult>(1) }
    // Draw follows the phase too: a relayout at an unchanged size would not redraw the restyled text.
    Layout({}, Modifier.semantics { this.text = AnnotatedString(text) }.drawBehind { progress(); shown[0]?.let { drawText(it, ink) } }) { _, constraints ->
        val result = measurer.measure(text, lerp(compact, expanded, progress()), TextOverflow.Ellipsis, maxLines = 1,
            constraints = Constraints(maxWidth = constraints.maxWidth))
        shown[0] = result
        layout(constraints.constrainWidth(result.size.width), constraints.constrainHeight(result.size.height)) {}
    }
}
