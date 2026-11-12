package io.github.anastaci1a.localica.ui

import io.github.anastaci1a.localica.ui.components.TriggerRole

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
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
    fun horizontal(shape: GlassShape, contact: GlassContact) =
        if (contact.vertical) shape.copy(strain = -shape.strain) else shape
    val separated=preparation.separated
    val surface = rememberLyricsHeaderSurface(reveal) { preparation.progress }
    // A fast source tap can finish the still-growing two-action header without
    // substituting a full-width pill at the beginning of preparation.
    val pill = rememberSplitPill { preparation.progress }
    val shadow = rememberGlassSilhouette { size ->
        val p = preparation.progress
        val bodies = pill.bodies(size)
        if (!preparation.separated) bodies.map { it.deformed(contacts.joined.body.value, Offset(size.width / 2f, size.height / 2f)) }
        else {
            val separation = originGap(p)
            val common = horizontal(contacts.presentation(contacts.left, contacts.right, 0f), contacts.left)
            bodies.mapIndexedNotNull { index, body ->
                run {
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
                    surface, Modifier.glassAnchor(anchor, source, face = { Icon(appIconPainter(AppIcon.ActionLyrics), null, Modifier.size(io.github.anastaci1a.localica.ui.spec.StockUiSpec.control.getValue(io.github.anastaci1a.localica.ui.spec.ControlSize.SettingsIcon))) }, role = TriggerRole.LyricsHeader(contacts, preparation)), { leftAction() },
                    { GlassPillDivider(it) }, { rightAction() })
            }
        } else {
            LiquidGlassSurface(Modifier.align(Alignment.CenterStart).size(CompactControlSize)
                .glassAnchor(anchor, source, face = { Icon(appIconPainter(AppIcon.ActionLyrics), null, Modifier.size(io.github.anastaci1a.localica.ui.spec.StockUiSpec.control.getValue(io.github.anastaci1a.localica.ui.spec.ControlSize.SettingsIcon))) }, role = TriggerRole.LyricsHeader(contacts, preparation)),
                shadow = false, depthScale = RaisedGlassDepthScale) { leftAction() }
            LiquidGlassSurface(Modifier.align(Alignment.CenterEnd).size(CompactControlSize).glassAnchor(null, role = TriggerRole.LyricsHeader(contacts, preparation, side = 1f)),
                sharedContact = contacts.right,
                shadow = false, depthScale = RaisedGlassDepthScale) { rightAction() }
        }
    }
}
