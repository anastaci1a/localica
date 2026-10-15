package io.github.anastaci1a.localica.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.R

/** Both actions press the same shell and keep their own click/drag ownership. */
@Composable
internal fun NavigationUtilityPill(anchor: GlassAnchor, source: FrostedBackdrop,
    modifier: Modifier = Modifier, shadowOpacity: () -> Float = { 1f },
    onSearch: () -> Unit, onMore: () -> Unit) {
    SideEffect {
        anchor.source = source
        anchor.face = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                    Icon(painterResource(R.drawable.ic_search), null, Modifier.size(28.dp))
                }
                GlassPillDivider()
                Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                    Icon(painterResource(R.drawable.ic_more), null, Modifier.size(28.dp))
                }
            }
        }
    }
    LiquidGlassSurface(modifier.glassAnchor(anchor).testTag("utility-pill")
        .bottomGlassShadow(opacity = shadowOpacity), source = source, shadow = false,
        depthScale = RaisedGlassDepthScale) {
        anchor.captureInk()
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            ActionIcon(R.drawable.ic_search, "Search library", glass = false, iconSize = 28.dp, onClick = onSearch)
            GlassPillDivider()
            ActionIcon(R.drawable.ic_more, "More", glass = false, iconSize = 28.dp, onClick = onMore)
        }
    }
}
