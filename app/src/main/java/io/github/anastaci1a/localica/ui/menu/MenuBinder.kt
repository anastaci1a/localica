package io.github.anastaci1a.localica.ui.menu

import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import io.github.anastaci1a.localica.BuildConfig
import io.github.anastaci1a.localica.ui.*
import io.github.anastaci1a.localica.ui.components.ControlProgress
import io.github.anastaci1a.localica.ui.layout.menu.*
import io.github.anastaci1a.localica.ui.layout.menu.ActionSlot
import io.github.anastaci1a.localica.ui.spec.MenuTextRole

/**
 * #757 binder: maps each admitted row to its geometry slot plus the existing live control. Wiring only: it owns no
 * container, no size and no text style. Rows fire `(token, slot, payload)` at the page dispatcher and never hold a
 * handler. Temporary home: `ui.menu` (Screens); it moves into Layout after the Stage 4 control move.
 */
@Stable internal class MenuHost {
    val core = MenuHostCore()
    val homes = MenuHomes()
    /** The pending result of the composition pass that is running; child owners (card actions) read their token from it. */
    var pending: PendingPublication? = null
    var navigate: (String) -> Unit = {}
    var previews: Map<String, @Composable () -> Unit> = emptyMap()
    fun token(key: QualifiedKey): OwnerToken? = pending?.token(key)
}

/** Popup homes outlive the rows that fill them: a row that scrolls away withdraws nothing but its composition. */
@Stable internal class MenuHomes {
    private val anchors = HashMap<String, GlassAnchor>()
    fun find(popupKey: String): GlassAnchor? = anchors[popupKey]
    fun retain(live: (String) -> Boolean) { anchors.keys.retainAll(live) }
    @Composable fun home(popup: PopupContent): GlassAnchor {
        val ink = LocalContentColor.current
        val popupHome = LocalGlassPopupHome.current
        return remember(popup.key) { anchors.getOrPut(popup.key.path) { GlassAnchor().also { it.ink = ink; it.popupHome = popupHome } } }
    }
}

/**
 * The page host. `declare` has already run in the caller, so [draft] carries the current bindings. Admission is
 * reused for equal content; the result is published to the dispatcher only after a successful apply.
 * [strict] (debug builds) throws on a rejected declaration; otherwise the page fails closed.
 */
@Composable internal fun MenuPageHost(draft: PageDraft, onNavigate: (String) -> Unit = {}, previews: Map<String, @Composable () -> Unit> = emptyMap(),
    strict: Boolean = BuildConfig.DEBUG) {
    val host = remember { MenuHost() }
    val pending = host.core.prepare(draft)
    (pending.admission as? Admission.Rejected)?.let { if (strict) error("Menu declaration rejected: ${it.violations}") }
    host.pending = pending
    SideEffect {
        host.navigate = onNavigate
        host.previews = previews
        host.core.publish(pending)
        pending.accepted?.index?.let { index -> host.homes.retain { index.popup(it) != null || host.core.popups.open.any { entry -> entry.key == it } } }
    }
    MenuScaffold(pending.accepted, scrollable = { Modifier.controlVerticalScroll(it) }, fade = { Modifier },
        tabs = { body, segments -> MenuTabs(body, pending.token(body.key), segments, host) }) { item ->
        MenuItem(item, item.row?.let { pending.token(it.key) }, host)
    }
    host.core.popups.open.toList().forEach { entry -> key(entry.key) { MenuPopup(entry, host) } }
}

private val icons: Map<String, AppIcon> by lazy { AppIcon.entries.associateBy { it.id } }
/** Declarations carry icon keys; an unknown key is a wiring defect and draws the close glyph in release. */
private fun icon(key: String): AppIcon = icons[key] ?: if (BuildConfig.DEBUG) error("Unknown menu icon $key") else AppIcon.ActionClose
@Composable private fun MenuIconFace(key: String, modifier: Modifier) { Icon(appIconPainter(icon(key)), null, modifier) }
private fun Value<Long>.count(): Value<String> = when (this) { is Value.Ready -> Value.Ready("%,d".format(value)); is Value.Loading -> this }

@Composable private fun MenuTabs(body: TabsBody, token: OwnerToken?, segments: SegmentMetrics, host: MenuHost) {
    GlassChoices(body.tabs.map { it.label }, body.tabs.indexOfFirst { it.id == body.selected }.coerceAtLeast(0), horizontal = true, role = Role.Tab,
        cellHeight = segments.cell, labelStyle = segments.style, outerVerticalInset = segments.margin) { index ->
        if (token != null) host.core.dispatcher.select(token, body.tabs[index].id)
    }
}

@Composable private fun MenuItem(item: FlatItem, token: OwnerToken?, host: MenuHost) {
    val row = item.row
    when (item.kind) {
        FlatKind.Heading -> MenuHeadingSlot(item.section.heading.orEmpty())
        FlatKind.Caption -> MenuCaptionSlot(item.section.caption.orEmpty())
        FlatKind.RecordEmpty -> MenuCaptionSlot((row as RecordsContent).empty)
        FlatKind.Row -> if (token != null) MenuRow(row!!, token, host)
        FlatKind.RecordEntry -> if (token != null) RecordRow(row as RecordsContent, item.entry, token, host)
        FlatKind.OrderedEntry -> if (token != null) OrderedRow(row as OrderedListContent, item.entry, token, host)
        FlatKind.OrderedAdd -> if (token != null) (row as OrderedListContent).let { list ->
            MenuActionSlot { GlassActionButton(icon(list.addIconKey.orEmpty()), list.addLabel.orEmpty()) { host.core.dispatcher.click(token) } }
        }
    }
}

/** Activation of a home-capable row: its popup if it declares one, otherwise its Click binding. */
private fun activate(row: RowContent, token: OwnerToken, host: MenuHost) {
    val popup = row.opens
    if (popup != null) host.core.popups.open(token, popup) else host.core.dispatcher.click(token)
}

/** A glass row. Its surface is the popup home and its face is the retained popup face. */
@Composable private fun GlassRow(row: RowContent?, enabled: Boolean, host: MenuHost, onClick: () -> Unit, face: @Composable (Modifier) -> Unit) {
    val anchor = row?.opens?.let { host.homes.home(it) }
    anchor?.captureInk()
    if (anchor != null) SideEffect { anchor.face = { face(Modifier) } }
    val opacity by animateControlStateFloat(if (enabled) 1f else .45f, enabled, "Menu row availability")
    val activation = controlActivation(enabled, onClick)
    MenuSurfaceSlot(surface = { corner, content ->
        LiquidGlassSurface(Modifier.glassShadowOpacity { opacity }.then(if (anchor == null) Modifier else Modifier.glassAnchor(anchor)),
            cornerRadius = corner, pressFeedback = enabled) { content() }
    }) { face(Modifier.ownButtonDrag(enabled).clickable(enabled, role = Role.Button, onClick = activation)) }
}

/** A flat row that opens a popup or fires Click; the row itself is the home. */
@Composable private fun flatActivation(row: RowContent, token: OwnerToken, host: MenuHost, face: @Composable (Modifier) -> Unit): Modifier {
    val anchor = row.opens?.let { host.homes.home(it) }
    anchor?.captureInk()
    if (anchor != null) SideEffect { anchor.face = { face(Modifier) } }
    val activation = controlActivation(row.enabled) { activate(row, token, host) }
    return Modifier.then(if (anchor == null) Modifier else Modifier.glassAnchor(anchor)).ownButtonDrag(row.enabled)
        .clickable(row.enabled, role = Role.Button, onClick = activation)
}

@Composable private fun Chevron(modifier: Modifier) { Icon(appIconPainter(AppIcon.ActionChevronRight), null, modifier) }

@Composable private fun ValueRow(row: RowContent, title: String, value: String, token: OwnerToken, host: MenuHost) {
    val face: @Composable (Modifier) -> Unit = { modifier ->
        MenuRowSlot(MenuRowSize.Control, title, modifier, value = Value.Ready(value), enabled = row.enabled,
            glyph = { Icon(appIconPainter(AppIcon.ActionSort), null, it) })
    }
    face(flatActivation(row, token, host, face))
}

@Composable private fun ActionButton(row: ActionContent, token: OwnerToken?, host: MenuHost) {
    val anchor = row.opens?.let { host.homes.home(it) }
    GlassActionButton(icon(row.iconKey), row.title, if (anchor == null) Modifier else Modifier.glassAnchor(anchor), row.enabled) {
        if (token != null) activate(row, token, host)
    }
}

@Composable private fun MenuRow(row: RowContent, token: OwnerToken, host: MenuHost) {
    val dispatcher = host.core.dispatcher
    when (row) {
        is NavContent -> GlassRow(null, true, host, { if (dispatcher.isEnabled(token)) host.navigate(row.target) }) { modifier ->
            MenuRowSlot(MenuRowSize.Link, row.title, modifier, summary = row.summary?.let { Value.Ready(it) },
                icon = row.iconKey?.let { key -> { MenuIconFace(key, it) } }, glyph = { Chevron(it) })
        }
        is LinkContent -> GlassRow(row, row.enabled, host, { activate(row, token, host) }) { modifier ->
            MenuRowSlot(MenuRowSize.Link, row.title, modifier, summary = row.summary?.let { Value.Ready(it) }, enabled = row.enabled, marker = row.marker,
                icon = row.iconKey?.let { key -> { MenuIconFace(key, it) } }, glyph = { Chevron(it) })
        }
        is ValueContent -> ValueRow(row, row.title, row.value, token, host)
        is ChoiceContent -> MenuChoiceSlot(row.title, row.options.map { it.label }, row.helper, row.enabled, inline = { segments ->
            GlassChoices(row.options.map { it.label }, row.options.indexOfFirst { it.id == row.selected }.coerceAtLeast(0), horizontal = true,
                cellHeight = segments.cell, labelStyle = segments.style, outerVerticalInset = segments.margin) { dispatcher.select(token, row.options[it].id) }
        }, box = { ValueRow(row, row.title, row.options.firstOrNull { it.id == row.selected }?.label.orEmpty(), token, host) })
        is ToggleContent -> {
            val activation = controlActivation(row.enabled) { dispatcher.toggle(token, !row.checked) }
            val change: (Boolean) -> Unit = { dispatcher.toggle(token, it) }
            MenuRowSlot(MenuRowSize.Control, row.title, Modifier.ownButtonDrag(row.enabled, pass = PointerEventPass.Main)
                .toggleable(row.checked, enabled = row.enabled, role = Role.Switch, onValueChange = { activation() }), helper = row.helper, enabled = row.enabled,
                control = { InterfaceSwitch(row.checked, if (row.enabled) change else null) })
        }
        is SliderContent -> MenuSliderSlot(row.title, row.valueLabel, row.low, row.high, row.helper, row.enabled) { modifier ->
            InterfaceSlider(row.value, { dispatcher.number(token, it) }, modifier, row.start..row.end, row.steps, row.enabled, row.curve)
        }
        is ActionContent -> MenuActionSlot { ActionButton(row, token, host) }
        is FieldContent -> MenuControlSlot(row.error) {
            GlassTextField(row.text, { dispatcher.text(token, it) }, label = { MenuText(MenuTextRole.FieldLabel, row.title) }, singleLine = true,
                isError = row.error != null, enabled = row.enabled, keyboardOptions = keyboard(row.keyboard))
        }
        is InfoContent -> MenuRowSlot(MenuRowSize.Control, row.title, if (row.clickable) Modifier.clickable(role = Role.Button) { dispatcher.click(token) } else Modifier,
            value = row.value, icon = row.iconKey?.let { key -> { MenuIconFace(key, it) } })
        is ProgressContent -> MenuProgressSlot(row.status) { modifier ->
            val fraction = row.fraction
            if (fraction == null) LinearProgressIndicator(modifier) else ControlProgress({ fraction }, modifier)
        }
        is NoticeContent -> MenuNoticeSlot(row.text, row.tone, icon = { MenuIconFace(AppIcon.ActionInfo.id, it) }, fix = row.fixLabel?.let { label -> {
            GlassActionButton(icon(row.fixIconKey.orEmpty()), label) { dispatcher.click(token, ActionSlot.FixAction) }
        } })
        is StatusCardContent -> MenuSurfaceSlot(surface = { corner, content -> LiquidGlassSurface(cornerRadius = corner, pressFeedback = false) { content() } }) {
            val progress = row.progress
            val ring: @Composable (Modifier) -> Unit = { modifier -> CircularProgressIndicator(progress = { progress ?: 0f }, modifier = modifier) }
            val glyph: (@Composable (Modifier) -> Unit)? = row.iconKey?.let { key -> { modifier -> MenuIconFace(key, modifier) } }
            MenuCardSlot(row.title, row.infos.map { it.value }, leading = if (progress != null) ring else glyph,
                stats = if (row.stats.isEmpty()) null else { { MenuStatGridSlot(row.stats.map { it.label }, row.stats.map { it.value }) } },
                actions = if (row.actions.isEmpty()) null else { { MenuWrapSlot { row.actions.forEach { action -> key(action.key) { ActionButton(action, host.token(action.key), host) } } } } })
        }
        is LegendContent -> MenuLegendSlot(bar = if (!row.bar) null else { {
            MenuLegendBarSlot(row.entries.map { it.tone }, row.entries.map { ((it.count as? Value.Ready)?.value ?: 0L).toFloat() })
        } }) {
            row.entries.forEach { entry -> key(entry.id) {
                GlassRow(null, entry.clickable, host, { dispatcher.select(token, entry.id, ActionSlot.Click) }) { modifier ->
                    MenuRowSlot(MenuRowSize.Link, entry.title, modifier, summary = entry.count.count(), marker = entry.tone,
                        glyph = if (entry.clickable) { { Chevron(it) } } else null)
                }
            } }
        }
        is PreviewContent -> MenuControlSlot { host.previews[row.previewKey]?.invoke() }
        is OrderedListContent, is RecordsContent -> Unit // flattened: their entries are separate items
    }
}

private fun keyboard(keyboard: FieldKeyboard) = KeyboardOptions(keyboardType = when (keyboard) {
    FieldKeyboard.Text -> KeyboardType.Text; FieldKeyboard.Number -> KeyboardType.Number; FieldKeyboard.Uri -> KeyboardType.Uri })

@Composable private fun RecordRow(row: RecordsContent, index: Int, token: OwnerToken, host: MenuHost) {
    val snapshot = row.snapshot
    val id = snapshot.id(index)
    val dispatcher = host.core.dispatcher
    MenuRowSlot(MenuRowSize.Link, snapshot.title(index), if (row.clickable) Modifier.clickable(role = Role.Button) { dispatcher.select(token, id, ActionSlot.Click) } else Modifier,
        summary = snapshot.subtitle(index)?.let { Value.Ready(it) }, flow = MenuTextFlow.Dynamic,
        icon = snapshot.iconKey(index)?.let { key -> { MenuIconFace(key, it) } },
        control = row.trailingLabel?.let { label -> { ActionIcon(icon(row.trailingIconKey.orEmpty()), label) { dispatcher.select(token, id, ActionSlot.Trailing) } } })
}

@Composable private fun OrderedRow(row: OrderedListContent, index: Int, token: OwnerToken, host: MenuHost) {
    val item = row.items[index]
    val dispatcher = host.core.dispatcher
    MenuRowSlot(MenuRowSize.Link, item.title, summary = item.subtitle?.let { Value.Ready(it) }, flow = MenuTextFlow.Dynamic, control = {
        MenuTrailingActionsSlot {
            ActionIcon(AppIcon.ActionArrowUp, "Move ${item.title} up", enabled = index > 0) { dispatcher.move(token, item.id, index - 1) }
            ActionIcon(AppIcon.ActionArrowDown, "Move ${item.title} down", enabled = index < row.items.lastIndex) { dispatcher.move(token, item.id, index + 1) }
            if (row.removable) ActionIcon(AppIcon.ActionClose, "Remove ${item.title}") { dispatcher.select(token, item.id, ActionSlot.Remove) }
        }
    })
}

/**
 * The popup adapter (DESIGN 3.4, temporary column): opens the existing morph popup from the home its owner
 * registered. Window, visibility and morph stay with the existing popup owner; this only maps intent to it.
 * Leaving this composition is the popup owner's own retirement path.
 */
@Composable private fun MenuPopup(entry: MenuPopupEntry, host: MenuHost) {
    val popups = host.core.popups
    LaunchedEffect(entry, entry.departing) { if (entry.departing) popups.retired(entry) }
    val dismiss = { popups.click(entry, ActionSlot.Dismiss); popups.retired(entry) }
    val close: @Composable (String) -> Unit = { label -> ActionTextButton(onClick = dismiss) { MenuPopupSlot { MenuText(MenuTextRole.ButtonLabel, label) } } }
    CompositionLocalProvider(LocalGlassOrigin provides host.homes.find(entry.key)) {
        when (val content = entry.content) {
            is ConfirmContent -> GlassAlertDialog(onDismissRequest = dismiss, title = { MenuPopupSlot { MenuText(MenuTextRole.PageTitle, content.title) } },
                text = { MenuPopupSlot { MenuText(MenuTextRole.PopupBody, content.message) } },
                confirmButton = { ActionTextButton(onClick = { popups.click(entry, ActionSlot.Confirm); popups.retired(entry) }, enabled = !entry.quarantined) {
                    MenuPopupSlot { MenuText(MenuTextRole.ButtonLabel, content.confirmLabel,
                        emphasis = if (content.role == ActionRole.Destructive) MenuEmphasis.Destructive else MenuEmphasis.Normal) }
                } }, dismissButton = { close(content.dismissLabel) })
            is MessageContent -> GlassAlertDialog(onDismissRequest = dismiss, title = { MenuPopupSlot { MenuText(MenuTextRole.PageTitle, content.title) } },
                text = { MenuPopupSlot { MenuText(MenuTextRole.PopupBody, content.message, tone = content.tone) } }, confirmButton = { close(content.dismissLabel) })
            is TextEntryContent -> GlassAlertDialog(onDismissRequest = dismiss, title = { MenuPopupSlot { MenuText(MenuTextRole.PageTitle, content.title) } },
                text = { MenuPopupSlot { MenuControlSlot(content.error) {
                    GlassTextField(entry.draft, { entry.draft = it }, label = { MenuText(MenuTextRole.FieldLabel, content.label) }, singleLine = true,
                        isError = content.error != null, keyboardOptions = keyboard(content.keyboard))
                } } },
                confirmButton = { ActionTextButton(onClick = { popups.text(entry); popups.retired(entry) }, enabled = !entry.quarantined) {
                    MenuPopupSlot { MenuText(MenuTextRole.ButtonLabel, content.confirmLabel) }
                } }, dismissButton = { close(content.dismissLabel) })
            is SingleChoiceContent -> GlassAlertDialog(onDismissRequest = dismiss, title = { MenuPopupSlot { MenuText(MenuTextRole.PageTitle, content.title) } },
                text = { MenuPopupSlot { MenuPopupListSlot({ Modifier.scrollEdgeFade(it).controlVerticalScroll(it) }) {
                    GlassChoices(content.options.map { it.label }, content.options.indexOfFirst { it.id == entry.staged }.coerceAtLeast(0)) { index ->
                        val id = content.options[index].id
                        if (content.staged) entry.staged = id else { popups.select(entry, id, ActionSlot.Select); popups.retired(entry) }
                    }
                } } },
                confirmButton = { if (content.staged) ActionTextButton(onClick = { popups.select(entry, entry.staged, ActionSlot.Confirm); popups.retired(entry) },
                    enabled = !entry.quarantined) { MenuPopupSlot { MenuText(MenuTextRole.ButtonLabel, content.confirmLabel) } } else close(content.dismissLabel) },
                dismissButton = if (content.staged) { { close(content.dismissLabel) } } else null)
        }
    }
}
