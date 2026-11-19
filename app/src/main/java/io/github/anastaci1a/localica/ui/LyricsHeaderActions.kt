package io.github.anastaci1a.localica.ui

import io.github.anastaci1a.localica.ui.components.TriggerRole

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.boundingRect
import androidx.compose.ui.geometry.center
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.R
import io.github.anastaci1a.localica.ui.engine.*
import io.github.anastaci1a.localica.ui.components.rememberLyricsHeaderSurface
import io.github.anastaci1a.localica.ui.layout.LyricsHeaderLayout
import io.github.anastaci1a.localica.ui.layout.LyricsHeaderJoinedLayout

/** One joined surface narrows its neck before handing two tangent bodies their
 * independent clocks. Rejoining follows the same geometry in reverse. */
@Composable
internal fun LyricsHeaderActions(lyricsOpen: Boolean, reveal: Float, anchor: GlassAnchor,
    preparation: GlassOriginPreparation, onShow: () -> Unit, onSources: () -> Unit, onHide: () -> Unit) {
    val contacts = remember(anchor) { GlassSplitContact(anchor.contact) }
    val separated=preparation.separated
    val surface = rememberLyricsHeaderSurface(reveal) { preparation.progress }
    // A fast source tap can finish the still-growing two-action header without
    // substituting a full-width pill at the beginning of preparation.
    val pill = rememberSplitPill { preparation.progress }
    // Each body casts through the transform its surface draws with, lift and drift included (the anchored left body has neither).
    val shadow = rememberGlassSilhouette { size ->
        val bodies = pill.bodies(size)
        val joined = contacts.joined
        if (!preparation.separated) bodies.map { it.deformed(joined.body.value.bodyTransform(size, joined.vertical), size.center) }
        else {
            val separation = originGap(preparation.progress)
            val common = contacts.presentation(contacts.left, contacts.right, 0f).scaleX(contacts.left.vertical)
            bodies.mapIndexed { index, body ->
                val (contact, other) = if (index == 0) contacts.left to contacts.right else contacts.right to contacts.left
                val shift = (if (index == 0) -1f else 1f) * size.height / 2f * (common - 1f) * (1f - separation)
                val drawn = contacts.presentation(contact, other, separation).bodyTransform(body.boundingRect.size, contact.vertical, anchored = index == 0)
                body.deformed(drawn.copy(drift = drawn.drift + Offset(shift, 0f)), body.center)
            }
        }
    }
    SideEffect {
        contacts.update(separated)
    }
    val source = LocalFrostedBackdrop.current

    @Composable fun leftAction() {
        anchor.captureInk()
        ActionIconButton(if (lyricsOpen) onSources else onShow, Modifier.fillMaxSize()) {
            Icon(appIconPainter(AppIcon.ActionLyrics), if (lyricsOpen) "Lyrics sources" else "Show lyrics", Modifier.size(24.dp))
        }
    }
    @Composable fun rightAction() {
        val colors = androidx.compose.material3.IconButtonDefaults.iconButtonColors()
        val foreground = controlStateColor(lyricsOpen, colors.contentColor, colors.disabledContentColor,
            "Action icon availability")
        if (lyricsOpen) ActionIconButton(onHide, Modifier.fillMaxSize()) {
            Icon(appIconPainter(AppIcon.ActionEyeOff), "Hide lyrics", Modifier.size(24.dp), tint = foreground)
        } else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            // Keep the closing glyph, not a disabled button whose hit region can
            // cover Show lyrics as the two circles overlap during contraction.
            Icon(appIconPainter(AppIcon.ActionEyeOff), null, Modifier.size(24.dp), tint = foreground)
        }
    }
    LyricsHeaderLayout(CompactControlSize, reveal, { preparation.progress },
        modifier = Modifier.preparedOrigin(preparation) { preparation.captured(separated) },
        decoration = pill.follow.testTag("player-lyrics-pill").splitGlassShadow(anchor, shadow)) {
        if (!separated) {
            LiquidGlassSurface(Modifier.fillMaxSize(), silhouette = pill.silhouette, shadow = false,
                sharedContact = contacts.joined, depthScale = RaisedGlassDepthScale) {
                LyricsHeaderJoinedLayout(CompactControlSize, reveal > 0f || preparation.active, lyricsOpen,
                    surface, Modifier.glassAnchor(anchor, source, face = { Icon(appIconPainter(AppIcon.ActionLyrics), null, Modifier.size(io.github.anastaci1a.localica.ui.spec.StockUiSpec.control.getValue(io.github.anastaci1a.localica.ui.spec.ControlSize.SettingsIcon))) }, role = TriggerRole.LyricsHeader(contacts, preparation, separated = separated)), { leftAction() },
                    { GlassPillDivider(it) }, { rightAction() })
            }
        } else {
            LiquidGlassSurface(Modifier.align(Alignment.CenterStart).size(CompactControlSize)
                .glassAnchor(anchor, source, face = { Icon(appIconPainter(AppIcon.ActionLyrics), null, Modifier.size(io.github.anastaci1a.localica.ui.spec.StockUiSpec.control.getValue(io.github.anastaci1a.localica.ui.spec.ControlSize.SettingsIcon))) }, role = TriggerRole.LyricsHeader(contacts, preparation, separated = separated)),
                shadow = false, depthScale = RaisedGlassDepthScale) { leftAction() }
            LiquidGlassSurface(Modifier.align(Alignment.CenterEnd).size(CompactControlSize).glassAnchor(null, role = TriggerRole.LyricsHeader(contacts, preparation, separated = separated, side = 1f)),
                sharedContact = contacts.right,
                shadow = false, depthScale = RaisedGlassDepthScale) { rightAction() }
        }
    }
}
