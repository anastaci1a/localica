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

/** Both actions press the same shell and keep their own click/drag ownership. */
@Composable
internal fun NavigationUtilityPill(anchor: GlassAnchor, source: FrostedBackdrop,
    modifier: Modifier = Modifier, noneAtlasInput: NoneAtlasInput? = null, shadowOpacity: () -> Float = { 1f },
    onSearch: () -> Unit, onMore: () -> Unit) {
    SideEffect {
        anchor.source = source
        anchor.face = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                    Icon(appIconPainter(AppIcon.ActionSearch), null, Modifier.size(28.dp))
                }
                GlassPillDivider()
                Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                    Icon(appIconPainter(AppIcon.ActionEllipsis), null, Modifier.size(28.dp))
                }
            }
        }
    }
    LiquidGlassSurface(modifier.glassAnchor(anchor).testTag("utility-pill")
        .bottomGlassShadow(opacity = shadowOpacity), source = source, shadow = false,
        depthScale = RaisedGlassDepthScale, noneAtlasInput = noneAtlasInput) {
        anchor.captureInk()
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            ActionIcon(AppIcon.ActionSearch, "Search library", glass = false, iconSize = 28.dp, onClick = onSearch)
            GlassPillDivider()
            ActionIcon(AppIcon.ActionEllipsis, "More", glass = false, iconSize = 28.dp, onClick = onMore)
        }
    }
}
