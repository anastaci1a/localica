package io.github.anastaci1a.localica.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.R
import io.github.anastaci1a.localica.ui.spec.Space
import io.github.anastaci1a.localica.ui.spec.StockUiSpec

/** Both actions press the same shell and keep their own click/drag ownership. */
@Composable
internal fun NavigationUtilityPill(anchor: GlassAnchor, source: FrostedBackdrop,
    modifier: Modifier = Modifier, noneAtlasInput: NoneAtlasInput? = null, shadowOpacity: () -> Float = { 1f },
    onSearch: () -> Unit, onMore: () -> Unit) {
    val iconSize = StockUiSpec.space.getValue(Space.Panel)
    SideEffect {
        anchor.source = source
        anchor.face = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                    Icon(appIconPainter(AppIcon.ActionSearch), null, Modifier.size(iconSize))
                }
                GlassPillDivider()
                Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                    Icon(appIconPainter(AppIcon.ActionEllipsis), null, Modifier.size(iconSize))
                }
            }
        }
    }
    LiquidGlassSurface(modifier.glassAnchor(anchor).testTag("utility-pill")
        .pageGlassCaster(opacity = shadowOpacity), source = source, shadow = false,
        depthScale = RaisedGlassDepthScale, noneAtlasInput = noneAtlasInput) {
        anchor.captureInk()
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            ActionIcon(AppIcon.ActionSearch, "Search library", glass = false, iconSize = iconSize, onClick = onSearch)
            GlassPillDivider()
            ActionIcon(AppIcon.ActionEllipsis, "More", glass = false, iconSize = iconSize, onClick = onMore)
        }
    }
}
