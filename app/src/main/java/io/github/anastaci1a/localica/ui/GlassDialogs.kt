package io.github.anastaci1a.localica.ui

import io.github.anastaci1a.localica.ui.spec.GlassDialogHeight

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

/** Appearance captured at an action whose dialog is declared in another composition scope.
 * This carries no geometry: detached children keep their existing presentation. */
internal class GlassDialogAppearance(private val inkState: State<androidx.compose.ui.graphics.Color>,
    private val backdropState: State<FrostedBackdrop?>, val popupHome: GlassPopupHome?) {
    val ink get() = inkState.value
    val backdrop get() = backdropState.value
}
internal val LocalGlassDialogAppearance = compositionLocalOf<GlassDialogAppearance?> { null }

@Composable
internal fun currentGlassDialogAppearance(): GlassDialogAppearance {
    val ink = rememberUpdatedState(LocalContentColor.current)
    val backdrop = rememberUpdatedState(LocalModalBackdrop.current ?: LocalFrostedBackdrop.current)
    val home = LocalGlassPopupHome.current
    return remember(home) { GlassDialogAppearance(ink, backdrop, home) }
}

/** Retain closing dialogs even when their conditional caller has left composition. */
@Stable
internal class GlassDialogs {
    val entries = mutableStateListOf<GlassDialogEntry>()
    var backdrop by mutableStateOf<FrostedBackdrop?>(null)
}

internal class GlassDialogEntry(val anchor: GlassAnchor, locals: CompositionLocalContext,
    dismiss: () -> Unit, dark: Boolean, val placement: GlassPlacement,
    val heightSizing: GlassDialogHeight, content: @Composable () -> Unit) {
    var mounted by mutableStateOf(true)
    var locals by mutableStateOf(locals)
    var dismiss by mutableStateOf(dismiss)
    var dark by mutableStateOf(dark)
    var content by mutableStateOf(content)
    var fitContent by mutableStateOf(false)
    var appearance by mutableStateOf<GlassDialogAppearance?>(null)
    var popupHome by mutableStateOf<GlassPopupHome?>(null)
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
                CompositionLocalProvider(LocalModalBackdrop provides
                    (entry.appearance?.backdrop ?: LocalModalBackdrop.current),
                    LocalGlassPopupHome provides entry.popupHome) {
                    GlassMorphPopup(entry.mounted, entry.anchor, { if (entry.mounted) entry.dismiss() },
                        entry.placement, darkOverride = entry.dark, fitContent = entry.fitContent,
                        heightSizing = entry.heightSizing, content = entry.content)
                }
            }
            LaunchedEffect(entry.mounted, entry.anchor.modalPresent) {
                if (!entry.mounted && !entry.anchor.modalPresent) host.entries.remove(entry)
            }
        } }
    }
}

@Composable
@GlassPopupOpener
internal fun GlassDialog(onDismiss: () -> Unit, placement: GlassPlacement = GlassPlacement.Center,
    fitContent: Boolean = placement == GlassPlacement.Menu,
    heightSizing: GlassDialogHeight = GlassDialogHeight.Content, content: @Composable () -> Unit) {
    require(heightSizing == GlassDialogHeight.Content || placement == GlassPlacement.Sheet)
    if (LocalInlineGlassDialog.current) {
        CompositionLocalProvider(LocalInlineGlassDialog provides false, content = content)
        return
    }
    val host = checkNotNull(LocalGlassDialogs.current) { "Glass dialogs require LocalicaTheme" }
    val trigger = LocalGlassOrigin.current?.takeUnless { placement == GlassPlacement.Page }
    val origin = trigger ?: rememberGlassAnchor()
    val appearance = LocalGlassDialogAppearance.current.takeIf { trigger == null }
    val popupHome = trigger?.popupHome ?: appearance?.popupHome ?: LocalGlassPopupHome.current
    val source = appearance?.backdrop ?: LocalModalBackdrop.current ?: LocalFrostedBackdrop.current ?: host.backdrop
    val locals = currentCompositionLocalContext
    // Dialog declarations may live outside the artwork theme containing their trigger.
    // Detached callbacks carry their launching scope; ordinary unanchored windows
    // follow current ambient ink, not a remembered anchor seed.
    val ink = trigger?.ink?.takeIf { it.isSpecified } ?: appearance?.ink ?: LocalContentColor.current
    val dark = ink.luminance() > .5f
    val resolvedContent: @Composable () -> Unit = {
        CompositionLocalProvider(LocalGlassDialogAppearance provides null, content = content)
    }
    val entry = remember(host, origin, placement) {
        // Conditional callers are disposed at dismissal, while their host entry keeps
        // returning. Reclaim that same animation/window instead of creating a second
        // writer for the trigger's hidden, shadow and expansion state.
        val returning = host.entries.lastOrNull { !it.mounted && it.anchor === origin && it.placement == placement }
        require(returning == null || returning.heightSizing == heightSizing) {
            "Height sizing is fixed while a dialog presentation returns"
        }
        returning ?: GlassDialogEntry(origin, locals, onDismiss, dark, placement, heightSizing, resolvedContent)
    }
    require(entry.heightSizing == heightSizing) { "Height sizing is fixed for a dialog presentation" }
    SideEffect {
        if (trigger == null) origin.ink = ink
        if (origin.source == null) origin.source = source
        entry.locals = locals; entry.dismiss = onDismiss; entry.dark = dark
        entry.content = resolvedContent
        entry.appearance = appearance
        entry.popupHome = popupHome
        entry.fitContent = fitContent
    }
    DisposableEffect(host, entry) {
        entry.mounted = true
        if (entry !in host.entries) host.entries.add(entry)
        onDispose { entry.mounted = false }
    }
}

@Composable
@GlassPopupOpener
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
