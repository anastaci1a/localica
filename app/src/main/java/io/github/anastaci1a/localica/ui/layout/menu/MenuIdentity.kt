package io.github.anastaci1a.localica.ui.layout.menu

/**
 * #757 neutral identity contracts. They carry a key and plain content, never rendering. Root `ui` and `ui.pages`
 * implement them; Layout sees pages, settings and icons only through these. The builder copies the key and the
 * plain content out of every instance it is given, so no implementation is ever stored in admitted content.
 */
interface MenuIcon { val key: String }
interface PageRef { val key: String; val title: String; val summary: String? get() = null; val icon: MenuIcon? get() = null }
interface TabId { val key: String }
interface Datum { val key: String }
/** A status readout and the set of data it shows; two visible elements of one panel may not show overlapping data. */
interface InfoKey { val key: String; val data: Set<Datum> }
interface PreviewId { val key: String }
interface HostedPopupId { val key: String }

/** What a row is keyed by: a catalog [SettingKey], or a [local] id unique in its section. */
sealed interface RowKeySource
interface SettingKey : RowKeySource { val id: String; val title: String }
class LocalKey internal constructor(val id: String, val title: String) : RowKeySource
/** A row that is not a searchable setting. [title] is required by every kind that shows one. */
fun local(id: String, title: String = ""): LocalKey = LocalKey(id, title)
