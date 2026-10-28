package io.github.anastaci1a.localica.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.R

/** One joined surface narrows its neck before handing two tangent bodies their
 * independent clocks. Rejoining follows the same geometry in reverse. */
@Composable
internal fun LyricsHeaderActions(lyricsOpen: Boolean, reveal: Float, anchor: GlassAnchor,
    preparation: GlassOriginPreparation, onShow: () -> Unit, onSources: () -> Unit, onHide: () -> Unit) {
    val contacts = remember(anchor) { GlassSplitContact(anchor.contact) }
    fun horizontal(shape: GlassShape, contact: GlassContact) =
        if (contact.vertical) shape.copy(strain = -shape.strain) else shape
    val progress = preparation.progress
    val separated = progress >= .5f
    val gap = ((progress - .5f) * 2f).coerceIn(0f, 1f)
    val originPresentation: (Float) -> GlassOriginPresentation = remember(anchor, contacts, preparation) {
        { homeHeight ->
            val separation = ((preparation.progress - .5f) * 2f).coerceIn(0f, 1f)
            val common = horizontal(contacts.presentation(contacts.left, contacts.right, 0f), contacts.left)
            GlassOriginPresentation(
                shape = contacts.presentation(contacts.left, contacts.right, separation),
                vertical = contacts.left.vertical,
                horizontalShift = -homeHeight / 2f * (common.scaleX() - 1f) * (1f - separation))
        }
    }
    DisposableEffect(anchor, originPresentation) {
        anchor.originPresentation = originPresentation
        onDispose { if (anchor.originPresentation === originPresentation) anchor.originPresentation = null }
    }
    // A fast source tap can finish the still-growing two-action header without
    // substituting a full-width pill at the beginning of preparation.
    val expanded = maxOf(reveal, (progress * 2f).coerceIn(0f, 1f))
    val width = CompactControlSize * (1f + expanded + gap / 6f)
    val silhouette = rememberSplitPillSilhouette(progress = { preparation.progress })
    val shadow = rememberGlassSilhouette { size ->
        val p = preparation.progress
        val bodies = splitPillBodies(size, p)
        if (p < .5f) bodies.map { it.deformed(contacts.joined.body.value, Offset(size.width / 2f, size.height / 2f)) }
        else {
            val separation = ((p - .5f) * 2f).coerceIn(0f, 1f)
            val common = horizontal(contacts.presentation(contacts.left, contacts.right, 0f), contacts.left)
            bodies.mapIndexedNotNull { index, body ->
                if (index == 0 && anchor.shadowInModal) null else {
                    val shape = if (index == 0) horizontal(contacts.presentation(contacts.left, contacts.right, separation), contacts.left)
                        else horizontal(contacts.presentation(contacts.right, contacts.left, separation), contacts.right)
                    val shift = (if (index == 0) -1f else 1f) * size.height / 2f * (common.scaleX() - 1f) * (1f - separation)
                    body.deformed(shape, Offset((body.left + body.right) / 2f, size.height / 2f), Offset(shift, 0f))
                }
            }
        }
    }
    SideEffect {
        contacts.update(separated)
        anchor.quiet = false
        anchor.depthScale = RaisedGlassDepthScale
        anchor.face = { Icon(painterResource(R.drawable.ic_lyrics), null, Modifier.size(24.dp)) }
    }
    val source = LocalFrostedBackdrop.current
    SideEffect { anchor.source = source }

    @Composable fun leftAction() {
        anchor.captureInk()
        ActionIconButton(if (lyricsOpen) onSources else onShow, Modifier.fillMaxSize()) {
            Icon(painterResource(R.drawable.ic_lyrics), if (lyricsOpen) "Lyrics sources" else "Show lyrics", Modifier.size(24.dp))
        }
    }
    @Composable fun rightAction() {
        val colors = androidx.compose.material3.IconButtonDefaults.iconButtonColors()
        val foreground = controlStateColor(lyricsOpen, colors.contentColor, colors.disabledContentColor,
            "Action icon availability")
        if (lyricsOpen) ActionIconButton(onHide, Modifier.fillMaxSize()) {
            Icon(painterResource(R.drawable.ic_eye_off), "Hide lyrics", Modifier.size(24.dp), tint = foreground)
        } else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            // Keep the closing glyph, not a disabled button whose hit region can
            // cover Show lyrics as the two circles overlap during contraction.
            Icon(painterResource(R.drawable.ic_eye_off), null, Modifier.size(24.dp), tint = foreground)
        }
    }
    Box(Modifier.width(width).height(CompactControlSize).preparedOrigin(preparation, progress)
        .testTag("player-lyrics-pill").silhouetteGlassShadow(shadow)) {
        if (!separated) {
            LiquidGlassSurface(Modifier.fillMaxSize(), silhouette = silhouette, shadow = false,
                sharedContact = contacts.joined, depthScale = RaisedGlassDepthScale) {
                Box(Modifier.align(Alignment.CenterStart).size(CompactControlSize).glassAnchor(anchor)) { leftAction() }
                if (expanded > 0f) {
                    GlassPillDivider(Modifier.align(Alignment.Center).graphicsLayer {
                        alpha = ((expanded - .4f) / .6f).coerceIn(0f, 1f) * (1f - progress * 2f)
                    })
                    Box(Modifier.align(Alignment.CenterEnd).size(CompactControlSize)
                        .graphicsLayer { alpha = ((expanded - .25f) / .75f).coerceIn(0f, 1f) }
                        .then(if (!lyricsOpen) Modifier.clearAndSetSemantics {} else Modifier)) { rightAction() }
                }
            }
        } else {
            fun Modifier.pivotCompensation(side: Float) = drawWithContent {
                val common = horizontal(contacts.presentation(contacts.left, contacts.right, 0f), contacts.left)
                val shift = if (side < 0f) originPresentation(CompactControlSize.toPx()).horizontalShift
                    else side * CompactControlSize.toPx() / 2f * (common.scaleX() - 1f) * (1f - gap)
                // Like shared contact scaling, this is a draw-space correction.
                // Moving layout coordinates would resample the backdrop at the cut.
                translate(left = shift) { this@drawWithContent.drawContent() }
            }
            LiquidGlassSurface(Modifier.align(Alignment.CenterStart).size(CompactControlSize)
                .pivotCompensation(-1f).glassAnchor(anchor),
                contactPresentation = { originPresentation(0f).shape },
                shadow = false, depthScale = RaisedGlassDepthScale) { leftAction() }
            LiquidGlassSurface(Modifier.align(Alignment.CenterEnd).size(CompactControlSize).pivotCompensation(1f),
                sharedContact = contacts.right,
                contactPresentation = { contacts.presentation(contacts.right, contacts.left, gap) },
                shadow = false, depthScale = RaisedGlassDepthScale) { rightAction() }
        }
    }
}
