package io.github.anastaci1a.localica.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp

/** Retain closing dialogs even when their conditional caller has left composition. */
@Stable
internal class GlassDialogs {
    val entries = mutableStateListOf<GlassDialogEntry>()
    var backdrop by mutableStateOf<FrostedBackdrop?>(null)
}

internal class GlassDialogEntry(val anchor: GlassAnchor, locals: CompositionLocalContext,
    dismiss: () -> Unit, dark: Boolean, val placement: GlassPlacement, content: @Composable () -> Unit) {
    var mounted by mutableStateOf(true)
    var locals by mutableStateOf(locals)
    var dismiss by mutableStateOf(dismiss)
    var dark by mutableStateOf(dark)
    var content by mutableStateOf(content)
}

internal val LocalGlassDialogs = staticCompositionLocalOf<GlassDialogs?> { null }
internal val LocalGlassOrigin = compositionLocalOf<GlassAnchor?> { null }

@Composable
internal fun GlassDialogHost(content: @Composable () -> Unit) {
    if (LocalGlassDialogs.current != null) { content(); return }
    val host = remember { GlassDialogs() }
    CompositionLocalProvider(LocalGlassDialogs provides host) {
        content()
        host.entries.toList().forEach { entry -> key(entry) {
            // Restore caller locals before creating the window. Dialog installs its own
            // view/focus locals; replacing those with Activity locals breaks keyboard input.
            CompositionLocalProvider(entry.locals) {
                GlassMorphPopup(entry.mounted, entry.anchor, { if (entry.mounted) entry.dismiss() },
                    entry.placement, darkOverride = entry.dark, content = entry.content)
            }
            LaunchedEffect(entry.mounted, entry.anchor.hidden) {
                if (!entry.mounted && !entry.anchor.hidden) host.entries.remove(entry)
            }
        } }
    }
}

@Composable
internal fun GlassDialog(onDismiss: () -> Unit, placement: GlassPlacement = GlassPlacement.Center, content: @Composable () -> Unit) {
    val host = checkNotNull(LocalGlassDialogs.current) { "Glass dialogs require LocalicaTheme" }
    val origin = LocalGlassOrigin.current?.takeUnless { placement == GlassPlacement.Page } ?: rememberGlassAnchor()
    val source = LocalModalBackdrop.current ?: LocalFrostedBackdrop.current ?: host.backdrop
    val locals = currentCompositionLocalContext
    val dark = MaterialTheme.colorScheme.onSurface.luminance() > .5f
    val entry = remember { GlassDialogEntry(origin, locals, onDismiss, dark, placement, content) }
    SideEffect {
        origin.source = source
        entry.locals = locals; entry.dismiss = onDismiss; entry.dark = dark; entry.content = content
    }
    DisposableEffect(host, entry) {
        host.entries.add(entry)
        onDispose { entry.mounted = false }
    }
}

@Composable
internal fun GlassAlertDialog(onDismissRequest: () -> Unit, confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier, dismissButton: (@Composable () -> Unit)? = null,
    icon: (@Composable () -> Unit)? = null, title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null) {
    GlassDialog(onDismissRequest) {
        Column(modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            icon?.let { Box(Modifier.align(Alignment.CenterHorizontally)) { it() } }
            title?.let { ProvideTextStyle(MaterialTheme.typography.titleLarge, it) }
            text?.let { Box(Modifier.weight(1f, fill = false)) { ProvideTextStyle(MaterialTheme.typography.bodyMedium, it) } }
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                dismissButton?.invoke(); confirmButton()
            }
        }
    }
}
