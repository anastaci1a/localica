package io.github.anastaci1a.localica.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.isSpecified
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
    var fitContent by mutableStateOf(false)
}

internal val LocalGlassDialogs = staticCompositionLocalOf<GlassDialogs?> { null }
internal val LocalGlassOrigin = compositionLocalOf<GlassAnchor?> { null }
/** A persistent collection popup can replace its contents without opening a second window. */
internal val LocalInlineGlassDialog = compositionLocalOf { false }

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
                    entry.placement, darkOverride = entry.dark, fitContent = entry.fitContent, content = entry.content)
            }
            LaunchedEffect(entry.mounted, entry.anchor.modalPresent) {
                if (!entry.mounted && !entry.anchor.modalPresent) host.entries.remove(entry)
            }
        } }
    }
}

@Composable
internal fun GlassDialog(onDismiss: () -> Unit, placement: GlassPlacement = GlassPlacement.Center,
    fitContent: Boolean = placement == GlassPlacement.Menu, content: @Composable () -> Unit) {
    if (LocalInlineGlassDialog.current) {
        CompositionLocalProvider(LocalInlineGlassDialog provides false, content = content)
        return
    }
    val host = checkNotNull(LocalGlassDialogs.current) { "Glass dialogs require LocalicaTheme" }
    val trigger = LocalGlassOrigin.current?.takeUnless { placement == GlassPlacement.Page }
    val origin = trigger ?: rememberGlassAnchor()
    val source = LocalModalBackdrop.current ?: LocalFrostedBackdrop.current ?: host.backdrop
    val locals = currentCompositionLocalContext
    // Dialog declarations may live outside the artwork theme containing their trigger.
    // An unanchored window instead follows its current ambient ink, not a remembered seed.
    val ink = trigger?.ink?.takeIf { it.isSpecified } ?: LocalContentColor.current
    val dark = ink.luminance() > .5f
    val entry = remember { GlassDialogEntry(origin, locals, onDismiss, dark, placement, content) }
    SideEffect {
        if (trigger == null) origin.ink = ink
        if (origin.source == null) origin.source = source
        entry.locals = locals; entry.dismiss = onDismiss; entry.dark = dark; entry.content = content
        entry.fitContent = fitContent
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
    text: (@Composable () -> Unit)? = null, actionsMeasurement: (@Composable () -> Unit)? = null,
    widthMeasurement: (@Composable () -> Unit)? = null,
    footer: (@Composable () -> Unit)? = null) {
    GlassDialog(onDismissRequest, fitContent = widthMeasurement != null) {
        PopupNaturalWidth(widthMeasurement) {
            DialogViewport(modifier, header = if (icon == null && title == null) null else ({
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    icon?.let { Box(Modifier.align(Alignment.CenterHorizontally)) { it() } }
                    title?.let { ProvideTextStyle(MaterialTheme.typography.titleLarge, it) }
                }
            }), body = text?.let { body -> ({ ProvideTextStyle(MaterialTheme.typography.bodyMedium, body) }) }, footer = {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    footer?.invoke()
                    @Composable fun Actions() {
                        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            dismissButton?.invoke(); confirmButton()
                        }
                    }
                    if (actionsMeasurement == null) Actions()
                    else StablePopupBody(measurement = actionsMeasurement) { Actions() }
                }
            })
        }
    }
}
