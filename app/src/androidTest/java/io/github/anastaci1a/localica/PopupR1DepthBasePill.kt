package io.github.anastaci1a.localica

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import io.github.anastaci1a.localica.ui.*
import io.github.anastaci1a.localica.ui.spec.ControlSize
import io.github.anastaci1a.localica.ui.spec.StockUiSpec

/** Fixture adapter for the synthetic split pill, using only published base APIs.
 * Geometry is the same two SettingsIcon*2 cells and divider as the candidate.
 * The base material retains its own anchor binding and shadow implementation. */
@Composable
internal fun R1BasePopupActionPill(anchor: GlassAnchor, source: FrostedBackdrop?, modifier: Modifier,
    trailingLabel: String, menuVisible: Boolean, onMenuDismiss: () -> Unit,
    menuContent: @Composable ColumnScope.() -> Unit, onSearch: () -> Unit, onMore: () -> Unit) {
    val iconSize = StockUiSpec.control.getValue(ControlSize.SettingsIcon)
    val extent = iconSize * 2
    SideEffect {
        anchor.source = source
        anchor.face = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                Box(Modifier.size(extent), contentAlignment = Alignment.Center) { Icon(appIconPainter(AppIcon.ActionSearch), null, Modifier.size(iconSize)) }
                GlassPillDivider()
                Box(Modifier.size(extent), contentAlignment = Alignment.Center) { Icon(appIconPainter(AppIcon.ActionEllipsis), null, Modifier.size(iconSize)) }
            }
        }
    }
    LiquidGlassSurface(modifier.glassAnchor(anchor).testTag("utility-pill"), source = source,
        shadow = true, contactAdmission = { !anchor.hidden }, depthScale = anchor.depthScale) {
        anchor.captureInk()
        Row(Modifier.align(Alignment.Center), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center) {
            ActionIcon(AppIcon.ActionSearch, "Search library", glass = false, iconSize = iconSize,
                onClick = { anchor.reclaimOpening?.invoke(); onSearch() })
            GlassPillDivider()
            ActionIcon(AppIcon.ActionEllipsis, trailingLabel, glass = false, iconSize = iconSize,
                onClick = { anchor.reclaimOpening?.invoke(); onMore() })
        }
    }
    GlassMenu(menuVisible, anchor, onMenuDismiss, content = menuContent)
}

/** Published base exposes its phase only through the existing geometry observer. */
@Suppress("UNUSED_PARAMETER")
internal fun r1CandidateFlightPhase(flight: GlassPopupFlight): Float? = null
