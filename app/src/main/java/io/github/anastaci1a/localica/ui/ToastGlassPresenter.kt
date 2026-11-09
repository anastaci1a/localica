package io.github.anastaci1a.localica.ui

import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import io.github.anastaci1a.localica.ui.components.*
import io.github.anastaci1a.localica.ui.spec.*

/** Appearance and typed navigation only. The root invokes this outside both player masks and all
 * sampled scene recordings. Native context and lifecycle remain inherited from this Activity. */
@Composable internal fun ToastGlassPresenter(root: ToastRowRoot, source: FrostedBackdrop, modal: Boolean) {
    val queue = LocalToastQueue.current ?: return
    val navigation = checkNotNull(LocalToastNavigation.current)
    val spec = LocalUiSpec.current.spec
    val shadow = with(LocalDensity.current) { glassShadowInset().toPx() }
    CompositionLocalProvider(LocalFrostedBackdrop provides source) {
        ToastPresenter(queue, root, modal, navigation::open, shadow,
            surface = { modifier, body ->
                LiquidGlassSurface(modifier, source = source, cornerRadius = spec.radius.getValue(Radius.Card),
                    pressFeedback = false) { body() }
            }, content = { model ->
                ToastBody(model, MaterialTheme.typography.bodyMedium,
                    artwork = { uri, modifier -> AlbumArtwork(uri, modifier) },
                    action = { label, activate, enabled, modifier ->
                        ActionTextButton(activate, modifier, enabled) { Text(label) }
                    })
            })
    }
}
