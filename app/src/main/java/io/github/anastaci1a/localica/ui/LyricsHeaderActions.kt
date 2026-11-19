package io.github.anastaci1a.localica.ui

import io.github.anastaci1a.localica.ui.components.TriggerRole

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.*
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
    // Separated surfaces are the measured header height square at either end, so their casters are too, posed by the surfaces' own presentation.
    val shadow = rememberGlassSilhouette { size ->
        val joined = contacts.joined
        if (!preparation.separated) pill.bodies(size).map { it.deformed(joined.body.value.bodyTransform(size, joined.vertical), size.center) }
        else listOf(-1f, 1f).map { side ->
            val left = if (side < 0f) 0f else size.width - size.height
            val body = RoundRect(left, 0f, left + size.height, size.height, CornerRadius(size.height / 2f))
            val pose = TriggerRole.LyricsHeader(contacts, preparation, true, side).presentation(size.height)
            val drawn = pose.shape.bodyTransform(Size(size.height, size.height), pose.vertical, anchored = side < 0f)
            body.deformed(drawn.copy(drift = drawn.drift + Offset(pose.horizontalShift, 0f)), body.center)
        }
    }
    SideEffect {
        contacts.update(separated)
    }
    val source = LocalFrostedBackdrop.current

    @Composable fun leftAction() {
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
